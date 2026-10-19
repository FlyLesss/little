package com.little.points.persistence.jdbc;

import com.little.points.domain.IssuanceRecord;
import com.little.points.domain.LedgerEntry;
import com.little.points.domain.LedgerEntryType;
import com.little.points.domain.PointsAccount;
import com.little.points.domain.PointsException;
import com.little.points.port.PaymentEvent;
import com.little.points.port.PointsStore;
import com.little.points.port.PointsTx;
import com.little.points.port.RedeemEvent;
import com.little.points.port.RefundEvent;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * JDBC adapter for PostgreSQL-compatible databases. The account row is locked with
 * {@code SELECT ... FOR UPDATE} and updated with an optimistic version check.
 * Install {@code db/points-schema.sql} before use. Balance reads sum the ledger.
 */
public final class JdbcPointsStore implements PointsStore {

    private static final String INSERT_ACCOUNT = """
            INSERT INTO points_account (account_id, balance, version) VALUES (?, 0, 0)
            """;

    private static final String LOCK_ACCOUNT = """
            SELECT balance, version FROM points_account WHERE account_id = ? FOR UPDATE
            """;

    private static final String UPDATE_ACCOUNT = """
            UPDATE points_account SET balance = ?, version = version + 1
            WHERE account_id = ? AND version = ?
            """;

    private static final String SELECT_ACCOUNT = """
            SELECT balance, version FROM points_account WHERE account_id = ?
            """;

    private static final String SELECT_ACCOUNT_BY_ORDER = """
            SELECT account_id FROM points_payment_event WHERE order_id = ?
            """;

    private static final String SELECT_PAYMENT_BY_ID = """
            SELECT payment_event_id, order_id, account_id, fingerprint, points_granted, created_at_ms
            FROM points_payment_event WHERE payment_event_id = ?
            """;

    private static final String SELECT_PAYMENT_BY_ORDER = """
            SELECT payment_event_id, order_id, account_id, fingerprint, points_granted, created_at_ms
            FROM points_payment_event WHERE order_id = ?
            """;

    private static final String INSERT_PAYMENT = """
            INSERT INTO points_payment_event (
                payment_event_id, order_id, account_id, fingerprint, points_granted, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ISSUANCE = """
            INSERT INTO points_issuance (
                issuance_id, payment_event_id, order_id, line_id, account_id, sku, quantity,
                pay_amount_in_cents, points_granted, rule_id, rule_version, rule_snapshot, issued_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ISSUANCES = """
            SELECT issuance_id, payment_event_id, order_id, line_id, account_id, sku, quantity,
                   pay_amount_in_cents, points_granted, rule_id, rule_version, rule_snapshot, issued_at_ms
            FROM points_issuance WHERE order_id = ? AND account_id = ? ORDER BY line_id
            """;

    private static final String SELECT_REFUND = """
            SELECT refund_id, order_id, account_id, fingerprint, points_reversed, created_at_ms
            FROM points_refund_event WHERE refund_id = ?
            """;

    private static final String INSERT_REFUND = """
            INSERT INTO points_refund_event (
                refund_id, order_id, account_id, fingerprint, points_reversed, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_REDEEM = """
            SELECT redeem_id, account_id, fingerprint, points_spent, created_at_ms
            FROM points_redeem_event WHERE redeem_id = ?
            """;

    private static final String INSERT_REDEEM = """
            INSERT INTO points_redeem_event (
                redeem_id, account_id, fingerprint, points_spent, created_at_ms
            ) VALUES (?, ?, ?, ?, ?)
            """;

    private static final String INSERT_LEDGER = """
            INSERT INTO points_ledger (
                entry_id, account_id, order_id, line_id, entry_type, points, quantity,
                correlation_id, idempotency_key, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_REVERSAL_PROGRESS = """
            SELECT COALESCE(SUM(quantity), 0), COALESCE(SUM(points), 0)
            FROM points_ledger
            WHERE account_id = ? AND order_id = ? AND line_id = ? AND entry_type = 'REVERSAL'
            """;

    private static final String SELECT_LEDGER_BY_CORRELATION = """
            SELECT entry_id, account_id, order_id, line_id, entry_type, points, quantity,
                   correlation_id, idempotency_key, created_at_ms
            FROM points_ledger
            WHERE account_id = ? AND correlation_id = ? AND entry_type = ?
            ORDER BY line_id
            """;

    private static final String SELECT_LEDGER = """
            SELECT entry_id, account_id, order_id, line_id, entry_type, points, quantity,
                   correlation_id, idempotency_key, created_at_ms
            FROM points_ledger WHERE account_id = ? ORDER BY created_at_ms, entry_id
            """;

    private static final String SELECT_BALANCE = """
            SELECT COALESCE(SUM(points), 0) FROM points_ledger WHERE account_id = ?
            """;

    private final DataSource dataSource;

    public JdbcPointsStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void installSchema() {
        String sql = loadSchema();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            for (String statement : statements(sql)) {
                try (Statement jdbc = connection.createStatement()) {
                    jdbc.execute(statement);
                }
            }
        } catch (SQLException ex) {
            throw translate(ex);
        }
    }

    @Override
    public Optional<String> accountIdForOrder(String orderId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_ACCOUNT_BY_ORDER)) {
            statement.setString(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(rows.getString(1));
            }
        } catch (SQLException ex) {
            throw translate(ex);
        }
    }

    @Override
    public <T> T transact(String accountId, Function<PointsTx, T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T result = work.apply(new JdbcTx(connection, accountId));
                connection.commit();
                return result;
            } catch (Throwable failure) {
                rollbackQuietly(connection, failure);
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new PointsException.Persistence("points transaction failed", failure);
            }
        } catch (SQLException ex) {
            throw translate(ex);
        }
    }

    @Override
    public Optional<PointsAccount> findAccount(String accountId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_ACCOUNT)) {
            statement.setString(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PointsAccount(accountId, rows.getLong(1), rows.getLong(2)));
            }
        } catch (SQLException ex) {
            throw translate(ex);
        }
    }

    @Override
    public long balanceOf(String accountId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_BALANCE)) {
            statement.setString(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException ex) {
            throw translate(ex);
        }
    }

    @Override
    public List<LedgerEntry> ledgerOf(String accountId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_LEDGER)) {
            statement.setString(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                List<LedgerEntry> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(mapLedger(rows));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException ex) {
            throw translate(ex);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            failure.addSuppressed(rollbackError);
        }
    }

    static List<String> statements(String sql) {
        String withoutComments = sql.replaceAll("(?m)--.*$", "");
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private static String loadSchema() {
        try (var input = JdbcPointsStore.class.getResourceAsStream("/db/points-schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("missing /db/points-schema.sql");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read points schema", ex);
        }
    }

    private static RuntimeException translate(SQLException ex) {
        if (isDuplicate(ex)) {
            String message = ex.getMessage() == null ? "duplicate key" : ex.getMessage();
            return new PointsException.DuplicateKey(message, ex);
        }
        String message = ex.getMessage() == null ? "database error" : ex.getMessage();
        return new PointsException.Persistence(message, ex);
    }

    private static boolean isDuplicate(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static LedgerEntry mapLedger(ResultSet rows) throws SQLException {
        return new LedgerEntry(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                LedgerEntryType.valueOf(rows.getString(5)),
                rows.getLong(6),
                rows.getInt(7),
                rows.getString(8),
                rows.getString(9),
                Instant.ofEpochMilli(rows.getLong(10)));
    }

    private static void bindLedger(PreparedStatement statement, LedgerEntry entry) throws SQLException {
        statement.setString(1, entry.entryId());
        statement.setString(2, entry.accountId());
        statement.setString(3, entry.orderId());
        statement.setString(4, entry.lineId());
        statement.setString(5, entry.type().name());
        statement.setLong(6, entry.points());
        statement.setInt(7, entry.quantity());
        statement.setString(8, entry.correlationId());
        statement.setString(9, entry.idempotencyKey());
        statement.setLong(10, entry.createdAt().toEpochMilli());
    }

    private final class JdbcTx implements PointsTx {
        private final Connection connection;
        private final String accountId;
        private boolean locked;

        private JdbcTx(Connection connection, String accountId) {
            this.connection = connection;
            this.accountId = accountId;
        }

        @Override
        public PointsAccount lockOrCreateAccount(String id) {
            checkAccount(id);
            try {
                insertAccountIfAbsent(id);
                return lockExisting(id, true);
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public PointsAccount lockAccount(String id) {
            checkAccount(id);
            try {
                return lockExisting(id, false);
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public Optional<PaymentEvent> findPaymentEvent(String paymentEventId) {
            return queryPayment(SELECT_PAYMENT_BY_ID, paymentEventId);
        }

        @Override
        public Optional<PaymentEvent> findPaymentEventByOrder(String orderId) {
            return queryPayment(SELECT_PAYMENT_BY_ORDER, orderId);
        }

        @Override
        public void insertPaymentEvent(PaymentEvent event) {
            requireLocked();
            if (!accountId.equals(event.accountId())) {
                throw new IllegalStateException("payment account mismatch");
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_PAYMENT)) {
                statement.setString(1, event.paymentEventId());
                statement.setString(2, event.orderId());
                statement.setString(3, event.accountId());
                statement.setString(4, event.fingerprint());
                statement.setLong(5, event.pointsGranted());
                statement.setLong(6, event.createdAt().toEpochMilli());
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void insertIssuance(IssuanceRecord record) {
            requireLocked();
            if (!accountId.equals(record.accountId())) {
                throw new IllegalStateException("issuance account mismatch");
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_ISSUANCE)) {
                statement.setString(1, record.issuanceId());
                statement.setString(2, record.paymentEventId());
                statement.setString(3, record.orderId());
                statement.setString(4, record.lineId());
                statement.setString(5, record.accountId());
                statement.setString(6, record.sku());
                statement.setInt(7, record.quantity());
                statement.setLong(8, record.payAmountInCents());
                statement.setLong(9, record.pointsGranted());
                statement.setString(10, record.ruleId());
                statement.setString(11, record.ruleVersion());
                statement.setString(12, record.ruleSnapshot());
                statement.setLong(13, record.issuedAt().toEpochMilli());
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public List<IssuanceRecord> findIssuances(String orderId) {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ISSUANCES)) {
                statement.setString(1, orderId);
                statement.setString(2, accountId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<IssuanceRecord> records = new ArrayList<>();
                    while (rows.next()) {
                        records.add(new IssuanceRecord(
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4),
                                rows.getString(5),
                                rows.getString(6),
                                rows.getInt(7),
                                rows.getLong(8),
                                rows.getLong(9),
                                rows.getString(10),
                                rows.getString(11),
                                rows.getString(12),
                                Instant.ofEpochMilli(rows.getLong(13))));
                    }
                    return List.copyOf(records);
                }
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public Optional<RefundEvent> findRefund(String refundId) {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_REFUND)) {
                statement.setString(1, refundId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new RefundEvent(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getLong(5),
                            Instant.ofEpochMilli(rows.getLong(6))));
                }
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void insertRefund(RefundEvent event) {
            requireLocked();
            if (!accountId.equals(event.accountId())) {
                throw new IllegalStateException("refund account mismatch");
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_REFUND)) {
                statement.setString(1, event.refundId());
                statement.setString(2, event.orderId());
                statement.setString(3, event.accountId());
                statement.setString(4, event.fingerprint());
                statement.setLong(5, event.pointsReversed());
                statement.setLong(6, event.createdAt().toEpochMilli());
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public Optional<RedeemEvent> findRedeem(String redeemId) {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_REDEEM)) {
                statement.setString(1, redeemId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new RedeemEvent(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getLong(4),
                            Instant.ofEpochMilli(rows.getLong(5))));
                }
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void insertRedeem(RedeemEvent event) {
            requireLocked();
            if (!accountId.equals(event.accountId())) {
                throw new IllegalStateException("redeem account mismatch");
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_REDEEM)) {
                statement.setString(1, event.redeemId());
                statement.setString(2, event.accountId());
                statement.setString(3, event.fingerprint());
                statement.setLong(4, event.pointsSpent());
                statement.setLong(5, event.createdAt().toEpochMilli());
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void appendLedger(LedgerEntry entry) {
            requireLocked();
            if (!accountId.equals(entry.accountId())) {
                throw new IllegalStateException("ledger account mismatch");
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_LEDGER)) {
                bindLedger(statement, entry);
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public ReversalProgress reversalProgress(String orderId, String lineId) {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_REVERSAL_PROGRESS)) {
                statement.setString(1, accountId);
                statement.setString(2, orderId);
                statement.setString(3, lineId);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    int quantity = Math.toIntExact(rows.getLong(1));
                    long signed = rows.getLong(2);
                    if (signed > 0) {
                        throw new IllegalStateException("reversal points are positive");
                    }
                    long points = signed == 0 ? 0 : Math.negateExact(signed);
                    return new ReversalProgress(quantity, points);
                }
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public List<LedgerEntry> findLedgerByCorrelation(String correlationId, LedgerEntryType type) {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_LEDGER_BY_CORRELATION)) {
                statement.setString(1, accountId);
                statement.setString(2, correlationId);
                statement.setString(3, type.name());
                try (ResultSet rows = statement.executeQuery()) {
                    List<LedgerEntry> entries = new ArrayList<>();
                    while (rows.next()) {
                        entries.add(mapLedger(rows));
                    }
                    return List.copyOf(entries);
                }
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void updateAccount(PointsAccount proposed) {
            requireLocked();
            if (!accountId.equals(proposed.accountId())) {
                throw new IllegalStateException("account mismatch");
            }
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_ACCOUNT)) {
                statement.setLong(1, proposed.balance());
                statement.setString(2, proposed.accountId());
                statement.setLong(3, proposed.version());
                int updated = statement.executeUpdate();
                if (updated != 1) {
                    throw new PointsException.Conflict("account version mismatch");
                }
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        private void insertAccountIfAbsent(String id) throws SQLException {
            Savepoint savepoint = connection.setSavepoint("create_points_account");
            try (PreparedStatement insert = connection.prepareStatement(INSERT_ACCOUNT)) {
                insert.setString(1, id);
                insert.executeUpdate();
                connection.releaseSavepoint(savepoint);
            } catch (SQLException ex) {
                rollbackSavepoint(savepoint, ex);
                if (!isDuplicate(ex)) {
                    throw ex;
                }
            }
        }

        private void rollbackSavepoint(Savepoint savepoint, SQLException original) throws SQLException {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollbackError) {
                original.addSuppressed(rollbackError);
                throw original;
            }
        }

        private PointsAccount lockExisting(String id, boolean createdIfMissing) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(LOCK_ACCOUNT)) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        if (createdIfMissing) {
                            throw new PointsException.Persistence("account row missing after insert", null);
                        }
                        throw new PointsException.OrderNotFound("points account " + id + " does not exist");
                    }
                    locked = true;
                    return new PointsAccount(id, rows.getLong(1), rows.getLong(2));
                }
            }
        }

        private Optional<PaymentEvent> queryPayment(String sql, String key) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new PaymentEvent(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getLong(5),
                            Instant.ofEpochMilli(rows.getLong(6))));
                }
            } catch (SQLException ex) {
                throw translate(ex);
            }
        }

        private void requireLocked() {
            if (!locked) {
                throw new IllegalStateException("account is not locked");
            }
        }

        private void checkAccount(String id) {
            if (!accountId.equals(id)) {
                throw new IllegalStateException("transaction is bound to " + accountId);
            }
        }
    }
}

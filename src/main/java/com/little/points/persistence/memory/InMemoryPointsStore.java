package com.little.points.persistence.memory;

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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * In-memory adapter. Each account has its own lock, and the account version is checked on update.
 * Mutations are undone if the unit of work throws, so a rejected refund does not leave a partial clawback.
 */
public final class InMemoryPointsStore implements PointsStore {

    private final ConcurrentHashMap<String, AccountLock> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<IssuanceRecord>> issuancesByOrder = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentEvent> paymentById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> paymentIdByOrder = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RefundEvent> refunds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RedeemEvent> redeems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<LedgerEntry>> ledgers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> idempotencyKeys = new ConcurrentHashMap<>();

    @Override
    public Optional<String> accountIdForOrder(String orderId) {
        String paymentId = paymentIdByOrder.get(orderId);
        if (paymentId == null) {
            return Optional.empty();
        }
        PaymentEvent event = paymentById.get(paymentId);
        return event == null ? Optional.empty() : Optional.of(event.accountId());
    }

    @Override
    public <T> T transact(String accountId, Function<PointsTx, T> work) {
        AccountLock guard = accounts.computeIfAbsent(accountId, ignored -> new AccountLock());
        if (guard.lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("nested points transaction");
        }
        guard.lock.lock();
        Undo undo = new Undo();
        try {
            T result = work.apply(new Tx(accountId, guard, undo));
            assertInvariant(guard, ledgers.get(accountId));
            return result;
        } catch (Throwable failure) {
            undo.rollback();
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(failure);
        } finally {
            guard.lock.unlock();
        }
    }

    @Override
    public Optional<PointsAccount> findAccount(String accountId) {
        AccountLock guard = accounts.get(accountId);
        if (guard == null) {
            return Optional.empty();
        }
        guard.lock.lock();
        try {
            return Optional.ofNullable(guard.account);
        } finally {
            guard.lock.unlock();
        }
    }

    @Override
    public long balanceOf(String accountId) {
        long sum = 0;
        for (LedgerEntry entry : ledgerOf(accountId)) {
            sum = Math.addExact(sum, entry.points());
        }
        return sum;
    }

    @Override
    public List<LedgerEntry> ledgerOf(String accountId) {
        AccountLock guard = accounts.get(accountId);
        if (guard == null) {
            return List.of();
        }
        guard.lock.lock();
        try {
            List<LedgerEntry> entries = ledgers.get(accountId);
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            return List.copyOf(entries);
        } finally {
            guard.lock.unlock();
        }
    }

    private static void assertInvariant(AccountLock guard, List<LedgerEntry> entries) {
        long sum = 0;
        if (entries != null) {
            for (LedgerEntry entry : entries) {
                sum = Math.addExact(sum, entry.points());
            }
        }
        long balance = guard.account == null ? 0 : guard.account.balance();
        if (sum != balance) {
            throw new IllegalStateException("balance " + balance + " does not match ledger " + sum);
        }
    }

    private static final class AccountLock {
        private final ReentrantLock lock = new ReentrantLock();
        private PointsAccount account;
    }

    private static final class Undo {
        private final Deque<Runnable> actions = new ArrayDeque<>();

        void add(Runnable action) {
            actions.push(action);
        }

        void rollback() {
            while (!actions.isEmpty()) {
                actions.pop().run();
            }
        }
    }

    private final class Tx implements PointsTx {
        private final String accountId;
        private final AccountLock guard;
        private final Undo undo;
        private boolean locked;

        private Tx(String accountId, AccountLock guard, Undo undo) {
            this.accountId = accountId;
            this.guard = guard;
            this.undo = undo;
        }

        @Override
        public PointsAccount lockOrCreateAccount(String id) {
            checkAccount(id);
            if (guard.account == null) {
                guard.account = new PointsAccount(id, 0, 0);
                undo.add(() -> guard.account = null);
            }
            locked = true;
            return guard.account;
        }

        @Override
        public PointsAccount lockAccount(String id) {
            checkAccount(id);
            if (guard.account == null) {
                throw new PointsException.OrderNotFound("points account " + id + " does not exist");
            }
            locked = true;
            return guard.account;
        }

        @Override
        public Optional<PaymentEvent> findPaymentEvent(String paymentEventId) {
            return Optional.ofNullable(paymentById.get(paymentEventId));
        }

        @Override
        public Optional<PaymentEvent> findPaymentEventByOrder(String orderId) {
            String paymentId = paymentIdByOrder.get(orderId);
            return paymentId == null ? Optional.empty() : Optional.ofNullable(paymentById.get(paymentId));
        }

        @Override
        public void insertPaymentEvent(PaymentEvent event) {
            requireLocked();
            if (!accountId.equals(event.accountId())) {
                throw new IllegalStateException("payment account mismatch");
            }
            if (paymentById.putIfAbsent(event.paymentEventId(), event) != null) {
                throw new PointsException.DuplicateKey("payment event " + event.paymentEventId());
            }
            undo.add(() -> paymentById.remove(event.paymentEventId(), event));
            if (paymentIdByOrder.putIfAbsent(event.orderId(), event.paymentEventId()) != null) {
                throw new PointsException.DuplicateKey("order " + event.orderId() + " already issued");
            }
            undo.add(() -> paymentIdByOrder.remove(event.orderId(), event.paymentEventId()));
        }

        @Override
        public void insertIssuance(IssuanceRecord record) {
            requireLocked();
            if (!accountId.equals(record.accountId())) {
                throw new IllegalStateException("issuance account mismatch");
            }
            List<IssuanceRecord> list = issuancesByOrder.computeIfAbsent(record.orderId(), ignored -> new ArrayList<>());
            synchronized (list) {
                for (IssuanceRecord existing : list) {
                    if (existing.lineId().equals(record.lineId())) {
                        throw new PointsException.DuplicateKey("issuance line " + record.lineId());
                    }
                }
                list.add(record);
            }
            undo.add(() -> {
                synchronized (list) {
                    list.remove(record);
                }
            });
        }

        @Override
        public List<IssuanceRecord> findIssuances(String orderId) {
            List<IssuanceRecord> list = issuancesByOrder.get(orderId);
            if (list == null) {
                return List.of();
            }
            synchronized (list) {
                return List.copyOf(list);
            }
        }

        @Override
        public Optional<RefundEvent> findRefund(String refundId) {
            return Optional.ofNullable(refunds.get(refundId));
        }

        @Override
        public void insertRefund(RefundEvent event) {
            requireLocked();
            if (!accountId.equals(event.accountId())) {
                throw new IllegalStateException("refund account mismatch");
            }
            if (refunds.putIfAbsent(event.refundId(), event) != null) {
                throw new PointsException.DuplicateKey("refund " + event.refundId());
            }
            undo.add(() -> refunds.remove(event.refundId(), event));
        }

        @Override
        public Optional<RedeemEvent> findRedeem(String redeemId) {
            return Optional.ofNullable(redeems.get(redeemId));
        }

        @Override
        public void insertRedeem(RedeemEvent event) {
            requireLocked();
            if (!accountId.equals(event.accountId())) {
                throw new IllegalStateException("redeem account mismatch");
            }
            if (redeems.putIfAbsent(event.redeemId(), event) != null) {
                throw new PointsException.DuplicateKey("redeem " + event.redeemId());
            }
            undo.add(() -> redeems.remove(event.redeemId(), event));
        }

        @Override
        public void appendLedger(LedgerEntry entry) {
            requireLocked();
            if (!accountId.equals(entry.accountId())) {
                throw new IllegalStateException("ledger account mismatch");
            }
            if (idempotencyKeys.putIfAbsent(entry.idempotencyKey(), Boolean.TRUE) != null) {
                throw new PointsException.DuplicateKey("ledger key " + entry.idempotencyKey());
            }
            undo.add(() -> idempotencyKeys.remove(entry.idempotencyKey()));
            List<LedgerEntry> list = ledgers.computeIfAbsent(accountId, ignored -> new ArrayList<>());
            list.add(entry);
            undo.add(() -> list.remove(list.size() - 1));
        }

        @Override
        public ReversalProgress reversalProgress(String orderId, String lineId) {
            int quantity = 0;
            long points = 0;
            for (LedgerEntry entry : ledger()) {
                if (entry.type() != LedgerEntryType.REVERSAL) {
                    continue;
                }
                if (!orderId.equals(entry.orderId()) || !lineId.equals(entry.lineId())) {
                    continue;
                }
                quantity = Math.addExact(quantity, entry.quantity());
                points = Math.addExact(points, negateReversal(entry.points()));
            }
            return new ReversalProgress(quantity, points);
        }

        @Override
        public List<LedgerEntry> findLedgerByCorrelation(String correlationId, LedgerEntryType type) {
            List<LedgerEntry> matched = new ArrayList<>();
            for (LedgerEntry entry : ledger()) {
                if (entry.type() == type && correlationId.equals(entry.correlationId())) {
                    matched.add(entry);
                }
            }
            matched.sort(Comparator.comparing(LedgerEntry::lineId, Comparator.nullsFirst(Comparator.naturalOrder())));
            return List.copyOf(matched);
        }

        @Override
        public void updateAccount(PointsAccount proposed) {
            requireLocked();
            PointsAccount current = guard.account;
            if (current == null
                    || !current.accountId().equals(proposed.accountId())
                    || current.version() != proposed.version()) {
                throw new PointsException.Conflict("account version mismatch");
            }
            PointsAccount previous = current;
            guard.account = new PointsAccount(proposed.accountId(), proposed.balance(), current.version() + 1);
            undo.add(() -> guard.account = previous);
        }

        private List<LedgerEntry> ledger() {
            List<LedgerEntry> list = ledgers.get(accountId);
            return list == null ? List.of() : list;
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

    private static long negateReversal(long signedPoints) {
        if (signedPoints > 0) {
            throw new IllegalStateException("reversal ledger entry has positive points");
        }
        return signedPoints == 0 ? 0 : Math.negateExact(signedPoints);
    }
}

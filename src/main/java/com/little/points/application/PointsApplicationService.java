package com.little.points.application;

import com.little.points.api.LinePoints;
import com.little.points.api.OrderLine;
import com.little.points.api.OrderPaidCommand;
import com.little.points.api.OrderPaidResult;
import com.little.points.api.PointsService;
import com.little.points.api.RedeemCommand;
import com.little.points.api.RedeemResult;
import com.little.points.api.RefundAppliedCommand;
import com.little.points.api.RefundAppliedResult;
import com.little.points.api.RefundLine;
import com.little.points.domain.FloorPayAmountPointsRule;
import com.little.points.domain.IssuanceRecord;
import com.little.points.domain.LedgerEntry;
import com.little.points.domain.LedgerEntryType;
import com.little.points.domain.LineReversal;
import com.little.points.domain.PointsAccount;
import com.little.points.domain.PointsException;
import com.little.points.domain.PointsIssuanceRule;
import com.little.points.port.PaymentEvent;
import com.little.points.port.PointsStore;
import com.little.points.port.PointsTx;
import com.little.points.port.RedeemEvent;
import com.little.points.port.RefundEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Grants points when an order is paid and claws them back when a refund is applied.
 * Refunds read the original issuance and never call {@link PointsIssuanceRule} again.
 */
public final class PointsApplicationService implements PointsService {

    private final PointsStore store;
    private final PointsIssuanceRule rule;
    private final Clock clock;

    public PointsApplicationService(PointsStore store, PointsIssuanceRule rule, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.clock = Objects.requireNonNull(clock, "clock");
        requireLabel(rule.id(), "rule id", 64);
        requireLabel(rule.version(), "rule version", 32);
    }

    public static PointsApplicationService withDefaultRule(PointsStore store) {
        return new PointsApplicationService(store, FloorPayAmountPointsRule.defaults(), Clock.systemUTC());
    }

    @Override
    public OrderPaidResult onOrderPaid(OrderPaidCommand command) {
        if (command == null) {
            throw new PointsException.InvalidRequest("command required");
        }
        String paymentEventId = requireId(command.paymentEventId(), "paymentEventId");
        String orderId = requireId(command.orderId(), "orderId");
        String accountId = requireId(command.accountId(), "accountId");
        List<OrderLine> lines = normalizeOrderLines(command.lines());
        String fingerprint = Fingerprints.orderPaid(accountId, orderId, lines);
        List<GrantLine> grants = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            long points = rule.pointsFor(line.sku(), line.quantity(), line.payAmountInCents());
            if (points < 0) {
                throw new PointsException.InvalidRequest("rule returned negative points");
            }
            String snapshot = rule.snapshot(line.sku(), line.quantity(), line.payAmountInCents());
            if (snapshot == null || snapshot.isBlank() || snapshot.length() > 512) {
                throw new PointsException.InvalidRequest("rule snapshot must be 1..512 characters");
            }
            grants.add(new GrantLine(line, points, snapshot));
        }
        Instant now = now();
        return execute(accountId, tx ->
                applyPaid(tx, paymentEventId, orderId, accountId, fingerprint, grants, now));
    }

    @Override
    public RefundAppliedResult onRefundApplied(RefundAppliedCommand command) {
        if (command == null) {
            throw new PointsException.InvalidRequest("command required");
        }
        String refundId = requireId(command.refundId(), "refundId");
        String orderId = requireId(command.orderId(), "orderId");
        List<RefundLine> lines = normalizeRefundLines(command.lines());
        String accountId = store.accountIdForOrder(orderId)
                .orElseThrow(() -> new PointsException.OrderNotFound(
                        "order " + orderId + " has no points issuance"));
        String fingerprint = Fingerprints.refund(orderId, lines);
        Instant now = now();
        return execute(accountId, tx -> applyRefund(tx, refundId, orderId, accountId, fingerprint, lines, now));
    }

    @Override
    public RedeemResult redeem(RedeemCommand command) {
        if (command == null) {
            throw new PointsException.InvalidRequest("command required");
        }
        String redeemId = requireId(command.redeemId(), "redeemId");
        String accountId = requireId(command.accountId(), "accountId");
        if (command.points() <= 0) {
            throw new PointsException.InvalidRequest("redeem points must be positive");
        }
        String fingerprint = Fingerprints.redeem(accountId, command.points());
        Instant now = now();
        long points = command.points();
        return execute(accountId, tx -> applyRedeem(tx, redeemId, accountId, fingerprint, points, now));
    }

    @Override
    public long balanceOf(String accountId) {
        requireId(accountId, "accountId");
        return store.balanceOf(accountId);
    }

    @Override
    public List<LedgerEntry> ledgerOf(String accountId) {
        requireId(accountId, "accountId");
        return store.ledgerOf(accountId);
    }

    private OrderPaidResult applyPaid(
            PointsTx tx,
            String paymentEventId,
            String orderId,
            String accountId,
            String fingerprint,
            List<GrantLine> grants,
            Instant now) {
        PointsAccount account = tx.lockOrCreateAccount(accountId);
        var existing = tx.findPaymentEvent(paymentEventId);
        if (existing.isPresent()) {
            PaymentEvent event = existing.get();
            if (!event.fingerprint().equals(fingerprint)
                    || !event.accountId().equals(accountId)
                    || !event.orderId().equals(orderId)) {
                throw new PointsException.IdempotencyConflict(
                        "payment event " + paymentEventId + " was already used with a different request");
            }
            List<LinePoints> linePoints = tx.findIssuances(orderId).stream()
                    .map(record -> new LinePoints(record.lineId(), record.quantity(), record.pointsGranted()))
                    .sorted(Comparator.comparing(LinePoints::lineId))
                    .toList();
            return new OrderPaidResult(
                    paymentEventId,
                    orderId,
                    accountId,
                    event.pointsGranted(),
                    account.balance(),
                    linePoints,
                    true);
        }
        if (tx.findPaymentEventByOrder(orderId).isPresent()) {
            throw new PointsException.IdempotencyConflict("order " + orderId + " already has a points issuance");
        }
        long total = 0;
        long balance = account.balance();
        List<LinePoints> linePoints = new ArrayList<>(grants.size());
        try {
            for (GrantLine grant : grants) {
                OrderLine line = grant.line();
                total = Math.addExact(total, grant.points());
                balance = Math.addExact(balance, grant.points());
                tx.insertIssuance(new IssuanceRecord(
                        UUID.randomUUID().toString(),
                        paymentEventId,
                        orderId,
                        line.lineId(),
                        accountId,
                        line.sku(),
                        line.quantity(),
                        line.payAmountInCents(),
                        grant.points(),
                        rule.id(),
                        rule.version(),
                        grant.snapshot(),
                        now));
                tx.appendLedger(new LedgerEntry(
                        UUID.randomUUID().toString(),
                        accountId,
                        orderId,
                        line.lineId(),
                        LedgerEntryType.GRANT,
                        grant.points(),
                        line.quantity(),
                        paymentEventId,
                        "grant:" + paymentEventId + ":" + line.lineId(),
                        now));
                linePoints.add(new LinePoints(line.lineId(), line.quantity(), grant.points()));
            }
        } catch (ArithmeticException ex) {
            throw new PointsException.InvalidRequest("points arithmetic overflow");
        }
        tx.insertPaymentEvent(new PaymentEvent(paymentEventId, orderId, accountId, fingerprint, total, now));
        tx.updateAccount(account.withBalance(balance));
        linePoints.sort(Comparator.comparing(LinePoints::lineId));
        return new OrderPaidResult(
                paymentEventId, orderId, accountId, total, balance, List.copyOf(linePoints), false);
    }

    private RefundAppliedResult applyRefund(
            PointsTx tx,
            String refundId,
            String orderId,
            String accountId,
            String fingerprint,
            List<RefundLine> refundLines,
            Instant now) {
        PointsAccount account = tx.lockAccount(accountId);
        var existing = tx.findRefund(refundId);
        if (existing.isPresent()) {
            RefundEvent event = existing.get();
            if (!event.fingerprint().equals(fingerprint)
                    || !event.orderId().equals(orderId)
                    || !event.accountId().equals(accountId)) {
                throw new PointsException.IdempotencyConflict(
                        "refund " + refundId + " was already used with a different request");
            }
            List<LinePoints> linePoints = tx.findLedgerByCorrelation(refundId, LedgerEntryType.REVERSAL).stream()
                    .map(entry -> new LinePoints(entry.lineId(), entry.quantity(), negate(entry.points())))
                    .sorted(Comparator.comparing(LinePoints::lineId))
                    .toList();
            return new RefundAppliedResult(
                    refundId,
                    orderId,
                    accountId,
                    event.pointsReversed(),
                    account.balance(),
                    linePoints,
                    true);
        }
        Map<String, IssuanceRecord> byLine = tx.findIssuances(orderId).stream()
                .collect(Collectors.toMap(IssuanceRecord::lineId, Function.identity()));
        long balance = account.balance();
        long reversedTotal = 0;
        List<LinePoints> linePoints = new ArrayList<>(refundLines.size());
        try {
            for (RefundLine refundLine : refundLines) {
                IssuanceRecord issuance = byLine.get(refundLine.lineId());
                if (issuance == null) {
                    throw new PointsException.LineNotFound(
                            "order " + orderId + " has no issuance for line " + refundLine.lineId());
                }
                PointsTx.ReversalProgress progress = tx.reversalProgress(orderId, refundLine.lineId());
                long points = LineReversal.pointsToReverse(
                        issuance.pointsGranted(),
                        issuance.quantity(),
                        refundLine.quantity(),
                        progress.quantity(),
                        progress.points());
                reversedTotal = Math.addExact(reversedTotal, points);
                balance = Math.subtractExact(balance, points);
                tx.appendLedger(new LedgerEntry(
                        UUID.randomUUID().toString(),
                        accountId,
                        orderId,
                        refundLine.lineId(),
                        LedgerEntryType.REVERSAL,
                        negate(points),
                        refundLine.quantity(),
                        refundId,
                        "reversal:" + refundId + ":" + refundLine.lineId(),
                        now));
                linePoints.add(new LinePoints(refundLine.lineId(), refundLine.quantity(), points));
            }
        } catch (ArithmeticException ex) {
            throw new PointsException.InvalidRequest("points arithmetic overflow");
        }
        tx.insertRefund(new RefundEvent(refundId, orderId, accountId, fingerprint, reversedTotal, now));
        tx.updateAccount(account.withBalance(balance));
        linePoints.sort(Comparator.comparing(LinePoints::lineId));
        return new RefundAppliedResult(
                refundId, orderId, accountId, reversedTotal, balance, List.copyOf(linePoints), false);
    }

    private RedeemResult applyRedeem(
            PointsTx tx,
            String redeemId,
            String accountId,
            String fingerprint,
            long points,
            Instant now) {
        PointsAccount account = tx.lockOrCreateAccount(accountId);
        var existing = tx.findRedeem(redeemId);
        if (existing.isPresent()) {
            RedeemEvent event = existing.get();
            if (!event.fingerprint().equals(fingerprint) || !event.accountId().equals(accountId)) {
                throw new PointsException.IdempotencyConflict(
                        "redeem " + redeemId + " was already used with a different request");
            }
            return new RedeemResult(redeemId, accountId, event.pointsSpent(), account.balance(), true);
        }
        if (account.balance() < points) {
            throw new PointsException.InsufficientPoints(
                    "balance " + account.balance() + " is below requested " + points);
        }
        long balance;
        try {
            balance = Math.subtractExact(account.balance(), points);
        } catch (ArithmeticException ex) {
            throw new PointsException.InvalidRequest("points arithmetic overflow");
        }
        tx.appendLedger(new LedgerEntry(
                UUID.randomUUID().toString(),
                accountId,
                null,
                null,
                LedgerEntryType.REDEEM,
                negate(points),
                0,
                redeemId,
                "redeem:" + redeemId,
                now));
        tx.insertRedeem(new RedeemEvent(redeemId, accountId, fingerprint, points, now));
        tx.updateAccount(account.withBalance(balance));
        return new RedeemResult(redeemId, accountId, points, balance, false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private <T> T execute(String accountId, Function<PointsTx, T> work) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return store.transact(accountId, work);
            } catch (PointsException.DuplicateKey ex) {
                if (attempt == 2) {
                    throw ex;
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static long negate(long points) {
        return points == 0 ? 0 : Math.negateExact(points);
    }

    private static List<OrderLine> normalizeOrderLines(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new PointsException.InvalidRequest("at least one order line is required");
        }
        Set<String> seen = new HashSet<>();
        List<OrderLine> copy = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            if (line == null) {
                throw new PointsException.InvalidRequest("order line is required");
            }
            requireId(line.lineId(), "lineId");
            requireSku(line.sku());
            if (line.quantity() <= 0) {
                throw new PointsException.InvalidRequest("quantity must be positive");
            }
            if (line.payAmountInCents() < 0) {
                throw new PointsException.InvalidRequest("payAmountInCents must be >= 0");
            }
            if (!seen.add(line.lineId())) {
                throw new PointsException.InvalidRequest("duplicate lineId " + line.lineId());
            }
            copy.add(line);
        }
        return List.copyOf(copy);
    }

    private static List<RefundLine> normalizeRefundLines(List<RefundLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new PointsException.InvalidRequest("at least one refund line is required");
        }
        Set<String> seen = new HashSet<>();
        List<RefundLine> copy = new ArrayList<>(lines.size());
        for (RefundLine line : lines) {
            if (line == null) {
                throw new PointsException.InvalidRequest("refund line is required");
            }
            requireId(line.lineId(), "lineId");
            if (line.quantity() <= 0) {
                throw new PointsException.InvalidRequest("refund quantity must be positive");
            }
            if (!seen.add(line.lineId())) {
                throw new PointsException.InvalidRequest("duplicate lineId " + line.lineId());
            }
            copy.add(line);
        }
        return List.copyOf(copy);
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new PointsException.InvalidRequest(name + " is required");
        }
        if (!value.equals(value.trim())) {
            throw new PointsException.InvalidRequest(name + " must not have surrounding whitespace");
        }
        if (value.length() > 64) {
            throw new PointsException.InvalidRequest(name + " exceeds 64 characters");
        }
        return value;
    }

    private static void requireSku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new PointsException.InvalidRequest("sku is required");
        }
        if (!sku.equals(sku.trim())) {
            throw new PointsException.InvalidRequest("sku must not have surrounding whitespace");
        }
        if (sku.length() > 128) {
            throw new PointsException.InvalidRequest("sku exceeds 128 characters");
        }
    }

    private static void requireLabel(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new PointsException.InvalidRequest(name + " must be 1.." + max + " characters");
        }
    }

    private record GrantLine(OrderLine line, long points, String snapshot) {
    }
}

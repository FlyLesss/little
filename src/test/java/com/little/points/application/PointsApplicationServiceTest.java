package com.little.points.application;

import com.little.points.api.LinePoints;
import com.little.points.api.OrderLine;
import com.little.points.api.OrderPaidCommand;
import com.little.points.api.OrderPaidResult;
import com.little.points.api.PointsService;
import com.little.points.api.RedeemResult;
import com.little.points.api.RefundAppliedCommand;
import com.little.points.api.RefundAppliedResult;
import com.little.points.api.RefundLine;
import com.little.points.domain.FloorPayAmountPointsRule;
import com.little.points.domain.IssuanceRecord;
import com.little.points.domain.LedgerEntry;
import com.little.points.domain.LedgerEntryType;
import com.little.points.domain.PointsException;
import com.little.points.domain.PointsIssuanceRule;
import com.little.points.persistence.jdbc.JdbcPointsStore;
import com.little.points.persistence.memory.InMemoryPointsStore;
import com.little.points.port.PointsStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PointsApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static Stream<Arguments> stores() {
        return Stream.of(
                arguments("memory", (Supplier<PointsStore>) InMemoryPointsStore::new),
                arguments("jdbc", (Supplier<PointsStore>) PointsApplicationServiceTest::newJdbcStore));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void fullRefundReversesEveryPointOnTheLine(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);

        OrderPaidResult paid = points.onOrderPaid(order("acct-1", "order-1", line("line-1", 4, 2599)));

        assertEquals(25, paid.pointsGranted());
        assertFalse(paid.replayed());
        RefundAppliedResult refund = points.onRefundApplied(refund("refund-1", "order-1", "line-1", 4));
        assertEquals(25, refund.pointsReversed());
        assertEquals(0, refund.balanceAfter());
        assertEquals(0, points.balanceOf("acct-1"));
        List<LedgerEntry> ledger = points.ledgerOf("acct-1");
        LedgerEntry grant = ledger.stream().filter(entry -> entry.type() == LedgerEntryType.GRANT).findFirst().orElseThrow();
        LedgerEntry reversal = ledger.stream()
                .filter(entry -> entry.type() == LedgerEntryType.REVERSAL)
                .findFirst()
                .orElseThrow();
        assertEquals(25, grant.points());
        assertEquals(-25, reversal.points());
        assertEquals(NOW, grant.createdAt());
        assertEquals(NOW, reversal.createdAt());
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void partialRefundsFloorAndLastSliceTakesRemainder(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order("acct-1", "order-1", line("line-1", 3, 1000)));

        RefundAppliedResult first = points.onRefundApplied(refund("refund-1", "order-1", "line-1", 1));
        RefundAppliedResult second = points.onRefundApplied(refund("refund-2", "order-1", "line-1", 1));
        RefundAppliedResult third = points.onRefundApplied(refund("refund-3", "order-1", "line-1", 1));

        assertEquals(3, first.pointsReversed());
        assertEquals(3, second.pointsReversed());
        assertEquals(4, third.pointsReversed());
        assertEquals(10, first.pointsReversed() + second.pointsReversed() + third.pointsReversed());
        assertEquals(0, points.balanceOf("acct-1"));
        assertEquals(4, store.findAccount("acct-1").orElseThrow().version());
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void refundOfTwoThenOneLeavesNoStrandedPoints(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order("acct-1", "order-1", line("line-1", 3, 1000)));

        assertEquals(6, points.onRefundApplied(refund("refund-1", "order-1", "line-1", 2)).pointsReversed());
        assertEquals(4, points.onRefundApplied(refund("refund-2", "order-1", "line-1", 1)).pointsReversed());
        assertEquals(0, points.balanceOf("acct-1"));
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void multiLineRefundSumsEachLine(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order(
                "acct-1",
                "order-1",
                line("line-a", 2, 500),
                line("line-b", 4, 1000)));

        RefundAppliedResult refund = points.onRefundApplied(new RefundAppliedCommand(
                "refund-1",
                "order-1",
                List.of(new RefundLine("line-a", 1), new RefundLine("line-b", 1))));

        assertEquals(4, refund.pointsReversed());
        assertEquals(List.of(new LinePoints("line-a", 1, 2), new LinePoints("line-b", 1, 2)), refund.lines());
        assertEquals(11, points.balanceOf("acct-1"));
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void fullRefundOfOneLineLeavesTheOtherLine(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order(
                "acct-1",
                "order-1",
                line("line-a", 1, 1000),
                line("line-b", 1, 500)));

        assertEquals(10, points.onRefundApplied(refund("refund-1", "order-1", "line-a", 1)).pointsReversed());
        assertEquals(5, points.balanceOf("acct-1"));
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void paidAndRefundReplaysDoNotDoubleApply(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        OrderPaidCommand command = order("acct-1", "order-1", line("line-1", 3, 1000));

        OrderPaidResult first = points.onOrderPaid(command);
        OrderPaidResult replay = points.onOrderPaid(new OrderPaidCommand(
                command.paymentEventId(),
                command.orderId(),
                command.accountId(),
                List.of(line("line-1", 3, 1000))));

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.pointsGranted(), replay.pointsGranted());
        assertEquals(1, store.findAccount("acct-1").orElseThrow().version());
        assertEquals(1, points.ledgerOf("acct-1").size());

        RefundAppliedCommand refund = refund("refund-1", "order-1", "line-1", 1);
        RefundAppliedResult reversed = points.onRefundApplied(refund);
        RefundAppliedResult refundReplay = points.onRefundApplied(refund);
        assertFalse(reversed.replayed());
        assertTrue(refundReplay.replayed());
        assertEquals(3, refundReplay.pointsReversed());
        assertEquals(7, points.balanceOf("acct-1"));
        assertEquals(2, points.ledgerOf("acct-1").size());
        assertEquals(2, store.findAccount("acct-1").orElseThrow().version());
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void reorderedLinesCountAsTheSamePaidEvent(String adapter, Supplier<PointsStore> stores) {
        PointsService points = service(stores.get());
        OrderPaidResult first = points.onOrderPaid(new OrderPaidCommand(
                "pay-1",
                "order-1",
                "acct-1",
                List.of(line("line-b", 1, 100), line("line-a", 1, 200))));
        OrderPaidResult replay = points.onOrderPaid(new OrderPaidCommand(
                "pay-1",
                "order-1",
                "acct-1",
                List.of(line("line-a", 1, 200), line("line-b", 1, 100))));

        assertEquals(3, first.pointsGranted());
        assertTrue(replay.replayed());
        assertEquals(3, points.balanceOf("acct-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void sameKeyWithADifferentBodyIsRejected(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order("acct-1", "order-1", line("line-1", 3, 1000)));

        assertThrows(PointsException.IdempotencyConflict.class, () -> points.onOrderPaid(
                order("acct-1", "order-1", line("line-1", 3, 2000))));
        assertThrows(PointsException.IdempotencyConflict.class, () -> points.onOrderPaid(
                new OrderPaidCommand("pay-other", "order-1", "acct-1", List.of(line("line-1", 3, 1000)))));
        points.onRefundApplied(refund("refund-1", "order-1", "line-1", 1));
        assertThrows(PointsException.IdempotencyConflict.class, () -> points.onRefundApplied(
                refund("refund-1", "order-1", "line-1", 2)));
        assertEquals(7, points.balanceOf("acct-1"));
        assertEquals(1, points.ledgerOf("acct-1").stream().filter(entry -> entry.type() == LedgerEntryType.REVERSAL).count());
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void overRefundRollsBackTheWholeCommand(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order(
                "acct-1",
                "order-1",
                line("line-1", 1, 100),
                line("line-2", 1, 100)));

        assertThrows(PointsException.RefundExceedsQuantity.class, () -> points.onRefundApplied(
                new RefundAppliedCommand(
                        "refund-1",
                        "order-1",
                        List.of(new RefundLine("line-1", 1), new RefundLine("line-2", 2)))));

        assertEquals(2, points.balanceOf("acct-1"));
        assertEquals(2, points.ledgerOf("acct-1").size());
        assertThrows(PointsException.LineNotFound.class, () -> points.onRefundApplied(
                refund("refund-2", "order-1", "missing", 1)));
        assertThrows(PointsException.OrderNotFound.class, () -> points.onRefundApplied(
                refund("refund-3", "missing-order", "line-1", 1)));
        assertEquals(2, points.balanceOf("acct-1"));
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void spentPointsStillReverseInFullAndBalanceMayGoNegative(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order("acct-1", "order-1", line("line-1", 1, 1000)));
        RedeemResult spent = points.redeem(new com.little.points.api.RedeemCommand("redeem-1", "acct-1", 10));

        assertEquals(0, spent.balanceAfter());
        assertThrows(PointsException.InsufficientPoints.class, () -> points.redeem(
                new com.little.points.api.RedeemCommand("redeem-2", "acct-1", 1)));
        RedeemResult replay = points.redeem(new com.little.points.api.RedeemCommand("redeem-1", "acct-1", 10));
        assertTrue(replay.replayed());
        assertEquals(0, points.balanceOf("acct-1"));

        RefundAppliedResult refund = points.onRefundApplied(refund("refund-1", "order-1", "line-1", 1));
        assertEquals(10, refund.pointsReversed());
        assertEquals(-10, refund.balanceAfter());
        assertEquals(-10, points.balanceOf("acct-1"));
        assertEquals(-10, points.ledgerOf("acct-1").stream().mapToLong(LedgerEntry::points).sum());
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void issuanceSnapshotIsWhatTheRefundReverses(String adapter, Supplier<PointsStore> stores) {
        AtomicInteger calls = new AtomicInteger();
        PointsIssuanceRule rule = new PointsIssuanceRule() {
            @Override
            public String id() {
                return "fixed-test";
            }

            @Override
            public String version() {
                return "v9";
            }

            @Override
            public long pointsFor(String sku, int quantity, long payAmountInCents) {
                if (calls.incrementAndGet() > 1) {
                    throw new AssertionError("refund must not recompute points");
                }
                return 10;
            }

            @Override
            public String snapshot(String sku, int quantity, long payAmountInCents) {
                return "fixed=10;payAmountInCents=" + payAmountInCents;
            }
        };
        PointsStore store = stores.get();
        PointsService points = service(store, rule);
        points.onOrderPaid(order("acct-1", "order-1", line("line-1", 3, 1000)));
        assertEquals(3, points.onRefundApplied(refund("refund-1", "order-1", "line-1", 1)).pointsReversed());
        assertEquals(1, calls.get());

        IssuanceRecord issuance = store.transact("acct-1", tx -> {
            tx.lockAccount("acct-1");
            return tx.findIssuances("order-1").get(0);
        });
        assertEquals("fixed-test", issuance.ruleId());
        assertEquals("v9", issuance.ruleVersion());
        assertEquals("fixed=10;payAmountInCents=1000", issuance.ruleSnapshot());
        assertEquals(10, issuance.pointsGranted());
        assertEquals(3, issuance.quantity());
        assertEquals("sku-1", issuance.sku());
        assertEquals(NOW, issuance.issuedAt());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void persistsDefaultRuleSnapshot(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        service(store).onOrderPaid(order("acct-1", "order-1", line("mug", 3, 1000)));

        IssuanceRecord issuance = store.transact("acct-1", tx -> {
            tx.lockAccount("acct-1");
            return tx.findIssuances("order-1").get(0);
        });
        assertEquals(FloorPayAmountPointsRule.ID, issuance.ruleId());
        assertEquals(FloorPayAmountPointsRule.VERSION, issuance.ruleVersion());
        assertTrue(issuance.ruleSnapshot().contains("rate=1"));
        assertTrue(issuance.ruleSnapshot().contains("payAmountInCents=1000"));
        assertEquals(10, issuance.pointsGranted());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void duplicatePaidCallbacksGrantOnce(String adapter, Supplier<PointsStore> stores) throws Exception {
        PointsStore store = stores.get();
        PointsService points = service(store);
        OrderPaidCommand command = order("acct-1", "order-1", line("line-1", 2, 1000));
        int threads = 8;
        List<OrderPaidResult> results = race(threads, () -> points.onOrderPaid(command));

        assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
        assertEquals(10, points.balanceOf("acct-1"));
        assertEquals(1, points.ledgerOf("acct-1").size());
        assertEquals(1, store.findAccount("acct-1").orElseThrow().version());
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void duplicateRefundCallbacksReverseOnce(String adapter, Supplier<PointsStore> stores) throws Exception {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order("acct-1", "order-1", line("line-1", 2, 1000)));
        RefundAppliedCommand command = refund("refund-1", "order-1", "line-1", 2);
        List<RefundAppliedResult> results = race(8, () -> points.onRefundApplied(command));

        assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
        assertTrue(results.stream().allMatch(result -> result.pointsReversed() == 10));
        assertEquals(0, points.balanceOf("acct-1"));
        assertEquals(1, points.ledgerOf("acct-1").stream().filter(entry -> entry.type() == LedgerEntryType.REVERSAL).count());
        assertReconciled(points, store, "acct-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void concurrentPartialRefundsSumToTheOriginalGrant(String adapter, Supplier<PointsStore> stores) throws Exception {
        PointsStore store = stores.get();
        PointsService points = service(store);
        points.onOrderPaid(order("acct-1", "order-1", line("line-1", 3, 1000)));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RefundAppliedResult> first = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return points.onRefundApplied(refund("refund-a", "order-1", "line-1", 1));
            });
            Future<RefundAppliedResult> second = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return points.onRefundApplied(refund("refund-b", "order-1", "line-1", 2));
            });
            long reversed = first.get(10, TimeUnit.SECONDS).pointsReversed()
                    + second.get(10, TimeUnit.SECONDS).pointsReversed();
            assertEquals(10, reversed);
            assertEquals(0, points.balanceOf("acct-1"));
            assertReconciled(points, store, "acct-1");
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void subYuanPaymentGrantsNoPointsAndRefundKeepsZero(String adapter, Supplier<PointsStore> stores) {
        PointsStore store = stores.get();
        PointsService points = service(store);
        assertEquals(0, points.onOrderPaid(order("acct-1", "order-1", line("line-1", 2, 99))).pointsGranted());

        assertEquals(0, points.onRefundApplied(refund("refund-1", "order-1", "line-1", 1)).pointsReversed());
        assertEquals(0, points.onRefundApplied(refund("refund-2", "order-1", "line-1", 1)).pointsReversed());
        assertEquals(0, points.balanceOf("acct-1"));
        assertEquals(3, points.ledgerOf("acct-1").size());
        assertReconciled(points, store, "acct-1");
    }

    @Test
    void customRateIsFloorOfPayAmountTimesRate() {
        PointsService points = service(
                new InMemoryPointsStore(),
                new FloorPayAmountPointsRule(5));
        OrderPaidResult paid = points.onOrderPaid(order("acct-1", "order-1", line("line-1", 1, 250)));
        assertEquals(12, paid.pointsGranted());
    }

    private static PointsApplicationService service(PointsStore store) {
        return service(store, FloorPayAmountPointsRule.defaults());
    }

    private static PointsApplicationService service(PointsStore store, PointsIssuanceRule rule) {
        return new PointsApplicationService(store, rule, CLOCK);
    }

    private static void assertReconciled(PointsService points, PointsStore store, String accountId) {
        long ledgerSum = points.ledgerOf(accountId).stream().mapToLong(LedgerEntry::points).sum();
        assertEquals(ledgerSum, points.balanceOf(accountId));
        store.findAccount(accountId).ifPresent(account -> assertEquals(ledgerSum, account.balance()));
    }

    private static <T> List<T> race(int threads, ConcurrentCall<T> call) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return call.get();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static PointsStore newJdbcStore() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:points"
                + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcPointsStore store = new JdbcPointsStore(dataSource);
        store.installSchema();
        return store;
    }

    private static OrderLine line(String lineId, int quantity, long payAmountInCents) {
        String sku = "mug".equals(lineId) ? "mug" : "sku-" + lineId.replace("line-", "");
        return new OrderLine(lineId, sku, quantity, payAmountInCents);
    }

    private static OrderPaidCommand order(String accountId, String orderId, OrderLine... lines) {
        return new OrderPaidCommand("pay-" + orderId, orderId, accountId, List.of(lines));
    }

    private static RefundAppliedCommand refund(String refundId, String orderId, String lineId, int quantity) {
        return new RefundAppliedCommand(refundId, orderId, List.of(new RefundLine(lineId, quantity)));
    }

    @FunctionalInterface
    private interface ConcurrentCall<T> {
        T get() throws Exception;
    }
}

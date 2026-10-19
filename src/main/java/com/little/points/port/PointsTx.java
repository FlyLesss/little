package com.little.points.port;

import com.little.points.domain.IssuanceRecord;
import com.little.points.domain.LedgerEntry;
import com.little.points.domain.LedgerEntryType;
import com.little.points.domain.PointsAccount;

import java.util.List;
import java.util.Optional;

/**
 * Work performed while the account row is locked. Do not call {@code PointsStore.transact} from inside a transaction.
 */
public interface PointsTx {

    record ReversalProgress(int quantity, long points) {
    }

    PointsAccount lockOrCreateAccount(String accountId);

    PointsAccount lockAccount(String accountId);

    Optional<PaymentEvent> findPaymentEvent(String paymentEventId);

    Optional<PaymentEvent> findPaymentEventByOrder(String orderId);

    void insertPaymentEvent(PaymentEvent event);

    void insertIssuance(IssuanceRecord record);

    List<IssuanceRecord> findIssuances(String orderId);

    Optional<RefundEvent> findRefund(String refundId);

    void insertRefund(RefundEvent event);

    Optional<RedeemEvent> findRedeem(String redeemId);

    void insertRedeem(RedeemEvent event);

    void appendLedger(LedgerEntry entry);

    ReversalProgress reversalProgress(String orderId, String lineId);

    List<LedgerEntry> findLedgerByCorrelation(String correlationId, LedgerEntryType type);

    void updateAccount(PointsAccount account);
}

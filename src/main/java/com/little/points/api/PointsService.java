package com.little.points.api;

import com.little.points.domain.LedgerEntry;

import java.util.List;

/**
 * Entry points an order service calls after payment and refund succeed.
 * Balance reads are the sum of immutable ledger entries.
 */
public interface PointsService {

    OrderPaidResult onOrderPaid(OrderPaidCommand command);

    RefundAppliedResult onRefundApplied(RefundAppliedCommand command);

    RedeemResult redeem(RedeemCommand command);

    long balanceOf(String accountId);

    List<LedgerEntry> ledgerOf(String accountId);
}

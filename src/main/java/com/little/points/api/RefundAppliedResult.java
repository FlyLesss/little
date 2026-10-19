package com.little.points.api;

import java.util.List;

public record RefundAppliedResult(
        String refundId,
        String orderId,
        String accountId,
        long pointsReversed,
        long balanceAfter,
        List<LinePoints> lines,
        boolean replayed) {
}

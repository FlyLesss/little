package com.little.points.api;

import java.util.List;

public record OrderPaidResult(
        String paymentEventId,
        String orderId,
        String accountId,
        long pointsGranted,
        long balanceAfter,
        List<LinePoints> lines,
        boolean replayed) {
}

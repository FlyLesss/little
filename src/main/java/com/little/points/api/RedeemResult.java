package com.little.points.api;

public record RedeemResult(
        String redeemId,
        String accountId,
        long pointsSpent,
        long balanceAfter,
        boolean replayed) {
}

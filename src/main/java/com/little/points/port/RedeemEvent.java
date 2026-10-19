package com.little.points.port;

import java.time.Instant;
import java.util.Objects;

public record RedeemEvent(
        String redeemId,
        String accountId,
        String fingerprint,
        long pointsSpent,
        Instant createdAt) {

    public RedeemEvent {
        Objects.requireNonNull(redeemId, "redeemId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        if (pointsSpent <= 0) {
            throw new IllegalArgumentException("pointsSpent must be positive");
        }
    }
}

package com.little.points.port;

import java.time.Instant;
import java.util.Objects;

public record RefundEvent(
        String refundId,
        String orderId,
        String accountId,
        String fingerprint,
        long pointsReversed,
        Instant createdAt) {

    public RefundEvent {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        if (pointsReversed < 0) {
            throw new IllegalArgumentException("pointsReversed must be >= 0");
        }
    }
}

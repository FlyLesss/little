package com.little.points.port;

import java.time.Instant;
import java.util.Objects;

public record PaymentEvent(
        String paymentEventId,
        String orderId,
        String accountId,
        String fingerprint,
        long pointsGranted,
        Instant createdAt) {

    public PaymentEvent {
        Objects.requireNonNull(paymentEventId, "paymentEventId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        if (pointsGranted < 0) {
            throw new IllegalArgumentException("pointsGranted must be >= 0");
        }
    }
}

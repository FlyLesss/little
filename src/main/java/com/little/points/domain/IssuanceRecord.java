package com.little.points.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of points granted for one order line at purchase time.
 * Refunds reverse {@link #pointsGranted()} and {@link #quantity()}; they do not re-run the rule.
 */
public record IssuanceRecord(
        String issuanceId,
        String paymentEventId,
        String orderId,
        String lineId,
        String accountId,
        String sku,
        int quantity,
        long payAmountInCents,
        long pointsGranted,
        String ruleId,
        String ruleVersion,
        String ruleSnapshot,
        Instant issuedAt) {

    public IssuanceRecord {
        Objects.requireNonNull(issuanceId, "issuanceId");
        Objects.requireNonNull(paymentEventId, "paymentEventId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(ruleSnapshot, "ruleSnapshot");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (payAmountInCents < 0 || pointsGranted < 0) {
            throw new IllegalArgumentException("amounts must be >= 0");
        }
    }
}

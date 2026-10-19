package com.little.points.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable ledger posting. Balance is the sum of {@link #points()} across an account.
 * Grant points are positive, reversal and redeem points are negative, so a clawback is never
 * dropped when the member has already spent the points.
 */
public record LedgerEntry(
        String entryId,
        String accountId,
        String orderId,
        String lineId,
        LedgerEntryType type,
        long points,
        int quantity,
        String correlationId,
        String idempotencyKey,
        Instant createdAt) {

    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        switch (type) {
            case GRANT -> {
                if (points < 0) {
                    throw new IllegalArgumentException("grant points must be >= 0");
                }
                if (quantity <= 0 || orderId == null || lineId == null) {
                    throw new IllegalArgumentException("grant requires order line and positive quantity");
                }
            }
            case REVERSAL -> {
                if (points > 0) {
                    throw new IllegalArgumentException("reversal points must be <= 0");
                }
                if (quantity <= 0 || orderId == null || lineId == null) {
                    throw new IllegalArgumentException("reversal requires order line and positive quantity");
                }
            }
            case REDEEM -> {
                if (points >= 0 || quantity != 0) {
                    throw new IllegalArgumentException("redeem requires negative points and quantity 0");
                }
                if (orderId != null || lineId != null) {
                    throw new IllegalArgumentException("redeem is not tied to an order line");
                }
            }
        }
    }
}

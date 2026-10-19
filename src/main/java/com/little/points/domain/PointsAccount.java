package com.little.points.domain;

import java.util.Objects;

/**
 * Materialized points balance for one account. The ledger is the source of truth;
 * {@code balance} is the sum of that account's ledger entries, updated in the same transaction.
 * {@code version} is the optimistic lock, incremented on every mutating transaction.
 * Balance is allowed to be negative after a refund clawback.
 */
public record PointsAccount(String accountId, long balance, long version) {

    public PointsAccount {
        Objects.requireNonNull(accountId, "accountId");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    public PointsAccount withBalance(long newBalance) {
        return new PointsAccount(accountId, newBalance, version);
    }
}

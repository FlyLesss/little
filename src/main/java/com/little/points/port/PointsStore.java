package com.little.points.port;

import com.little.points.domain.LedgerEntry;
import com.little.points.domain.PointsAccount;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Persistence port. {@link #transact} locks the account (row lock or equivalent) for the whole unit of work.
 * A unique-key race is reported as {@code PointsException.DuplicateKey} after rollback; callers retry the work.
 * Writers must lock the account before appending ledger rows so concurrent callbacks cannot double-apply.
 */
public interface PointsStore {

    Optional<String> accountIdForOrder(String orderId);

    <T> T transact(String accountId, Function<PointsTx, T> work);

    Optional<PointsAccount> findAccount(String accountId);

    long balanceOf(String accountId);

    List<LedgerEntry> ledgerOf(String accountId);
}

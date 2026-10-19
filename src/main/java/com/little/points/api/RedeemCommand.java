package com.little.points.api;

/** Spend points. Rejected when the balance cannot cover {@code points}. {@code redeemId} is the idempotency key. */
public record RedeemCommand(String redeemId, String accountId, long points) {
}

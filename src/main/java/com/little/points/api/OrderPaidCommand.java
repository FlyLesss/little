package com.little.points.api;

import java.util.List;

/**
 * Successful payment of an order. {@code paymentEventId} is the idempotency key:
 * replaying the same event does not grant points twice.
 */
public record OrderPaidCommand(
        String paymentEventId,
        String orderId,
        String accountId,
        List<OrderLine> lines) {
}

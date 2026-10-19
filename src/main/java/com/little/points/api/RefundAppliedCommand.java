package com.little.points.api;

import java.util.List;

/**
 * A refund that has been applied to the order. {@code refundId} is the idempotency key.
 * The caller sends each line's refunded quantity; a full line refund sends the remaining quantity.
 */
public record RefundAppliedCommand(String refundId, String orderId, List<RefundLine> lines) {
}

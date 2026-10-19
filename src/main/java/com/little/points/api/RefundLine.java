package com.little.points.api;

/** Quantity refunded for one order line. Proportion uses this quantity against the issued quantity. */
public record RefundLine(String lineId, int quantity) {
}

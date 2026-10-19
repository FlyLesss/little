package com.little.points.api;

/**
 * One paid order line. {@code payAmountInCents} is the amount that earns points on this line.
 */
public record OrderLine(String lineId, String sku, int quantity, long payAmountInCents) {
}

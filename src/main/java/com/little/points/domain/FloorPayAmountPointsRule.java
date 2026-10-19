package com.little.points.domain;

/**
 * Default issuance rule.
 *
 * <p>{@code points = floor(payAmountInCents * rate / 100)}. {@link #DEFAULT_RATE} is {@code 1},
 * so every 100 cents (1 yuan) of line pay amount grants 1 point and the remainder is discarded.
 * Example: 1999 cents grants 19 points. Quantity is not an input to the grant; it is stored on
 * the issuance and used only when a refund reverses points in proportion to quantity.
 */
public record FloorPayAmountPointsRule(int rate) implements PointsIssuanceRule {

    public static final String ID = "floor-pay-amount";
    public static final String VERSION = "v1";
    public static final int DEFAULT_RATE = 1;

    public FloorPayAmountPointsRule {
        if (rate < 0) {
            throw new PointsException.InvalidRequest("rate must be >= 0");
        }
    }

    public static FloorPayAmountPointsRule defaults() {
        return new FloorPayAmountPointsRule(DEFAULT_RATE);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public long pointsFor(String sku, int quantity, long payAmountInCents) {
        if (payAmountInCents < 0) {
            throw new PointsException.InvalidRequest("payAmountInCents must be >= 0");
        }
        try {
            return Math.floorDiv(Math.multiplyExact(payAmountInCents, (long) rate), 100L);
        } catch (ArithmeticException ex) {
            throw new PointsException.InvalidRequest("points calculation overflow");
        }
    }

    @Override
    public String snapshot(String sku, int quantity, long payAmountInCents) {
        return "formula=floor(payAmountInCents*rate/100)"
                + ";rate=" + rate
                + ";payAmountInCents=" + payAmountInCents
                + ";quantity=" + quantity
                + ";sku=" + sku;
    }
}

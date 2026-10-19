package com.little.points.domain;

/**
 * Points to claw back for one refund slice of one order line.
 *
 * <p>Intermediate slices use {@code floor(originalPoints * refundQty / originalQty)}.
 * The slice that exhausts the quantity which originally earned the points takes whatever
 * points are still unreversed, so partials sum to exactly the granted points and never more.
 */
public final class LineReversal {

    private LineReversal() {
    }

    public static long pointsToReverse(
            long originalPoints,
            int originalQuantity,
            int refundQuantity,
            int alreadyReversedQuantity,
            long alreadyReversedPoints) {
        if (originalQuantity <= 0) {
            throw new IllegalArgumentException("originalQuantity must be positive");
        }
        if (originalPoints < 0 || alreadyReversedPoints < 0) {
            throw new IllegalArgumentException("points must be >= 0");
        }
        if (alreadyReversedQuantity < 0 || alreadyReversedQuantity > originalQuantity) {
            throw new IllegalStateException("reversed quantity is corrupt");
        }
        if (alreadyReversedPoints > originalPoints) {
            throw new IllegalStateException("reversed points exceed the grant");
        }
        if (refundQuantity <= 0) {
            throw new PointsException.InvalidRequest("refund quantity must be positive");
        }
        int remainingQuantity;
        try {
            int nextQuantity = Math.addExact(alreadyReversedQuantity, refundQuantity);
            if (nextQuantity > originalQuantity) {
                throw new PointsException.RefundExceedsQuantity(
                        "refund quantity " + refundQuantity
                                + " exceeds remaining " + (originalQuantity - alreadyReversedQuantity));
            }
            remainingQuantity = originalQuantity - alreadyReversedQuantity;
        } catch (ArithmeticException ex) {
            throw new PointsException.InvalidRequest("refund quantity overflow");
        }
        long remainingPoints = originalPoints - alreadyReversedPoints;
        if (refundQuantity == remainingQuantity) {
            return remainingPoints;
        }
        long proportional;
        try {
            proportional = Math.floorDiv(Math.multiplyExact(originalPoints, (long) refundQuantity), originalQuantity);
        } catch (ArithmeticException ex) {
            throw new PointsException.InvalidRequest("reversal calculation overflow");
        }
        if (proportional < 0) {
            throw new IllegalStateException("proportional reversal is negative");
        }
        return Math.min(proportional, remainingPoints);
    }
}

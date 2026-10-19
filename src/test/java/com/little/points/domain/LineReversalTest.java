package com.little.points.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineReversalTest {

    @Test
    void fullQuantityTakesEveryGrantedPoint() {
        assertEquals(10, LineReversal.pointsToReverse(10, 3, 3, 0, 0));
    }

    @Test
    void oneByOneSlicesFloorAndTheLastSliceTakesTheRemainder() {
        long first = LineReversal.pointsToReverse(10, 3, 1, 0, 0);
        long second = LineReversal.pointsToReverse(10, 3, 1, 1, first);
        long third = LineReversal.pointsToReverse(10, 3, 1, 2, first + second);

        assertEquals(3, first);
        assertEquals(3, second);
        assertEquals(4, third);
        assertEquals(10, first + second + third);
    }

    @Test
    void largerFirstSliceStillLeavesTheRemainderForTheExhaustingRefund() {
        long first = LineReversal.pointsToReverse(10, 3, 2, 0, 0);
        long second = LineReversal.pointsToReverse(10, 3, 1, 2, first);

        assertEquals(6, first);
        assertEquals(4, second);
    }

    @Test
    void intermediateSliceDoesNotAbsorbTheRemainder() {
        assertEquals(2, LineReversal.pointsToReverse(10, 4, 1, 0, 0));
    }

    @Test
    void clampsASliceThatWouldPassTheUnreversedPoints() {
        assertEquals(1, LineReversal.pointsToReverse(10, 3, 1, 1, 9));
    }

    @Test
    void rejectsQuantityBeyondTheOriginalGrant() {
        assertThrows(PointsException.RefundExceedsQuantity.class,
                () -> LineReversal.pointsToReverse(10, 3, 2, 2, 6));
        assertThrows(PointsException.InvalidRequest.class,
                () -> LineReversal.pointsToReverse(10, 3, 0, 0, 0));
    }

    @Test
    void everyPartitionSumsToTheGrantAndNeverExceedsIt() {
        for (int points = 0; points <= 40; points++) {
            for (int quantity = 1; quantity <= 10; quantity++) {
                for (int sliceSize = 1; sliceSize <= quantity; sliceSize++) {
                    int reversedQuantity = 0;
                    long reversedPoints = 0;
                    while (reversedQuantity < quantity) {
                        int refundQuantity = Math.min(sliceSize, quantity - reversedQuantity);
                        long slice = LineReversal.pointsToReverse(
                                points, quantity, refundQuantity, reversedQuantity, reversedPoints);
                        assertTrue(slice >= 0, "negative slice");
                        reversedQuantity += refundQuantity;
                        reversedPoints += slice;
                        assertTrue(reversedPoints <= points, "reversal exceeded the grant");
                    }
                    assertEquals(points, reversedPoints);
                }
            }
        }
    }
}

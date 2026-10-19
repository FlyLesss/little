package com.little.points.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloorPayAmountPointsRuleTest {

    @Test
    void defaultRateGrantsOnePointPerWholeYuan() {
        FloorPayAmountPointsRule rule = FloorPayAmountPointsRule.defaults();

        assertEquals(1, rule.rate());
        assertEquals("floor-pay-amount", rule.id());
        assertEquals("v1", rule.version());
        assertEquals(19, rule.pointsFor("sku", 1, 1999));
        assertEquals(0, rule.pointsFor("sku", 3, 99));
        assertEquals(1, rule.pointsFor("sku", 3, 100));
        assertEquals(0, rule.pointsFor("sku", 1, 0));
        assertEquals(rule.pointsFor("sku", 1, 1500), rule.pointsFor("sku", 9, 1500));
    }

    @Test
    void customRateUsesFloorOfPayAmountTimesRateOverOneHundred() {
        FloorPayAmountPointsRule rule = new FloorPayAmountPointsRule(15);

        assertEquals(15, rule.pointsFor("sku", 1, 100));
        assertEquals(0, new FloorPayAmountPointsRule(0).pointsFor("sku", 1, 50_000));
    }

    @Test
    void snapshotRecordsTheInputsUsedAtPurchase() {
        String snapshot = FloorPayAmountPointsRule.defaults().snapshot("mug", 3, 1000);

        assertTrue(snapshot.contains("formula=floor(payAmountInCents*rate/100)"));
        assertTrue(snapshot.contains("rate=1"));
        assertTrue(snapshot.contains("payAmountInCents=1000"));
        assertTrue(snapshot.contains("quantity=3"));
        assertTrue(snapshot.contains("sku=mug"));
    }

    @Test
    void rejectsNegativeRatePayAmountAndOverflow() {
        assertThrows(PointsException.InvalidRequest.class, () -> new FloorPayAmountPointsRule(-1));
        FloorPayAmountPointsRule rule = new FloorPayAmountPointsRule(2);
        assertThrows(PointsException.InvalidRequest.class, () -> rule.pointsFor("sku", 1, -1));
        assertThrows(PointsException.InvalidRequest.class, () -> rule.pointsFor("sku", 1, Long.MAX_VALUE));
    }
}

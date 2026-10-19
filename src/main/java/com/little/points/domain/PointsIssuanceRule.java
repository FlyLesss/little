package com.little.points.domain;

/**
 * Rule used only when a purchase is paid. The identity, version, and {@link #snapshot}
 * are stored on the issuance record. Refund clawback must not call this interface again.
 */
public interface PointsIssuanceRule {

    String id();

    String version();

    /**
     * Non-negative integer points for one paid line.
     */
    long pointsFor(String sku, int quantity, long payAmountInCents);

    /**
     * Inputs used for this grant, persisted so later rule changes cannot rewrite history.
     * At most 512 characters.
     */
    String snapshot(String sku, int quantity, long payAmountInCents);
}

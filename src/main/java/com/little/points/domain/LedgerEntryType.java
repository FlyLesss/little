package com.little.points.domain;

public enum LedgerEntryType {
    /** Points granted for a paid order line. Signed points are {@code >= 0}. */
    GRANT,
    /** Clawback of a prior grant. Signed points are {@code <= 0}. */
    REVERSAL,
    /** Points spent by the member. Signed points are negative. */
    REDEEM
}

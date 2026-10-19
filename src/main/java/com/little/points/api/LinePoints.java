package com.little.points.api;

/** Points granted or reversed for a single order line. {@code points} is a non-negative magnitude. */
public record LinePoints(String lineId, int quantity, long points) {
}

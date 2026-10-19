package com.little.points.domain;

/**
 * Base type for points-domain failures. Callers should branch on the nested types.
 */
public class PointsException extends RuntimeException {

    public PointsException(String message) {
        super(message);
    }

    public PointsException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The request itself is malformed. */
    public static final class InvalidRequest extends PointsException {
        public InvalidRequest(String message) {
            super(message);
        }
    }

    /** The same idempotency key was reused with a different body. */
    public static final class IdempotencyConflict extends PointsException {
        public IdempotencyConflict(String message) {
            super(message);
        }
    }

    /** Unique-key race. The service retries once; a second failure is returned to the caller. */
    public static final class DuplicateKey extends PointsException {
        public DuplicateKey(String message) {
            super(message);
        }

        public DuplicateKey(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Optimistic version did not match. */
    public static final class Conflict extends PointsException {
        public Conflict(String message) {
            super(message);
        }
    }

    public static final class OrderNotFound extends PointsException {
        public OrderNotFound(String message) {
            super(message);
        }
    }

    public static final class LineNotFound extends PointsException {
        public LineNotFound(String message) {
            super(message);
        }
    }

    public static final class RefundExceedsQuantity extends PointsException {
        public RefundExceedsQuantity(String message) {
            super(message);
        }
    }

    /** Spend rejected. Refund clawback does not use this exception and may drive the balance negative. */
    public static final class InsufficientPoints extends PointsException {
        public InsufficientPoints(String message) {
            super(message);
        }
    }

    public static final class Persistence extends PointsException {
        public Persistence(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

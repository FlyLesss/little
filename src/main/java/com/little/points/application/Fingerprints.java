package com.little.points.application;

import com.little.points.api.OrderLine;
import com.little.points.api.RefundLine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class Fingerprints {

    private Fingerprints() {
    }

    static String orderPaid(String accountId, String orderId, List<OrderLine> lines) {
        StringBuilder sb = new StringBuilder();
        field(sb, "paid");
        field(sb, accountId);
        field(sb, orderId);
        lines.stream().sorted(Comparator.comparing(OrderLine::lineId)).forEach(line -> {
            field(sb, line.lineId());
            field(sb, line.sku());
            field(sb, line.quantity());
            field(sb, line.payAmountInCents());
        });
        return sha256(sb.toString());
    }

    static String refund(String orderId, List<RefundLine> lines) {
        StringBuilder sb = new StringBuilder();
        field(sb, "refund");
        field(sb, orderId);
        lines.stream().sorted(Comparator.comparing(RefundLine::lineId)).forEach(line -> {
            field(sb, line.lineId());
            field(sb, line.quantity());
        });
        return sha256(sb.toString());
    }

    static String redeem(String accountId, long points) {
        StringBuilder sb = new StringBuilder();
        field(sb, "redeem");
        field(sb, accountId);
        field(sb, points);
        return sha256(sb.toString());
    }

    private static void field(StringBuilder sb, String value) {
        sb.append(value.length()).append(':').append(value).append(';');
    }

    private static void field(StringBuilder sb, long value) {
        sb.append(value).append(';');
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

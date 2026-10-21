package com.chengyinhub.business.domain.map;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 不签名的页游标。没有新密钥。篡改 query、玩家或水位会在比对时被拒绝。
 * 编码是 11 行 UTF-8：版本、指纹的 7 行、玩家、水位、位置。
 * 位置按可读来源的请求顺序，形如 {@code roam_poi=4} 或 {@code store=*}。
 */
public final class MapPublicCursor {

    private static final String VERSION = "v1";

    private MapPublicCursor() {
    }

    public static String encode(MapPublicQuery.Normalized query, long viewerId, List<Pos> positions) {
        StringBuilder body = new StringBuilder();
        body.append(VERSION).append('\n').append(query.fingerprint()).append('\n')
                .append(viewerId).append('\n').append(MapPublicLimits.WATERMARK).append('\n');
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(positions.get(i).text());
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Parsed parse(String token) {
        if (token == null || token.isEmpty()) {
            throw new MapPublicException("CURSOR_REJECTED");
        }
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new MapPublicException("CURSOR_REJECTED");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length != 11 || !VERSION.equals(lines[0]) || !MapPublicLimits.WATERMARK.equals(lines[9])) {
            throw new MapPublicException("CURSOR_REJECTED");
        }
        long viewerId;
        try {
            viewerId = Long.parseLong(lines[8]);
        } catch (NumberFormatException ex) {
            throw new MapPublicException("CURSOR_REJECTED");
        }
        if (viewerId <= 0) {
            throw new MapPublicException("CURSOR_REJECTED");
        }
        String fingerprint = lines[1] + "\n" + lines[2] + "\n" + lines[3] + "\n" + lines[4] + "\n"
                + lines[5] + "\n" + lines[6] + "\n" + lines[7];
        return new Parsed(fingerprint, viewerId, lines[9], positions(lines[10]));
    }

    private static List<Pos> positions(String line) {
        if (line.isEmpty()) {
            return List.of();
        }
        String[] parts = line.split(",", -1);
        List<Pos> positions = new ArrayList<>();
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq != part.lastIndexOf('=') || !part.substring(0, eq).matches("[a-z][a-z0-9_]{0,31}")) {
                throw new MapPublicException("CURSOR_REJECTED");
            }
            String source = part.substring(0, eq);
            String value = part.substring(eq + 1);
            for (Pos existing : positions) {
                if (existing.sourceType.equals(source)) {
                    throw new MapPublicException("CURSOR_REJECTED");
                }
            }
            if ("*".equals(value)) {
                positions.add(Pos.exhausted(source));
                continue;
            }
            long id;
            try {
                id = Long.parseLong(value);
            } catch (NumberFormatException ex) {
                throw new MapPublicException("CURSOR_REJECTED");
            }
            if (id <= 0) {
                throw new MapPublicException("CURSOR_REJECTED");
            }
            positions.add(Pos.after(source, id));
        }
        return positions;
    }

    public static final class Pos {
        private final String sourceType;
        private final boolean exhausted;
        private final Long afterId;

        private Pos(String sourceType, boolean exhausted, Long afterId) {
            this.sourceType = sourceType;
            this.exhausted = exhausted;
            this.afterId = afterId;
        }

        public static Pos exhausted(String sourceType) {
            return new Pos(sourceType, true, null);
        }

        public static Pos after(String sourceType, long afterId) {
            return new Pos(sourceType, false, afterId);
        }

        public String sourceType() { return sourceType; }
        public boolean exhausted() { return exhausted; }
        public Long afterId() { return afterId; }

        private String text() {
            return sourceType + "=" + (exhausted ? "*" : afterId);
        }
    }

    public static final class Parsed {
        private final String fingerprint;
        private final long viewerId;
        private final String watermark;
        private final List<Pos> positions;

        Parsed(String fingerprint, long viewerId, String watermark, List<Pos> positions) {
            this.fingerprint = fingerprint;
            this.viewerId = viewerId;
            this.watermark = watermark;
            this.positions = Collections.unmodifiableList(positions);
        }

        public String fingerprint() { return fingerprint; }
        public long viewerId() { return viewerId; }
        public String watermark() { return watermark; }
        public List<Pos> positions() { return positions; }

        public Pos position(String sourceType) {
            for (Pos pos : positions) {
                if (pos.sourceType.equals(sourceType)) {
                    return pos;
                }
            }
            return null;
        }
    }
}

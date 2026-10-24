package com.chengyinhub.business.service.npc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * C06 exclusive local NPC knowledge DRAFT. AI may only populate this form;
 * it is not a publish payload, not a live commerce mutation, and not an
 * original review receipt. Missing {@code requestId} stays Java {@code null},
 * never {@code ""}.
 *
 * <p>Display catalog prices are facts only. Applying them as live price /
 * stock / award / presence is always a typed refusal.
 */
public final class MerchantNpcKnowledgeDraft {

    public static final int SCHEMA_VERSION = 1;
    public static final int FACTS_BYTE_LIMIT = 16_000;

    public enum Origin {
        HUMAN,
        AI
    }

    public enum ForbiddenAiCommerce {
        PRICE,
        DEDUCT_STOCK,
        AWARD,
        CERTIFY_PRESENCE
    }

    public enum IssueSection {
        DRAFT, PRODUCTS, HOURS, PROMOTIONS, FAQ, NEVER_SAY
    }

    public enum IssueKind {
        COUNT, TEXT, PRICE, CURRENCY, BYTE_LIMIT, ENCODING,
        LIVE_PRICE, LIVE_STOCK, LIVE_AWARD, LIVE_PRESENCE
    }

    public record Issue(IssueSection section, Integer row, String field, IssueKind kind) {
        public Issue {
            section = section == null ? IssueSection.DRAFT : section;
            kind = kind == null ? IssueKind.ENCODING : kind;
        }
    }

    public record Product(String name, String priceMinor, String currency) {
        public Product {
            // Keep null as null. Do not coerce missing fields to "".
        }
    }

    public record Hours(int day, String opens, String closes, String timeZone) {
        public Hours {
        }
    }

    public record Promotion(String title, String startsAt, String endsAt) {
        public Promotion {
        }
    }

    public record Faq(String question, String answer) {
        public Faq {
        }
    }

    private final String requestId;
    private final Origin origin;
    private final List<Product> products;
    private final List<Hours> hours;
    private final List<Promotion> promotions;
    private final List<Faq> faq;
    private final List<String> neverSay;

    public MerchantNpcKnowledgeDraft(String requestId, Origin origin,
                                     List<Product> products, List<Hours> hours,
                                     List<Promotion> promotions, List<Faq> faq,
                                     List<String> neverSay) {
        this.requestId = blankToNull(requestId);
        this.origin = origin == null ? Origin.AI : origin;
        this.products = copy(products);
        this.hours = copy(hours);
        this.promotions = copy(promotions);
        this.faq = copy(faq);
        this.neverSay = copyStrings(neverSay);
    }

    public static MerchantNpcKnowledgeDraft aiDraft(String requestId, List<Product> products) {
        return new MerchantNpcKnowledgeDraft(requestId, Origin.AI, products, List.of(),
                List.of(), List.of(), List.of());
    }

    public String requestId() {
        return requestId;
    }

    public Origin origin() {
        return origin;
    }

    public List<Product> products() {
        return products;
    }

    public List<Hours> hours() {
        return hours;
    }

    public List<Promotion> promotions() {
        return promotions;
    }

    public List<Faq> faq() {
        return faq;
    }

    public List<String> neverSay() {
        return neverSay;
    }

    public boolean isEmpty() {
        return products.isEmpty() && hours.isEmpty() && promotions.isEmpty()
                && faq.isEmpty() && neverSay.isEmpty();
    }

    /** Display-only catalog facts. This draft never prices SKUs, deducts stock, awards, or certifies presence. */
    public boolean encodesLiveCommerceMutation() {
        return false;
    }

    public List<Issue> applyingAiCommerce(ForbiddenAiCommerce mutation) {
        return liveCommerceIssues(mutation);
    }

    public static List<Issue> liveCommerceIssues(ForbiddenAiCommerce mutation) {
        Objects.requireNonNull(mutation, "mutation");
        IssueKind kind;
        switch (mutation) {
            case PRICE -> kind = IssueKind.LIVE_PRICE;
            case DEDUCT_STOCK -> kind = IssueKind.LIVE_STOCK;
            case AWARD -> kind = IssueKind.LIVE_AWARD;
            case CERTIFY_PRESENCE -> kind = IssueKind.LIVE_PRESENCE;
            default -> kind = IssueKind.LIVE_PRICE;
        }
        return List.of(new Issue(IssueSection.PRODUCTS, null, null, kind));
    }

    public List<Issue> validationIssues() {
        List<Issue> issues = new ArrayList<Issue>();
        if (products.size() > 40) {
            issues.add(new Issue(IssueSection.PRODUCTS, null, null, IssueKind.COUNT));
        }
        for (int i = 0; i < products.size(); i++) {
            Product product = products.get(i);
            if (!validText(product.name(), 120)) {
                issues.add(new Issue(IssueSection.PRODUCTS, i, "name", IssueKind.TEXT));
            }
            if (!validPriceMinor(product.priceMinor())) {
                issues.add(new Issue(IssueSection.PRODUCTS, i, "priceMinor", IssueKind.PRICE));
            }
            if (!validCurrency(product.currency())) {
                issues.add(new Issue(IssueSection.PRODUCTS, i, "currency", IssueKind.CURRENCY));
            }
        }
        byte[] encoded;
        try {
            encoded = canonicalFactsJson();
        } catch (RuntimeException ex) {
            issues.add(new Issue(IssueSection.DRAFT, null, null, IssueKind.ENCODING));
            return List.copyOf(issues);
        }
        if (encoded.length > FACTS_BYTE_LIMIT) {
            issues.add(new Issue(IssueSection.DRAFT, null, null, IssueKind.BYTE_LIMIT));
        }
        return List.copyOf(issues);
    }

    public byte[] validatedPublicFactsJson() {
        List<Issue> issues = validationIssues();
        if (!issues.isEmpty()) {
            throw new IllegalStateException("invalid NPC knowledge draft");
        }
        return canonicalFactsJson();
    }

    public String factsHash() {
        return sha256Hex(validatedPublicFactsJson());
    }

    public byte[] canonicalFactsJson() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"schemaVersion\":").append(SCHEMA_VERSION);
        json.append(",\"products\":[");
        for (int i = 0; i < products.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Product product = products.get(i);
            json.append("{\"currency\":").append(jsonString(product.currency()));
            json.append(",\"name\":").append(jsonString(product.name()));
            json.append(",\"priceMinor\":").append(parsePriceMinor(product.priceMinor()));
            json.append('}');
        }
        json.append("],\"hours\":[");
        for (int i = 0; i < hours.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Hours row = hours.get(i);
            json.append("{\"closes\":").append(jsonString(row.closes()));
            json.append(",\"day\":").append(row.day());
            json.append(",\"opens\":").append(jsonString(row.opens()));
            json.append(",\"timeZone\":").append(jsonString(row.timeZone()));
            json.append('}');
        }
        json.append("],\"promotions\":[");
        for (int i = 0; i < promotions.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Promotion row = promotions.get(i);
            json.append("{\"endsAt\":").append(jsonString(row.endsAt()));
            json.append(",\"startsAt\":").append(jsonString(row.startsAt()));
            json.append(",\"title\":").append(jsonString(row.title()));
            json.append('}');
        }
        json.append("],\"faq\":[");
        for (int i = 0; i < faq.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Faq row = faq.get(i);
            json.append("{\"answer\":").append(jsonString(row.answer()));
            json.append(",\"question\":").append(jsonString(row.question()));
            json.append('}');
        }
        json.append("],\"neverSay\":[");
        for (int i = 0; i < neverSay.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(jsonString(neverSay.get(i)));
        }
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static boolean validText(String value, int max) {
        if (value == null) {
            return false;
        }
        int units = value.length();
        if (units > max) {
            return false;
        }
        boolean visible = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 32 || (ch >= 127 && ch <= 159)) {
                return false;
            }
            if (ch > 32) {
                visible = true;
            }
        }
        return visible;
    }

    private static boolean validPriceMinor(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        long parsed = 0L;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
            parsed = parsed * 10L + (ch - '0');
            if (parsed > 100_000_000L) {
                return false;
            }
        }
        return true;
    }

    private static long parsePriceMinor(String value) {
        if (!validPriceMinor(value)) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private static boolean validCurrency(String value) {
        if (value == null || value.length() != 3) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            char ch = value.charAt(i);
            if (ch < 'A' || ch > 'Z') {
                return false;
            }
        }
        return true;
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 32) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static <T> List<T> copy(List<T> source) {
        return source == null || source.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<T>(source));
    }

    private static List<String> copyStrings(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<String>(source.size());
        for (String value : source) {
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}

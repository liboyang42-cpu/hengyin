package com.chengyinhub.business.service.support.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.chengyinhub.business.domain.BusinessAuditEvent;

/**
 * C11 支持端按已授权的 typed 资源翻原 request 的事件页。
 *
 * <p>授权只认 domainType + domainId + VIEW_AUDIT。不接受 platformAdmin，
 * 也不把员工 memberId 当成 owner。全局 selectByRequestId 不是这一页。
 * 原 selectAuditLogPage 的 requestId 等值条件在未应用的共享 hunk 里，
 * 本类不改 Mapper，也不调用还没有该条件的查询。
 */
public final class AuthorizedTypedResourceEventPage {

    public static final String VIEW_AUDIT = "VIEW_AUDIT";
    public static final int MAX_LIMIT = 100;
    public static final int MAX_REQUEST_ID_LENGTH = 64;

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
    private static final Pattern CARD = Pattern.compile("(?<![0-9])[0-9]{15,19}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final PageSource source;

    public AuthorizedTypedResourceEventPage(PageSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
        this.source = source;
    }

    /**
     * 读一页。projectionOutcome 只作对照，不得把缺行或 UNKNOWN 改成成功。
     */
    public Page read(Grant grant, String requestId, String targetType, Long targetId,
                     Long cursor, int limit, String projectionOutcome) {
        Grant live = requireGrant(grant);
        String id = requireRequestId(requestId);
        String target = requireTarget(targetType, targetId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit");
        }
        if (cursor != null && cursor <= 0L) {
            throw new IllegalArgumentException("cursor");
        }
        PageRequest request = new PageRequest(live.domainType, live.domainId, id, target, targetId,
                cursor, limit + 1);
        List<BusinessAuditEvent> fetched = source.selectAuditLogPage(request);
        if (fetched == null) {
            fetched = Collections.emptyList();
        }
        List<BusinessAuditEvent> matched = new ArrayList<BusinessAuditEvent>();
        for (BusinessAuditEvent row : fetched) {
            if (matches(live, id, target, targetId, row)) {
                matched.add(redact(row));
            }
            if (matched.size() > limit) {
                break;
            }
        }
        boolean hasMore = matched.size() > limit;
        List<BusinessAuditEvent> items = hasMore ? matched.subList(0, limit) : matched;
        Long nextCursor = hasMore ? items.get(items.size() - 1).getId() : null;
        return new Page(id, live.domainType, live.domainId, items, nextCursor, outcome(items),
                projectionOutcome);
    }

    /** 十进制 Long。拒绝 true/false、小数，以及会经 Double 截断的写法。 */
    public static long exactLong(String text) {
        if (text == null) {
            throw new IllegalArgumentException("id");
        }
        String value = text.trim();
        if (value.isEmpty() || value.length() > 19 || value.charAt(0) == '+' || value.charAt(0) == '-') {
            throw new IllegalArgumentException("id");
        }
        if (value.length() > 1 && value.charAt(0) == '0') {
            throw new IllegalArgumentException("id");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("id");
            }
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
                throw new IllegalArgumentException("id");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("id");
        }
    }

    private static Grant requireGrant(Grant grant) {
        if (grant == null || grant.revoked || !VIEW_AUDIT.equals(grant.scope)
                || grant.domainType == null || grant.domainType.trim().isEmpty() || grant.domainId <= 0L) {
            throw new TypedResourceAuthorizationException();
        }
        return grant;
    }

    static String requireRequestId(String requestId) {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId");
        }
        String id = requestId.trim();
        if (!id.equals(requestId) || id.isEmpty() || id.length() > MAX_REQUEST_ID_LENGTH
                || !REQUEST_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("requestId");
        }
        return id;
    }

    private static String requireTarget(String targetType, Long targetId) {
        boolean typeBlank = targetType == null || targetType.trim().isEmpty();
        if (targetId == null && typeBlank) {
            return null;
        }
        if (targetId == null || targetId <= 0L || typeBlank) {
            throw new IllegalArgumentException("target");
        }
        return targetType.trim();
    }

    private static boolean matches(Grant grant, String requestId, String targetType, Long targetId,
                                   BusinessAuditEvent row) {
        if (row == null || row.getId() == null || row.getId() <= 0L) {
            return false;
        }
        if (!grant.domainType.equals(row.getDomainType()) || row.getDomainId() == null
                || grant.domainId != row.getDomainId() || !requestId.equals(row.getRequestId())) {
            return false;
        }
        if (targetType == null) {
            return true;
        }
        return targetType.equals(row.getTargetType()) && targetId.equals(row.getTargetId());
    }

    private static String outcome(List<BusinessAuditEvent> items) {
        if (items.isEmpty()) {
            return "UNKNOWN";
        }
        String first = items.get(0).getAfterJson();
        if (first == null || first.trim().isEmpty() || "UNKNOWN".equals(first)) {
            return "UNKNOWN";
        }
        for (int i = 1; i < items.size(); i++) {
            if (!first.equals(items.get(i).getAfterJson())) {
                return "UNKNOWN";
            }
        }
        return first;
    }

    private static BusinessAuditEvent redact(BusinessAuditEvent source) {
        BusinessAuditEvent safe = new BusinessAuditEvent();
        safe.setId(source.getId());
        safe.setDomainType(source.getDomainType());
        safe.setDomainId(source.getDomainId());
        safe.setActorType(source.getActorType());
        safe.setActorMemberId(source.getActorMemberId());
        safe.setAction(source.getAction());
        safe.setTargetType(source.getTargetType());
        safe.setTargetId(source.getTargetId());
        safe.setRelationId(source.getRelationId());
        safe.setBeforeJson(redactText(source.getBeforeJson()));
        safe.setAfterJson(redactText(source.getAfterJson()));
        safe.setReason(redactText(source.getReason()));
        safe.setRequestId(source.getRequestId());
        safe.setCreateTime(source.getCreateTime());
        return safe;
    }

    static String redactText(String value) {
        if (value == null) {
            return null;
        }
        String redacted = PHONE.matcher(value).replaceAll("***");
        redacted = CARD.matcher(redacted).replaceAll("***");
        redacted = EMAIL.matcher(redacted).replaceAll("***");
        return redactSensitiveKeys(redacted);
    }

    private static String redactSensitiveKeys(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int key = value.indexOf('"', i);
            if (key < 0) {
                out.append(value, i, value.length());
                break;
            }
            int keyEnd = value.indexOf('"', key + 1);
            if (keyEnd < 0) {
                out.append(value, i, value.length());
                break;
            }
            out.append(value, i, keyEnd + 1);
            String name = value.substring(key + 1, keyEnd);
            int colon = keyEnd + 1;
            while (colon < value.length() && Character.isWhitespace(value.charAt(colon))) {
                colon++;
            }
            if (colon >= value.length() || value.charAt(colon) != ':' || !isSensitive(name)) {
                i = keyEnd + 1;
                continue;
            }
            int valueStart = colon + 1;
            while (valueStart < value.length() && Character.isWhitespace(value.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart >= value.length() || value.charAt(valueStart) != '"') {
                i = keyEnd + 1;
                continue;
            }
            int valueEnd = value.indexOf('"', valueStart + 1);
            if (valueEnd < 0) {
                i = keyEnd + 1;
                continue;
            }
            out.append(':');
            out.append("\"***\"");
            i = valueEnd + 1;
        }
        return out.toString();
    }

    private static boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("token") || normalized.contains("password")
                || normalized.contains("phone") || normalized.contains("mobile")
                || normalized.contains("openid") || normalized.contains("secret")
                || normalized.contains("credential") || normalized.contains("authorization")
                || normalized.contains("unionid") || normalized.contains("idcard")
                || normalized.contains("identity") || normalized.contains("realname")
                || normalized.contains("bank") || normalized.contains("cardnumber") || normalized.contains("cardno")
                || normalized.contains("address") || normalized.contains("email")
                || normalized.contains("latitude") || normalized.contains("longitude")
                || normalized.contains("location") || normalized.contains("nickname")
                || "name".equals(normalized) || "lat".equals(normalized) || "lng".equals(normalized);
    }

    /** 原 selectAuditLogPage 的调用形状。实现必须带 requestId 等值，禁止改成全局 request 查询。 */
    public interface PageSource {
        List<BusinessAuditEvent> selectAuditLogPage(PageRequest request);
    }

    public static final class Grant {
        private final String domainType;
        private final long domainId;
        private final String scope;
        private final boolean revoked;

        private Grant(String domainType, long domainId, String scope, boolean revoked) {
            this.domainType = domainType;
            this.domainId = domainId;
            this.scope = scope;
            this.revoked = revoked;
        }

        public static Grant of(String domainType, String domainIdText, String scope) {
            if (domainType == null || domainType.trim().isEmpty() || !VIEW_AUDIT.equals(scope)) {
                throw new TypedResourceAuthorizationException();
            }
            return new Grant(domainType.trim(), exactLong(domainIdText), scope, false);
        }

        public Grant revoke() {
            return new Grant(domainType, domainId, scope, true);
        }

        public String getDomainType() {
            return domainType;
        }

        public long getDomainId() {
            return domainId;
        }
    }

    public static final class PageRequest {
        private final String domainType;
        private final long domainId;
        private final String requestId;
        private final String targetType;
        private final Long targetId;
        private final Long cursor;
        private final int fetchLimit;

        PageRequest(String domainType, long domainId, String requestId, String targetType, Long targetId,
                    Long cursor, int fetchLimit) {
            this.domainType = domainType;
            this.domainId = domainId;
            this.requestId = requestId;
            this.targetType = targetType;
            this.targetId = targetId;
            this.cursor = cursor;
            this.fetchLimit = fetchLimit;
        }

        public String getDomainType() {
            return domainType;
        }

        public long getDomainId() {
            return domainId;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getTargetType() {
            return targetType;
        }

        public Long getTargetId() {
            return targetId;
        }

        public Long getCursor() {
            return cursor;
        }

        public int getFetchLimit() {
            return fetchLimit;
        }
    }

    public static final class Page {
        private final String requestId;
        private final String domainType;
        private final long domainId;
        private final List<BusinessAuditEvent> items;
        private final Long nextCursor;
        private final String outcome;
        private final String ignoredProjection;

        Page(String requestId, String domainType, long domainId, List<BusinessAuditEvent> items,
             Long nextCursor, String outcome, String ignoredProjection) {
            this.requestId = requestId;
            this.domainType = domainType;
            this.domainId = domainId;
            this.items = Collections.unmodifiableList(new ArrayList<BusinessAuditEvent>(items));
            this.nextCursor = nextCursor;
            this.outcome = outcome;
            this.ignoredProjection = ignoredProjection;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getDomainType() {
            return domainType;
        }

        public long getDomainId() {
            return domainId;
        }

        public List<BusinessAuditEvent> getItems() {
            return items;
        }

        public Long getNextCursor() {
            return nextCursor;
        }

        public boolean isConfirmed() {
            return !items.isEmpty();
        }

        public String getOutcome() {
            return outcome;
        }

        public boolean usedProjection() {
            return false;
        }

        public String getIgnoredProjection() {
            return ignoredProjection;
        }
    }

    public static final class TypedResourceAuthorizationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public TypedResourceAuthorizationException() {
            super("TYPED_RESOURCE_DENIED");
        }
    }
}

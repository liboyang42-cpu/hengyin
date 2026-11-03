package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.constant.ProductType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * 普通城市定向已发布规则选择器。只认 {@code publishedConfigVersion} 冻结快照，
 * 不把作者草稿 {@code configVersion} 或成长榜规则当成开局规则。
 */
public final class PublishedRuleSelector {

    public FrozenPublishedRule select(CmsTopic course) {
        if (course == null || course.getId() == null || course.getId() <= 0) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "主题参数有误");
        }
        if (ProductType.ORIENTEERING != (course.getProductType() == null ? -1 : course.getProductType())) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_ORDINARY_CITY, "该主题不是普通城市定向");
        }
        if (!Integer.valueOf(0).equals(course.getDelFlag())) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_PUBLISHED, "主题已删除");
        }
        if (!Integer.valueOf(1).equals(course.getStatus()) || !Integer.valueOf(1).equals(course.getUserStatus())) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_PUBLISHED, "主题尚未以已发布规则上架");
        }
        Long publishedVersion = course.getPublishedConfigVersion();
        if (publishedVersion == null || publishedVersion <= 0L) {
            throw new OrienteeringEventException(OrienteeringEventException.RULE_NOT_FROZEN, "没有已发布的规则版本可冻结");
        }
        String routeMode = course.getRouteMode() == null || course.getRouteMode().trim().isEmpty()
                ? "LINEAR" : course.getRouteMode().trim().toUpperCase(Locale.ROOT);
        String snapshot = course.getPublishedRouteGraphJson();
        if (snapshot == null || snapshot.trim().isEmpty()) {
            snapshot = "{\"routeMode\":\"" + routeMode + "\"}";
        }
        String completeRule = course.getCompleteRuleJson() == null ? "" : course.getCompleteRuleJson();
        String digest = sha256(publishedVersion + "\0" + snapshot + "\0" + completeRule + "\0" + routeMode);
        return new FrozenPublishedRule(course.getId(), publishedVersion, snapshot, completeRule, routeMode, digest);
    }

    /** 开局只能确认选择器给出的冻结版本，不能拿后来升高的草稿 configVersion 顶替。 */
    public void requireAck(FrozenPublishedRule frozen, Long ackPublishedConfigVersion) {
        if (frozen == null) {
            throw new OrienteeringEventException(OrienteeringEventException.RULE_NOT_FROZEN, "没有已冻结的发布规则");
        }
        if (ackPublishedConfigVersion == null || !ackPublishedConfigVersion.equals(frozen.publishedConfigVersion)) {
            throw new OrienteeringEventException(OrienteeringEventException.RULE_VERSION_MISMATCH,
                    "请先确认本局已发布规则版本");
        }
    }

    private static String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static final class FrozenPublishedRule {
        public final long topicId;
        public final long publishedConfigVersion;
        public final String snapshotJson;
        public final String completeRuleJson;
        public final String routeMode;
        public final String digest;

        FrozenPublishedRule(long topicId, long publishedConfigVersion, String snapshotJson,
                            String completeRuleJson, String routeMode, String digest) {
            this.topicId = topicId;
            this.publishedConfigVersion = publishedConfigVersion;
            this.snapshotJson = snapshotJson;
            this.completeRuleJson = completeRuleJson;
            this.routeMode = routeMode;
            this.digest = digest;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FrozenPublishedRule)) return false;
            FrozenPublishedRule that = (FrozenPublishedRule) o;
            return topicId == that.topicId
                    && publishedConfigVersion == that.publishedConfigVersion
                    && Objects.equals(digest, that.digest);
        }

        @Override
        public int hashCode() {
            return Objects.hash(topicId, publishedConfigVersion, digest);
        }
    }
}

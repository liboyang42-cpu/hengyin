package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.OrientSafetyRule;
import com.chengyinhub.business.service.orienteering.PublishedRuleSelector.FrozenPublishedRule;
import com.chengyinhub.business.service.support.OrientSafetyPolicy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 普通城市定向的城市时区资格与逐项安全确认。权威时区是城市时区（默认北京时间），
 * 客户端时区不能放行或改写场次窗口。
 */
public final class OrdinaryCityTimezoneQualifier {

    public static final ZoneId CITY_ZONE = OrientSafetyPolicy.ZONE;

    public static final String ITEM_PUBLISHED_RULES = "PUBLISHED_RULES";
    public static final String ITEM_CITY_TIMEZONE = "CITY_TIMEZONE";
    public static final String ITEM_DAY_NIGHT = "DAY_NIGHT";
    public static final String ITEM_WEATHER = "WEATHER";
    public static final String ITEM_AGE_GUARDIAN = "AGE_GUARDIAN";
    public static final String ITEM_INSURANCE_OPTIONAL = "INSURANCE_OPTIONAL";

    /** 普通城市定向只认城市时区；声称的客户端时区即使不同也不能成为权威。 */
    public ZoneId qualify(CmsTopic course, Club hostClub, ZoneId claimedClientZone) {
        if (course == null || course.getId() == null || course.getId() <= 0) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "主题参数有误");
        }
        if (hostClub != null && hostClub.getCity() != null && !hostClub.getCity().trim().isEmpty()
                && claimedClientZone != null && !CITY_ZONE.equals(claimedClientZone)) {
            throw new OrienteeringEventException(OrienteeringEventException.TIMEZONE_NOT_CITY,
                    "场次时间按城市时区计算，不能改用客户端时区");
        }
        if (claimedClientZone != null && !CITY_ZONE.equals(claimedClientZone)) {
            throw new OrienteeringEventException(OrienteeringEventException.TIMEZONE_NOT_CITY,
                    "普通城市定向按 Asia/Shanghai 计算，客户端时区不是权威");
        }
        return CITY_ZONE;
    }

    /**
     * 逐项安全确认：每一项必须显式点过，不允许一条「全部同意」冒充。
     * 安全闸有阻断时不能确认通过。
     */
    public SafetyConfirmation confirm(FrozenPublishedRule frozen, ZoneId cityZone,
                                      Collection<String> confirmedItemCodes, OrientSafetyRule safetyRule,
                                      Date sessionStart, Date sessionEnd, Date now, Date birthday,
                                      boolean guardianVerified) {
        if (frozen == null) {
            throw new OrienteeringEventException(OrienteeringEventException.RULE_NOT_FROZEN, "没有已冻结的发布规则");
        }
        if (cityZone == null || !CITY_ZONE.equals(cityZone)) {
            throw new OrienteeringEventException(OrienteeringEventException.TIMEZONE_NOT_CITY, "城市时区未确认");
        }
        OrientSafetyPolicy.Decision decision = OrientSafetyPolicy.evaluate(
                safetyRule, sessionStart, sessionEnd, now == null ? sessionStart : now, birthday, guardianVerified);
        if (!decision.allowed()) {
            String code = decision.blockers.isEmpty() ? OrienteeringEventException.SAFETY_BLOCKED
                    : decision.blockers.get(0).get("code");
            String message = decision.blockers.isEmpty() ? "安全闸未放行" : decision.blockers.get(0).get("message");
            throw new OrienteeringEventException(code == null ? OrienteeringEventException.SAFETY_BLOCKED : code, message);
        }
        List<String> required = requiredItems(decision);
        Set<String> confirmed = new LinkedHashSet<String>();
        if (confirmedItemCodes != null) {
            for (String code : confirmedItemCodes) {
                if (code == null) continue;
                String item = code.trim();
                if (item.isEmpty() || "ALL".equalsIgnoreCase(item) || "AGREE".equalsIgnoreCase(item)) {
                    throw new OrienteeringEventException(OrienteeringEventException.SAFETY_UNCONFIRMED,
                            "安全确认必须逐项勾选，不能一条全部同意");
                }
                confirmed.add(item);
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String item : required) {
            if (!confirmed.contains(item)) missing.add(item);
        }
        if (!missing.isEmpty()) {
            throw new OrienteeringEventException(OrienteeringEventException.SAFETY_UNCONFIRMED,
                    "尚有安全项未确认: " + missing);
        }
        return new SafetyConfirmation(frozen.publishedConfigVersion, frozen.digest, cityZone,
                Collections.unmodifiableList(required), Collections.unmodifiableList(new ArrayList<String>(confirmed)));
    }

    static List<String> requiredItems(OrientSafetyPolicy.Decision decision) {
        List<String> required = new ArrayList<String>();
        required.add(ITEM_PUBLISHED_RULES);
        required.add(ITEM_CITY_TIMEZONE);
        required.add(ITEM_DAY_NIGHT);
        required.add(ITEM_WEATHER);
        required.add(ITEM_INSURANCE_OPTIONAL);
        if (Boolean.TRUE.equals(decision.minor)) {
            required.add(ITEM_AGE_GUARDIAN);
        }
        return required;
    }

    public static final class SafetyConfirmation {
        public final long frozenRuleVersion;
        public final String frozenRuleDigest;
        public final ZoneId cityZone;
        public final List<String> requiredItems;
        public final List<String> confirmedItems;

        SafetyConfirmation(long frozenRuleVersion, String frozenRuleDigest, ZoneId cityZone,
                           List<String> requiredItems, List<String> confirmedItems) {
            this.frozenRuleVersion = frozenRuleVersion;
            this.frozenRuleDigest = frozenRuleDigest;
            this.cityZone = cityZone;
            this.requiredItems = requiredItems;
            this.confirmedItems = confirmedItems;
        }

        public boolean complete() {
            return confirmedItems.containsAll(requiredItems);
        }
    }
}

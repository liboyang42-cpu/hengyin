package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/** 将商家自报事实转换成圈层供给的失败关闭准入判断。 */
public final class CircleSupplyPolicy {
    private static final long NIGHT_FRESHNESS_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final long PRIMARY_THEME_FRESHNESS_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final long CLOCK_SKEW_MILLIS = 5L * 60 * 1000;
    private static final Set<String> SUPPLY_KINDS = new HashSet<>(Arrays.asList(
            "PRODUCT", "SERVICE", "EVENT", "ZERO_BROWSE"));
    private static final Set<String> ACTION_TYPES = new HashSet<>(Arrays.asList(
            "NAVIGATE", "BOOK", "BUY"));

    private CircleSupplyPolicy() { }

    public static void assertValid(String themeCode, String policyCode,
                                   MerchantCircleSupplyProfile profile, Date now) {
        if (profile == null) throw new IllegalStateException("请确认商家现成供给");
        if (now == null) throw new IllegalArgumentException("缺少校验时间");
        require(profile.getSupplyName(), "请填写现成供给名称");
        if (!SUPPLY_KINDS.contains(profile.getSupplyKind())) {
            throw new IllegalStateException("供给类型不合法");
        }
        if (profile.getRegularPrice() == null || profile.getRegularPrice().signum() < 0) {
            throw new IllegalStateException("请填写正常价格");
        }
        if ("ZERO_BROWSE".equals(profile.getSupplyKind())) {
            if (profile.getRegularPrice().compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalStateException("0元供给价格必须为0");
            }
        } else if (profile.getRegularPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("非0元供给必须填写大于0的正常价格");
        }
        if (!ACTION_TYPES.contains(profile.getActionType())) {
            throw new IllegalStateException("行动入口类型不合法");
        }
        require(profile.getActionValue(), "请填写导航、预约或购买入口");
        require(profile.getAvailableHours(), "请填写真实开放时段");
        require(profile.getMerchantRules(), "请填写商家现场与取消规则");
        if (profile.getMinPeople() == null || profile.getMaxPeople() == null
                || profile.getMinPeople() < 1 || profile.getMaxPeople() < profile.getMinPeople()) {
            throw new IllegalStateException("供给人数范围不合法");
        }
        if (profile.getCheckedAt() == null
                || profile.getCheckedAt().getTime() > now.getTime() + CLOCK_SKEW_MILLIS) {
            throw new IllegalStateException("最后确认时间不合法");
        }
        if (isPrimaryDocumentTheme(themeCode)
                && now.getTime() - profile.getCheckedAt().getTime() > PRIMARY_THEME_FRESHNESS_MILLIS) {
            throw new IllegalStateException("该供给已超过30天未复核");
        }
        if (isPrimaryDocumentTheme(themeCode)) {
            if (profile.getDurationMinutes() == null || profile.getDurationMinutes() <= 0) {
                throw new IllegalStateException("请填写现成供给的体验时长");
            }
            if (!Integer.valueOf(0).equals(profile.getSplitBookingAllowed())
                    && !Integer.valueOf(1).equals(profile.getSplitBookingAllowed())) {
                throw new IllegalStateException("请确认同行接待或分开预约方式");
            }
            if (Integer.valueOf(1).equals(profile.getSplitBookingAllowed())
                    && profile.getMinPeople() > 1) {
                throw new IllegalStateException("分开预约必须允许1人单独购买或预约");
            }
            if (profile.getMaxPeople() < 2 && !Integer.valueOf(1).equals(profile.getSplitBookingAllowed())) {
                throw new IllegalStateException("多人实例需可同时接待同行，或明确允许分开预约");
            }
            if ("DATE".equals(themeCode) && profile.getMinPeople() > 2
                    && !Integer.valueOf(1).equals(profile.getSplitBookingAllowed())) {
                throw new IllegalStateException("约会主题需允许2人参与，或明确允许分开预约");
            }
            if ("FRIENDS".equals(themeCode)
                    && (profile.getMinPeople() > 2 || profile.getMaxPeople() < 5)
                    && !Integer.valueOf(1).equals(profile.getSplitBookingAllowed())) {
                throw new IllegalStateException("朋友主题需可接待2—5人，或明确允许分开预约");
            }
            if (!blank(profile.getOptionalBenefit()) && blank(profile.getBenefitRules())) {
                throw new IllegalStateException("附加权益需写明来源、有效期和核销规则");
            }
        }

        if ("AFTER_WORK".equals(themeCode) || "WORKDAY_EVENING".equals(policyCode)) {
            if (!Integer.valueOf(1).equals(profile.getWorkdayEveningCovered())) {
                throw new IllegalStateException("供给必须真实覆盖工作日18:00—21:30中的可用时段");
            }
        }
        if ("SOLO".equals(themeCode) || "SOLO".equals(policyCode)) {
            if (!Integer.valueOf(1).equals(profile.getSinglePersonAllowed())
                    || profile.getMinPeople() > 1) {
                throw new IllegalStateException("供给必须允许1人正常购买或预约");
            }
        }
        if ("PARENT_CHILD".equals(themeCode) || "PARENT_CHILD".equals(policyCode)) {
            require(profile.getDeclaredAgeRange(), "缺少DECLARED_AGE_RANGE");
            require(profile.getGuardianMode(), "缺少GUARDIAN_MODE");
            if (!Integer.valueOf(1).equals(profile.getGuardianPresentThroughout())
                    || !Integer.valueOf(0).equals(profile.getMerchantChildcareRequired())) {
                throw new IllegalStateException("监护人必须全程在场并承担监护责任");
            }
            require(profile.getAdultChildCapacity(), "缺少ADULT_CHILD_CAPACITY");
            require(profile.getPriceUnit(), "缺少PRICE_UNIT");
            require(profile.getRestrictions(), "缺少RESTRICTIONS");
            require(profile.getToolsAnimalsMaterials(), "缺少TOOLS_ANIMALS_MATERIALS");
            require(profile.getIncidentProcess(), "缺少INCIDENT_PROCESS");
            require(profile.getCancellationRule(), "缺少CANCELLATION_RULE");
        }
        if ("PHOTO_RULES".equals(policyCode)) {
            if (!Integer.valueOf(1).equals(profile.getPersonalPhotoAllowed())) {
                throw new IllegalStateException("商家未明确允许个人非商业拍摄");
            }
            require(profile.getPhotoAllowedArea(), "缺少允许拍摄区域");
            require(profile.getTripodPolicy(), "缺少三脚架规则");
            require(profile.getLightingPolicy(), "缺少补光灯规则");
            require(profile.getPhotoDurationLimit(), "缺少拍摄时长规则");
        }
        if ("NIGHT".equals(themeCode) || "NIGHT".equals(policyCode)) {
            require(profile.getNightHours(), "缺少NIGHT_HOURS");
            require(profile.getLastEntry(), "缺少LAST_ENTRY");
            if (profile.getAlcoholInvolved() == null) {
                throw new IllegalStateException("夜间供给必须明确是否涉及酒精");
            }
            if (!Integer.valueOf(0).equals(profile.getAlcoholInvolved())
                    && !Integer.valueOf(1).equals(profile.getAlcoholInvolved())) {
                throw new IllegalStateException("酒精标记不合法");
            }
            if (Integer.valueOf(1).equals(profile.getAlcoholInvolved())) {
                if (profile.getLegalDrinkingAge() == null || profile.getLegalDrinkingAge() < 18) {
                    throw new IllegalStateException("涉及酒精时必须填写当地法定饮酒年龄");
                }
                require(profile.getLegalDrinkingAgeRule(), "涉及酒精时必须填写法定饮酒年龄规则");
                require(profile.getAlcoholWarning(), "涉及酒精时必须填写饮酒风险提示");
                String ageRule = profile.getLegalDrinkingAgeRule().trim();
                if (!ageRule.contains("法定饮酒年龄")
                        || containsAny(ageRule, "不限年龄", "不限制年龄", "无需年龄")) {
                    throw new IllegalStateException("酒精供给必须明确仅限达到当地法定饮酒年龄者");
                }
            }
            if (now.getTime() - profile.getCheckedAt().getTime() > NIGHT_FRESHNESS_MILLIS) {
                throw new IllegalStateException("夜间营业时段已超过7天未确认");
            }
            if ("EVENT".equals(profile.getSupplyKind())
                    && (profile.getEventEndAt() == null || !profile.getEventEndAt().after(now))) {
                throw new IllegalStateException("单场夜间活动必须填写未来的EVENT_END_AT");
            }
        }
    }

    private static void require(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(message);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    private static boolean isPrimaryDocumentTheme(String themeCode) {
        return "FITNESS".equals(themeCode) || "MIDLIFE".equals(themeCode)
                || "DATE".equals(themeCode) || "FRIENDS".equals(themeCode)
                || "SHANGHAI".equals(themeCode);
    }
}

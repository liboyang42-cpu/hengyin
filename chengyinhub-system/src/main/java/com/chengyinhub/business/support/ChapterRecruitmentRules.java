package com.chengyinhub.business.support;

import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.system.domain.SysCategory;

import java.math.BigDecimal;

/** 章节商家承接在发布器与后台 CRUD 共用的输入规则。 */
public final class ChapterRecruitmentRules {

    private ChapterRecruitmentRules() {
    }

    public static boolean isEnabled(Integer recruitEnabled) {
        return Integer.valueOf(1).equals(recruitEnabled);
    }

    public static void assertRecruitEnabled(Integer recruitEnabled) {
        if (recruitEnabled != null && recruitEnabled != 0 && recruitEnabled != 1) {
            throw new IllegalStateException("章节招商开关只能是0或1");
        }
    }

    /** cms_topic_chapter.max_merchant 是有符号 TINYINT；0 明确表示不限。 */
    public static void assertMaxMerchant(Integer maxMerchant) {
        if (!isMaxMerchantValid(maxMerchant)) {
            throw new IllegalStateException("章节商家名额只能是0到127之间的整数");
        }
    }

    public static boolean isMaxMerchantValid(Integer maxMerchant) {
        return maxMerchant == null || (maxMerchant >= 0 && maxMerchant <= 127);
    }

    /** 只读兼容历史 null；无条件写 max_merchant 的入口必须显式写 0 表示不限。 */
    public static void assertPersistableMaxMerchant(Integer maxMerchant) {
        if (maxMerchant == null) {
            throw new IllegalStateException("章节商家名额不能为空；不限请传0");
        }
        assertMaxMerchant(maxMerchant);
    }

    /** 权益门槛可留空（不设门槛），一旦填写必须是正金额。 */
    public static boolean isPerkMinValueValid(BigDecimal perkMinValue) {
        return perkMinValue == null || perkMinValue.signum() > 0;
    }

    public static void assertPerkMinValue(BigDecimal perkMinValue) {
        if (!isPerkMinValueValid(perkMinValue)) {
            throw new IllegalStateException("权益最低价值须为大于0的金额；不设置请留空");
        }
    }

    /** 权益门槛只属于权益档；空值在权益档表示不设门槛。 */
    public static boolean isPerkMinValueValidForTerms(String termsMode, BigDecimal perkMinValue) {
        return (termsMode == null || TermsMode.PERK.equals(termsMode))
                ? isPerkMinValueValid(perkMinValue) : perkMinValue == null;
    }

    public static void assertPerkMinValueForTerms(String termsMode, BigDecimal perkMinValue) {
        if (isPerkMinValueValidForTerms(termsMode, perkMinValue)) {
            return;
        }
        if (termsMode != null && !TermsMode.PERK.equals(termsMode)) {
            throw new IllegalStateException("只有权益档有权益门槛;非权益档请留空");
        }
        assertPerkMinValue(perkMinValue);
    }

    /** V1 开放招商只承诺权益/引流；平台计酬须等 P2 的平台运营链路。 */
    public static boolean isV1RecruitmentTermsMode(String termsMode) {
        return termsMode == null || TermsMode.PERK.equals(termsMode) || TermsMode.TRAFFIC.equals(termsMode);
    }

    public static void assertV1RecruitmentTermsMode(String termsMode, boolean clubDirect) {
        if (clubDirect && TermsMode.REVSHARE.equals(termsMode)) return;
        assertV1RecruitmentTermsMode(termsMode);
    }

    public static void assertV1RecruitmentTermsMode(String termsMode) {
        if (!isV1RecruitmentTermsMode(termsMode)) {
            throw new IllegalStateException("V1开放招商仅支持权益合作或引流合作；平台计酬待P2开放");
        }
    }

    public static void assertMerchantCategory(Integer categoryId, SysCategory category) {
        if (categoryId == null || categoryId <= 0) {
            throw new IllegalStateException("开放商家承接的章节必须选择适合商家品类");
        }
        if (!isMerchantCategory(categoryId, category)) {
            throw new IllegalStateException("适合商家品类不存在或已停用");
        }
    }

    /** sys_category.type:主题类别 / 玩家路线偏好(商家历史行业也引用这一类,保留可用)。 */
    public static final long THEME_CATEGORY_TYPE = 1L;
    /** sys_category.type:商家行业(2026-11-03 业主 M-16:与主题类别分表,只给商家侧读)。 */
    public static final long MERCHANT_INDUSTRY_TYPE = 6L;

    /** 商家行业可取的 type:新的商家行业 6 + 存量商家 / 章节已引用的 1。 */
    public static boolean isMerchantCategoryType(Long type) {
        return Long.valueOf(MERCHANT_INDUSTRY_TYPE).equals(type) || Long.valueOf(THEME_CATEGORY_TYPE).equals(type);
    }

    /** 历史章节读侧和申请入口也只认可当前有效的一级商家品类。 */
    public static boolean isMerchantCategory(Integer categoryId, SysCategory category) {
        return categoryId != null && categoryId > 0
                && category != null && category.getStatus() != null && category.getStatus() == 1
                && isMerchantCategoryType(category.getType())
                && Long.valueOf(0L).equals(category.getParentId())
                && (category.getDelFlag() == null || category.getDelFlag() == 0);
    }
}

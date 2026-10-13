package com.chengyinhub.common.constant;

/**
 * P0-35 角色槽的主体类型。
 *
 * <p>⚠️ <b>{@link #PLATFORM} 是本次的重点</b>:原枚举只有 PLAYER/CLUB/MERCHANT,**没有平台**。
 * 而 ③-自营 是首轮内测标的(D12),没有这个值就没法表达 —— 施工图 §4 必清栏原话:
 * 「产品真源,**entity_type 须含 PLATFORM**」。
 *
 * <p>它同时是 <b>P0-50 REVSHARE 范围闸</b>的判据:计酬档只对
 * {@code product_role.CONTENT.entity_type = PLATFORM} 的主题开放 —— 因为 ③-自营 的票款是平台自营
 * 收入,计酬是平台的**采购/推广费支出**(应付台账 → 对公打款 → 商家开票),零收付通依赖;
 * 而 ③-第三方 的计酬 = **第三方之间分钱 = 二清**,必须等收付通(P0-43)。
 *
 * <p>⚠️ 在此之前,「这个主题是不是自营」在代码里**没有任何一列直说** —— 最接近的是
 * {@code CmsTopicServiceImpl:208} 那句裸魔数 {@code setMemberId(100000L)}(全仓只出现一次、
 * 无名无注释)。这与 P0-02 治好的病同源:别再用组合约定反推事实。
 */
public final class RoleEntityType {
    private RoleEntityType() {}

    /**
     * 平台自己 = ③-自营。
     * <p>⚠️ {@code entity_id} 必须为 NULL —— 平台不是某个 member。库里有 CHECK 兜底
     * ({@code ck_role_entity_id}),因为票款归属就挂在这个槽上,一行自相矛盾的数据就是一笔错账。
     */
    public static final String PLATFORM = "PLATFORM";

    /** 玩家个人。 */
    public static final String MEMBER = "MEMBER";

    /** 俱乐部。 */
    public static final String CLUB = "CLUB";

    /** 商家。 */
    public static final String MERCHANT = "MERCHANT";

    public static boolean isValid(String t) {
        return PLATFORM.equals(t) || MEMBER.equals(t) || CLUB.equals(t) || MERCHANT.equals(t);
    }

    /**
     * 内容槽同时定义票款归属;商家可以承接供给,但不能占用该槽。
     */
    public static boolean isContentOwner(String t) {
        return PLATFORM.equals(t) || MEMBER.equals(t) || CLUB.equals(t);
    }

    /** PLATFORM 不带 entity_id,其余三类必须带 —— 与库里的 ck_role_entity_id 同口径。 */
    public static boolean entityIdConsistent(String entityType, Long entityId) {
        if (PLATFORM.equals(entityType)) {
            return entityId == null;
        }
        return entityId != null;
    }
}

package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;

/** 登录态派生的演员上下文；perspective 只选择视图，权限仍由服务端重算。 */
public class GameActorContext {
    private final Long memberId;
    private final String perspective;
    /**
     * 商家视角解析出的商家主体(店主 memberId)与岗位权限码。
     * 直接构造的 player/club 视角为 null;merchant(memberId) 旧工厂等价于「登录人本人就是店主」。
     */
    private final Long merchantOwnerMemberId;
    private final String merchantRoleCode;

    public GameActorContext(Long memberId, String perspective) {
        this(memberId, perspective, null, null);
    }

    private GameActorContext(Long memberId, String perspective, Long merchantOwnerMemberId, String merchantRoleCode) {
        this.memberId = memberId;
        this.perspective = perspective == null ? null : perspective.toUpperCase();
        this.merchantOwnerMemberId = merchantOwnerMemberId;
        this.merchantRoleCode = merchantRoleCode;
    }

    public static GameActorContext player(Long memberId) { return new GameActorContext(memberId, "PLAYER"); }
    public static GameActorContext club(Long memberId) { return new GameActorContext(memberId, "CLUB"); }

    /** 登录人本人即商家主体的旧语义(店主直连/单测装配)。 */
    public static GameActorContext merchant(Long memberId) {
        return new GameActorContext(memberId, "MERCHANT", memberId, MerchantRoleCode.MERCHANT_OWNER);
    }

    /** 商家视角:站点归属用 access 的商家主体,权限用 access 的岗位角色。 */
    public static GameActorContext merchant(Long memberId, MerchantAccessContext access) {
        if (access == null) throw new IllegalArgumentException("merchant access required");
        return new GameActorContext(memberId, "MERCHANT", access.getOwnerMemberId(), access.getRoleCode());
    }

    public Long getMemberId() { return memberId; }
    public String getPerspective() { return perspective; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public String getMerchantRoleCode() { return merchantRoleCode; }
}

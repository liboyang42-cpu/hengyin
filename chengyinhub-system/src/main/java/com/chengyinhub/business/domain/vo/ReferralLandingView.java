package com.chengyinhub.business.domain.vo;

import java.util.Date;

/**
 * 推广凭据落地页只读投影（匿名可读）。
 *
 * <p><b>只读、不发放任何东西</b>：这里没有 claim/支付/权益写入，也不存在"凭 inviter 参数
 * 送一次免费局"的路径。要真正获得准入，仍走原有注册/购买/领取接口，并且凭据只证明
 * 「这次分享来自哪个会员 + 推广哪个目标 + 有效到何时」，不含价格、折扣或佣金。
 *
 * <p>状态码是稳定分支依据：{@code VALID} 才会给出分享者公开昵称/头像与目标标题；
 * 其余状态不暴露分享者资料，也不用错误码差异暗示凭据是否存在以外的信息。
 */
public class ReferralLandingView {

    public static final String KIND_REFERRAL = "REFERRAL";

    public static final String STATE_VALID = "VALID";
    public static final String STATE_INVALID_TOKEN = "INVALID_TOKEN";
    public static final String STATE_TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String STATE_TOKEN_REVOKED = "TOKEN_REVOKED";
    public static final String STATE_TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE";
    public static final String STATE_INVITER_UNAVAILABLE = "INVITER_UNAVAILABLE";

    public static final String ACTION_NONE = "NONE";
    /** 落地页不能证明注册可办：只引导去目标页，由原注册/报价接口重新校验资格。 */
    public static final String ACTION_VIEW_TARGET = "VIEW_TARGET";

    /** 未核验：仅表示"现有公开投影没有给出不可办结论"，不代表已确认可报名/可购买。 */
    public static final String REGISTRATION_UNKNOWN = "UNKNOWN";
    /**
     * 现有公开售卖投影明确给出停售/未在售，**且**没有开着的免费自玩入口；这是事实结论，不是猜的。
     * 免费入口开着（{@code freeSelfPlay=true}）时即使付费售卖为 NOT_ON_SALE/SALE_ENDED 也回 UNKNOWN。
     */
    public static final String REGISTRATION_CLOSED = "CLOSED";

    private String kind = KIND_REFERRAL;

    private String state;
    private String reasonCode;
    /** 分享者公开资料最小值；仅 VALID 时给出，不含会员 id、手机号或余额。 */
    private Member inviter;
    private String campaignKey;
    private Target target;
    private Date issuedAt;
    private Date expiresAt;
    /**
     * 报名/购买资格状态：只有 {@link #REGISTRATION_UNKNOWN} / {@link #REGISTRATION_CLOSED}。
     * 公开可见 + 凭据有效都不能证明报名窗口/容量/资格仍开放，所以不设"可报名"状态。
     */
    private String registrationState = REGISTRATION_UNKNOWN;
    /**
     * 现有公开目录投影的售卖状态（仅主题）：ON_SALE / SALE_ENDED / NOT_ON_SALE / null。
     * 这是目录卡片的只读事实，不等于可报名；真正下单仍走原注册/商品报价闸。
     */
    private String saleState;
    /** 现有公开目录投影的免费自玩事实（仅主题）；不是本凭据发放的权益。 */
    private Boolean freeSelfPlay;
    private String nextAction = ACTION_NONE;
    private Endpoints endpoints;
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Member getInviter() { return inviter; }
    public void setInviter(Member inviter) { this.inviter = inviter; }

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Target getTarget() { return target; }
    public void setTarget(Target target) { this.target = target; }

    public Date getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Date issuedAt) { this.issuedAt = issuedAt; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

    public String getRegistrationState() { return registrationState; }
    public void setRegistrationState(String registrationState) { this.registrationState = registrationState; }

    public String getSaleState() { return saleState; }
    public void setSaleState(String saleState) { this.saleState = saleState; }

    public Boolean getFreeSelfPlay() { return freeSelfPlay; }
    public void setFreeSelfPlay(Boolean freeSelfPlay) { this.freeSelfPlay = freeSelfPlay; }

    public String getNextAction() { return nextAction; }
    public void setNextAction(String nextAction) { this.nextAction = nextAction; }

    public Endpoints getEndpoints() { return endpoints; }
    public void setEndpoints(Endpoints endpoints) { this.endpoints = endpoints; }

    /** 分享者公开资料白名单：昵称 + 头像，不含 id。 */
    public static class Member {
        private String nickname;
        private String avatar;

        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }

        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
    }

    /** 推广目标公开摘要：类型、id、标题与稳定路由（仅主题有路由）。 */
    public static class Target {
        private Integer type;
        private Long id;
        private String title;
        private String canonicalPath;

        public Integer getType() { return type; }
        public void setType(Integer type) { this.type = type; }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getCanonicalPath() { return canonicalPath; }
        public void setCanonicalPath(String canonicalPath) { this.canonicalPath = canonicalPath; }
    }

    /** 真实可走的原有接口；落地页不新增下单/发券入口。 */
    public static class Endpoints {
        private String registrationQuote = "/api/registration/quote";
        private String registrationCreate = "/api/registration/create";
        private String claimAttribution = "/api/marketing/attribution/claim";
        private boolean claimRequiresLogin = true;

        public String getRegistrationQuote() { return registrationQuote; }
        public void setRegistrationQuote(String registrationQuote) { this.registrationQuote = registrationQuote; }

        public String getRegistrationCreate() { return registrationCreate; }
        public void setRegistrationCreate(String registrationCreate) { this.registrationCreate = registrationCreate; }

        public String getClaimAttribution() { return claimAttribution; }
        public void setClaimAttribution(String claimAttribution) { this.claimAttribution = claimAttribution; }

        public boolean isClaimRequiresLogin() { return claimRequiresLogin; }
        public void setClaimRequiresLogin(boolean claimRequiresLogin) { this.claimRequiresLogin = claimRequiresLogin; }
    }
}

package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 视频内容悬赏活动配置(video_bounty_campaign)。
 *
 * <p><b>定位</b>:创作者拍关卡短视频(TikTok/Reels/YouTube)带联盟链接,平台按
 * <b>有效自然播放</b>记录应计。参考口径(发布后 7 天 / 每千次播放 €2 / 单条封顶 €500)
 * 只是可配置默认值,不是线上费率;{@code cash_payoutEnabled} 默认 0,
 * 本域只落"应计记录",不做现金兑付,也不改任何钱包或支付状态。
 *
 * <p><b>钱/权益边界</b>:本表无余额、无支付、无分账字段。应计金额进
 * {@link VideoBountyAccrual},状态为"仅记录",不冒充已打款。
 *
 * @author chengyinhub
 */
public class VideoCampaign
{
    /** 草稿:可编辑,不接受投稿。 */
    public static final int STATUS_DRAFT = 0;
    /** 进行中:接受投稿与证据录入。 */
    public static final int STATUS_ACTIVE = 1;
    /** 暂停:不接受新投稿,已有投稿仍可审核/结算应计。 */
    public static final int STATUS_PAUSED = 2;
    /** 关闭:不再接受投稿,历史应计保留。 */
    public static final int STATUS_CLOSED = 3;

    private Long id;
    /** 活动标识,供外部引用与 M1 推广凭据 campaignKey 对齐。 */
    private String campaignKey;
    private String title;
    private String description;
    private Integer status;
    /**
     * 活动归属的创作者会员 id;成交归因只按这个 owner 去聚合。
     *
     * <p><b>为什么必须显式存</b>:归因域的 {@code aggregateOwnedCampaign} 只认
     * "凭据分享人 = 调用者"这一条绑定。若活动没有 owner,就没有一个合法的聚合主体,
     * 只能返回"未配置"而不是拿调用者自己的 id 去凑数(null = 未指定)。
     */
    private Long ownerMemberId;
    /** ISO-4217 币种;仅记录口径,不做汇率换算。 */
    private String currency;
    /** 每千次有效自然播放的应计金额。只作为规则快照,不在此处直接算钱。 */
    private BigDecimal ratePerThousand;
    /** 同一原视频跨平台合计封顶(单条视频封顶)。 */
    private BigDecimal perVideoCap;
    /** 发布后计入应计的天数窗口。 */
    private Integer windowDays;
    /** 活动总应计预算上限(可空 = 未设预算上限,不代表已批准支出)。 */
    private BigDecimal totalBudgetCap;
    /** 0=现金兑付未启用(默认);1=已启用(需另行批准兑付集成,本域不实现打款)。 */
    private Integer cashPayoutEnabled;
    /** 规则版本;改变费率/封顶/窗口时必须换版本,历史应计保留旧版本。 */
    private String ruleVersion;
    /** 平台白名单、去重策略等附加配置(JSON 字符串,null = 未配置)。 */
    private String configJson;
    private String delFlag;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getRatePerThousand() { return ratePerThousand; }
    public void setRatePerThousand(BigDecimal ratePerThousand) { this.ratePerThousand = ratePerThousand; }
    public BigDecimal getPerVideoCap() { return perVideoCap; }
    public void setPerVideoCap(BigDecimal perVideoCap) { this.perVideoCap = perVideoCap; }
    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer windowDays) { this.windowDays = windowDays; }
    public BigDecimal getTotalBudgetCap() { return totalBudgetCap; }
    public void setTotalBudgetCap(BigDecimal totalBudgetCap) { this.totalBudgetCap = totalBudgetCap; }
    public Integer getCashPayoutEnabled() { return cashPayoutEnabled; }
    public void setCashPayoutEnabled(Integer cashPayoutEnabled) { this.cashPayoutEnabled = cashPayoutEnabled; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}

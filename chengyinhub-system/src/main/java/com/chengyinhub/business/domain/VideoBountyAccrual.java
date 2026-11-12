package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 视频悬赏应计记录(video_bounty_accrual):<b>只记录计算,不产生支付</b>。
 *
 * <p>{@code payoutState=0}(RECORDED_ONLY)是默认且本域唯一会写入的状态:因为外部平台
 * 自然播放数据源<b>当前未配置</b>(我们尚未核实任何一家平台提供可用的只读口径或合规导出),
 * 且现金兑付通道<b>尚未接入</b>。本表不写钱包、不写支付、不写分账。
 *
 * <p><b>可追溯</b>:同一组证据对应一个 {@code idempotencyKey};重新计算只会追加新行并把
 * 旧行置为 {@code SUPERSEDED},不覆盖历史。封顶按"同一原视频跨平台合计"计算。
 *
 * @author chengyinhub
 */
public class VideoBountyAccrual
{
    /** 仅记录,不可兑付(默认)。 */
    public static final int PAYOUT_RECORDED_ONLY = 0;
    /** 待兑付(需独立获批的兑付集成;本域不写)。 */
    public static final int PAYOUT_PENDING = 1;
    /** 已打款(需真实结算回执;本域不写)。 */
    public static final int PAYOUT_PAID = 2;
    /** 已被新证据重算替代。 */
    public static final int PAYOUT_SUPERSEDED = 3;
    /** 已撤销(投稿被撤回/申诉成立)。 */
    public static final int PAYOUT_REVERSED = 4;

    private Long id;
    private Long submissionId;
    private Long campaignId;
    /** 同组(同一原视频)分组键,封顶按组合计。 */
    private String groupKey;
    private String ruleVersion;
    /** 本条投稿在窗口内的有效自然播放合计。 */
    private Long basisViews;
    private BigDecimal ratePerThousand;
    private BigDecimal perVideoCap;
    /** 同组所有跨平台投稿的窗口内播放合计。 */
    private Long groupBasisViews;
    /** 未封顶前金额。 */
    private BigDecimal grossAmount;
    /** 按组封顶并分摊后金额。 */
    private BigDecimal payableAmount;
    private String currency;
    private Integer payoutState;
    /** 幂等键:campaignId|groupKey|ruleVersion|watermark|submissionId。 */
    private String idempotencyKey;
    private Date windowStart;
    private Date windowEnd;
    /** 证据水位(同组最大证据 id),证据变化才会产生新行。 */
    private Long evidenceWatermark;
    private String note;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public Long getBasisViews() { return basisViews; }
    public void setBasisViews(Long basisViews) { this.basisViews = basisViews; }
    public BigDecimal getRatePerThousand() { return ratePerThousand; }
    public void setRatePerThousand(BigDecimal ratePerThousand) { this.ratePerThousand = ratePerThousand; }
    public BigDecimal getPerVideoCap() { return perVideoCap; }
    public void setPerVideoCap(BigDecimal perVideoCap) { this.perVideoCap = perVideoCap; }
    public Long getGroupBasisViews() { return groupBasisViews; }
    public void setGroupBasisViews(Long groupBasisViews) { this.groupBasisViews = groupBasisViews; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Integer getPayoutState() { return payoutState; }
    public void setPayoutState(Integer payoutState) { this.payoutState = payoutState; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Date getWindowStart() { return windowStart; }
    public void setWindowStart(Date windowStart) { this.windowStart = windowStart; }
    public Date getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Date windowEnd) { this.windowEnd = windowEnd; }
    public Long getEvidenceWatermark() { return evidenceWatermark; }
    public void setEvidenceWatermark(Long evidenceWatermark) { this.evidenceWatermark = evidenceWatermark; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}

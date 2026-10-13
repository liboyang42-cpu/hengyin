package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 核销流水 redemption(施工图 §3.2 · 不可变核销事件 + 反向撤销事件)。
 * 为什么撤销不用删/改行:店员误扫、运营撤销、权益补发后重新核销都要留痕,删了就查不出发生过什么。
 * 唯一键 uk_redemption_active 只对 ACTIVE 生效(靠生成列 active_key),这是**跨商家重复核销的根治**:
 * ③ 每票每章一次 ⇒ 键=registration-chapter;① 整单一次 ⇒ chapter_id 为 NULL,键退化成 registration-ORDER;
 * 撤销后生成列变 NULL,MySQL 唯一索引不约束 NULL ⇒ 键自动释放,可重新核销。
 */
public class Redemption extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 报名id(=票) */
    private Long registrationId;
    /** 章节id;NULL=① 整单核销(无章节维度) */
    private Long chapterId;
    private Long topicId;
    /** 核销商家memberId(⚠️非 mms_merchant.id) */
    private Long merchantId;
    /** 命中的 merchant_chapter_offer.id;NULL=① 无供给概念 */
    private Long offerId;
    /** 实际操作人memberId:店员≠商家主体时靠它区分,追责到人 */
    private Long operatorId;
    /** ACTIVE生效 / REVERSED已撤销 */
    private String status;

    /**
     * 请求级幂等键。与 activeKey 不是一回事,两个都要:
     * activeKey 保护「业务结果」唯一(每票每章一次),本键保护「请求」幂等。
     * 两者在两处分岔:①撤销后 activeKey 已是 NULL,无键拦重复撤销;②扫码超时重试时撞 1062,
     * 应用无法区分「我自己的重试」(该返回成功)与「另一家商家抢核销」(必须拒绝)。
     * 构造:redeem:{reg}:{chapter}:{merchant} / reverse:{redemptionId} —— 两个动作不同前缀,各自幂等。
     */
    private String idempotencyKey;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date reversedAt;
    /** 撤销原因(误扫/运营撤销/权益补发) */
    private String reverseReason;
    /**
     * 生效核销的唯一键,库里是 GENERATED ALWAYS AS ... VIRTUAL 生成列。
     * ⚠️ **只读**:由 MySQL 从 status/registration_id/chapter_id 算出,
     * 任何 insert/update 把它写进列清单都会报 3105(不能给生成列赋值)。
     * 留这个字段只为查询/排障时能看到键值,不参与写入。
     */
    private String activeKey;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Date getReversedAt() { return reversedAt; }
    public void setReversedAt(Date reversedAt) { this.reversedAt = reversedAt; }
    public String getReverseReason() { return reverseReason; }
    public void setReverseReason(String reverseReason) { this.reverseReason = reverseReason; }
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
}

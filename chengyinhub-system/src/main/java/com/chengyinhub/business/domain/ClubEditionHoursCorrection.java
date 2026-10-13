package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 已确认工时的更正留痕（append-only）。
 *
 * <p>为什么要这张表：{@code confirmActualHoursCas} 的 where 写死旧状态 {@code REPORTED}，
 * {@code reportActualHoursCas} 又不收 {@code CONFIRMED} —— 平台把 ONSITE 确认成 40（实际 4）之后
 * 两条 CAS 各命中 0 行，该类工时<b>永久锁死在错值</b>，只能改库；确认成偏小则是无补救的少付。
 * 同批的 A1（质量维版本化更正）与 A2（EXTRA append-only 更正链）都有更正路径，唯独工时没有。
 *
 * <p>放宽 {@code confirmActualHoursCas} 的旧状态<b>不是</b>解法：那会把「一次确认」变成「随便改」。
 * 这里走的是「带理由的退回」——先把被推翻的那条确认事实（值 + 确认人 + 确认时刻）整份快照进本表，
 * 再把当前行退回 {@code REPORTED} 并清空 {@code confirmed_*}；重新确认仍然只能走原来那条唯一 CAS。
 * ⇒ 旧事实不被覆盖（它在本表里）、写已确认工时的路径仍然只有一条。
 *
 * <p>退回期间四类不齐 ⇒ {@code confirmedHoursByKind} 返 null ⇒ EXTRA 整体 PENDING，
 * 不会拿着错值去付款；这也是为什么退回要清空 {@code confirmed_*} 而不是留着当第二个真值。
 */
public class ClubEditionHoursCorrection {

    private Long id;
    private Long topicId;
    private Long clubId;
    private String hourKind;
    /** 被推翻的那条确认事实的工时值。 */
    private BigDecimal previousConfirmedHours;
    /** 被推翻的那条确认事实是谁定的。 */
    private Long previousConfirmedBy;
    /** 被推翻的那条确认事实定在哪一刻；同时是退回 CAS 的乐观锁比对值。 */
    private Date previousConfirmedAt;
    /** 谁发起这次更正。 */
    private Long reopenedBy;
    /** 更正理由；空串在 DB 层就写不进来（ck_club_edition_hours_correction_reason）。 */
    private String reason;
    /** 由数据库时钟写，Java 不传 —— 传时间就等于允许「事后补一条更正并把时间写早」。 */
    private Date reopenedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }

    public String getHourKind() { return hourKind; }
    public void setHourKind(String hourKind) { this.hourKind = hourKind; }

    public BigDecimal getPreviousConfirmedHours() { return previousConfirmedHours; }
    public void setPreviousConfirmedHours(BigDecimal previousConfirmedHours) {
        this.previousConfirmedHours = previousConfirmedHours;
    }

    public Long getPreviousConfirmedBy() { return previousConfirmedBy; }
    public void setPreviousConfirmedBy(Long previousConfirmedBy) { this.previousConfirmedBy = previousConfirmedBy; }

    public Date getPreviousConfirmedAt() { return previousConfirmedAt; }
    public void setPreviousConfirmedAt(Date previousConfirmedAt) { this.previousConfirmedAt = previousConfirmedAt; }

    public Long getReopenedBy() { return reopenedBy; }
    public void setReopenedBy(Long reopenedBy) { this.reopenedBy = reopenedBy; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Date getReopenedAt() { return reopenedAt; }
    public void setReopenedAt(Date reopenedAt) { this.reopenedAt = reopenedAt; }
}

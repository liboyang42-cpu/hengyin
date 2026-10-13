package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 每票每必选章的权益应得/已得/失效 registration_entitlement(施工图 §3.2 · 容量预占真源)。
 * 为什么售出即建行:售出一张 5 必选章通票的瞬间,平台已欠玩家 5 份章节权益 ⇒
 * **未核销(status=0)也占容量**,容量真源是本表的「已售未核销条数」,不是某个 offer 的预占列
 * (offer 刻意不设预占列 —— 玩家购买时并未选商家,设了就有两个真源)。
 */
public class RegistrationEntitlement extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 报名id(=票) */
    private Long registrationId;
    /** 必选章节id;UNIQUE(registration_id, chapter_id) 保证每票每章 1 次 */
    private Long chapterId;
    /** 冗余主题id:按主题聚合时免去 join 章节表 */
    private Long topicId;
    /** 0应得(已售未核销,占容量) 1已得(已核销) 2失效(退款/取消,释放容量) */
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    /** 失效原因 REFUND/ORGANIZER_CANCEL/FULFILLMENT_GAP/MANUAL(status=2 时应填)。
     *  四种失效在容量口径上等价(都释放),但商家对账「我这章为什么少了 8 份预占」要答得出来。 */
    private String invalidReason;

    private Date redeemedAt;

    /** 章节名(瞬态,订单详情展示用)。 */
    private String chapterName;

    /** 状态文案(瞬态):待核销 / 已核销 / 已失效。 */
    private String statusLabel;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String invalidReason) { this.invalidReason = invalidReason; }
    public Date getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Date redeemedAt) { this.redeemedAt = redeemedAt; }

    public String getChapterName() { return chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }

    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }
}

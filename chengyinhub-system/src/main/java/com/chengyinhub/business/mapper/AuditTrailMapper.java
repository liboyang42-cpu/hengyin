package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.AuditTrailEntryVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * DOC2 O5c-(a):统一留痕只读投影。五张既有留痕表各一条 SELECT,合并排序在 Service 里做
 * (各表 collation 不同,不用 UNION 免得撞 Illegal mix of collations)。只读,不写任何表。
 */
public interface AuditTrailMapper {

    /** audit_record ⨝ audit_task:按内容 (biz_type, biz_id) 取全部审核单的时间线。 */
    List<AuditTrailEntryVO> selectAuditRecordTrail(@Param("bizType") String bizType, @Param("bizId") Long bizId,
                                                   @Param("limit") int limit);

    /** business_audit_event:审核裁决/撤销事件(domain_type=PLATFORM, target_type=AUDIT_TASK)。 */
    List<AuditTrailEntryVO> selectBusinessAuditEventTrail(@Param("bizType") String bizType, @Param("bizId") Long bizId,
                                                          @Param("limit") int limit);

    /** community_post_enforcement:帖文处置(post_id)。 */
    List<AuditTrailEntryVO> selectPostEnforcementTrail(@Param("postId") Long postId, @Param("limit") int limit);

    /** community_report_timeline ⨝ community_report_case:针对该对象的举报阶段时间线(只取 public_note)。 */
    List<AuditTrailEntryVO> selectReportTimelineTrail(@Param("targetType") String targetType,
                                                      @Param("targetId") Long targetId, @Param("limit") int limit);

    /** official_event_audit_log:官方活动 V2 操作审计。 */
    List<AuditTrailEntryVO> selectOfficialEventAuditTrail(@Param("eventId") Long eventId, @Param("limit") int limit);
}

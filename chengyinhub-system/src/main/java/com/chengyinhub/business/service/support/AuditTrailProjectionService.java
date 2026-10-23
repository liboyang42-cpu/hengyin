package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.vo.AuditTrailEntryVO;
import com.chengyinhub.business.mapper.AuditTrailMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DOC2 O5c-(a):一个内容的完整留痕,一处读。
 *
 * <p>只读投影,不迁移/不删除任何既有留痕表。按内容 (bizType, bizId) 汇总:
 * <ul>
 *   <li>所有类型:audit_record(经 audit_task)+ business_audit_event(审核裁决/撤销,target=AUDIT_TASK);</li>
 *   <li>COMMUNITY_POST:另加 community_post_enforcement(post_id)+ 举报时间线(target_type=CONTENT);</li>
 *   <li>ACTIVITY:另加举报时间线(target_type=ACTIVITY);</li>
 *   <li>OFFICIAL_EVENT:另加 official_event_audit_log(event_id)。</li>
 * </ul>
 * 按事件时间升序合并;同一时刻按来源固定顺序再按源表 id,结果稳定。每个来源与总数都有上限。
 *
 * <p>开关 {@code chengyin.audit.o5c.unified-trail.enabled} 默认关:关时不查任何表。
 */
@Service
public class AuditTrailProjectionService {

    /** 每个来源最多取多少条;总条数上限见 {@link #MAX_TOTAL}。 */
    static final int PER_SOURCE_LIMIT = 200;
    static final int MAX_TOTAL = 500;
    private static final Pattern BIZ_TYPE = Pattern.compile("[A-Za-z_]{1,40}");
    private static final List<String> SOURCE_ORDER = Arrays.asList(
            AuditTrailEntryVO.SOURCE_AUDIT_RECORD,
            AuditTrailEntryVO.SOURCE_BUSINESS_AUDIT_EVENT,
            AuditTrailEntryVO.SOURCE_POST_ENFORCEMENT,
            AuditTrailEntryVO.SOURCE_REPORT_TIMELINE,
            AuditTrailEntryVO.SOURCE_OFFICIAL_EVENT_AUDIT_LOG);

    private final AuditTrailMapper auditTrailMapper;

    @Value("${chengyin.audit.o5c.unified-trail.enabled:false}")
    private boolean enabled = false;

    public AuditTrailProjectionService(AuditTrailMapper auditTrailMapper) {
        this.auditTrailMapper = auditTrailMapper;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @throws IllegalArgumentException bizType/bizId 不合法
     * @throws IllegalStateException    开关关闭
     */
    public List<AuditTrailEntryVO> trail(String bizType, Long bizId) {
        if (!enabled) {
            throw new IllegalStateException("统一留痕查询未开启");
        }
        if (bizType == null || !BIZ_TYPE.matcher(bizType.trim()).matches() || bizId == null || bizId <= 0L) {
            throw new IllegalArgumentException("bizType/bizId 不合法");
        }
        String type = bizType.trim();
        List<AuditTrailEntryVO> all = new ArrayList<AuditTrailEntryVO>();
        addAll(all, auditTrailMapper.selectAuditRecordTrail(type, bizId, PER_SOURCE_LIMIT));
        addAll(all, auditTrailMapper.selectBusinessAuditEventTrail(type, bizId, PER_SOURCE_LIMIT));
        if (AuditTask.BIZ_COMMUNITY_POST.equals(type)) {
            addAll(all, auditTrailMapper.selectPostEnforcementTrail(bizId, PER_SOURCE_LIMIT));
            addAll(all, auditTrailMapper.selectReportTimelineTrail(CommunityReportCase.TARGET_CONTENT, bizId,
                    PER_SOURCE_LIMIT));
        } else if (AuditTask.BIZ_ACTIVITY.equals(type)) {
            addAll(all, auditTrailMapper.selectReportTimelineTrail(CommunityReportCase.TARGET_ACTIVITY, bizId, PER_SOURCE_LIMIT));
        } else if (AuditTask.BIZ_OFFICIAL_EVENT.equals(type)) {
            addAll(all, auditTrailMapper.selectOfficialEventAuditTrail(bizId, PER_SOURCE_LIMIT));
        }
        all.sort(ORDER);
        return all.size() > MAX_TOTAL ? new ArrayList<AuditTrailEntryVO>(all.subList(0, MAX_TOTAL)) : all;
    }

    private static void addAll(List<AuditTrailEntryVO> into, List<AuditTrailEntryVO> rows) {
        if (rows == null) return;
        for (AuditTrailEntryVO row : rows) {
            if (row != null) into.add(row);
        }
    }

    static final Comparator<AuditTrailEntryVO> ORDER = Comparator
            .comparing(AuditTrailEntryVO::getEventTime, Comparator.nullsLast(Comparator.<Date>naturalOrder()))
            .thenComparingInt(e -> {
                int i = SOURCE_ORDER.indexOf(e.getSource());
                return i < 0 ? SOURCE_ORDER.size() : i;
            })
            .thenComparing(AuditTrailEntryVO::getSourceId, Comparator.nullsLast(Comparator.<Long>naturalOrder()));
}

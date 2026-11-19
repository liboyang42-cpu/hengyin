package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.dto.TopicSaleCanonicalDraftDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.ITopicAuthorSaleService;
import com.chengyinhub.business.service.ITopicSaleCanonicalDraftService;
import com.chengyinhub.business.service.ITopicSaleFinalizationService;
import com.chengyinhub.business.service.support.FreeExploreCanonicalDefaults;
import com.chengyinhub.business.service.support.FreeExploreSlotProgress;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F10 §3.4 作者路径开售。照搬城市通票「签齐才定价」:位置全部有人承接(商家或作者自己)后,
 * 作者在编辑器票价区填完票价点「发布」,一个事务内完成锁价 → 场次票价同步 → canonical 草稿 → 开售。
 *
 * <p>开售前的全部硬闸都还在:confirmPrice 的地板价/计酬必亏闸/自玩价闸,finalizeForSale 的
 * 时间、供给覆盖、阵容、场次票投影、canonical 完整性校验。只有「等运营审核开售」这一步改为作者发布即生效。
 */
@Service
public class TopicAuthorSaleServiceImpl implements ITopicAuthorSaleService {

    static final String AUDIT_ACTION = "AUTHOR_FE_PUBLISH_SALE";

    private final CmsTopicMapper topicMapper;
    private final OmsTicketMapper ticketMapper;
    private final CmsActivityMapper activityMapper;
    private final AuditTaskMapper auditTaskMapper;
    private final BusinessAuditEventMapper businessAuditEventMapper;
    private final IPricingService pricingService;
    private final IOmsTicketService omsTicketService;
    private final ITopicSaleCanonicalDraftService canonicalDraftService;
    private final IAuditTaskService auditTaskService;
    private final ITopicSaleFinalizationService finalizationService;
    private final FreeExploreSlotProgress slotProgress;
    private final ObjectProvider<ICoopInviteService> coopInviteService;

    public TopicAuthorSaleServiceImpl(CmsTopicMapper topicMapper, OmsTicketMapper ticketMapper,
                                      CmsActivityMapper activityMapper, AuditTaskMapper auditTaskMapper,
                                      BusinessAuditEventMapper businessAuditEventMapper,
                                      IPricingService pricingService, IOmsTicketService omsTicketService,
                                      ITopicSaleCanonicalDraftService canonicalDraftService,
                                      IAuditTaskService auditTaskService,
                                      ITopicSaleFinalizationService finalizationService,
                                      FreeExploreSlotProgress slotProgress,
                                      ObjectProvider<ICoopInviteService> coopInviteService) {
        this.topicMapper = topicMapper;
        this.ticketMapper = ticketMapper;
        this.activityMapper = activityMapper;
        this.auditTaskMapper = auditTaskMapper;
        this.businessAuditEventMapper = businessAuditEventMapper;
        this.pricingService = pricingService;
        this.omsTicketService = omsTicketService;
        this.canonicalDraftService = canonicalDraftService;
        this.auditTaskService = auditTaskService;
        this.finalizationService = finalizationService;
        this.slotProgress = slotProgress;
        this.coopInviteService = coopInviteService;
    }

    @Override
    public Readiness readiness(Long topicId, Long authorMemberId) {
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        assertAuthorTopic(topic, authorMemberId);
        FreeExploreSlotProgress.Slots slots = slotProgress.slots(topicId);
        Readiness r = new Readiness();
        r.setTopicId(topicId);
        r.setLifecycle(topic.getLifecycle());
        r.setTotalSlots(slots.getTotal());
        r.setFilledSlots(slots.getFilled());
        r.setOpenSlots(slots.getOpen());
        boolean preSale = isState(topic, TopicLifecycleState.RECRUITING) || isState(topic, TopicLifecycleState.PRICING);
        r.setCanFillPrice(preSale && slots.isAllFilled());
        r.setCanPublish(r.isCanFillPrice());
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long publishForSale(Long topicId, Long authorMemberId, Map<Long, BigDecimal> ticketPrices) {
        if (topicId == null) throw new IllegalStateException("缺少主题");
        if (ticketPrices == null || ticketPrices.isEmpty()) throw new IllegalStateException("请填写票价");
        for (BigDecimal price : ticketPrices.values()) {
            if (price == null) throw new IllegalStateException("每张票都要填写票价");
            if (price.signum() < 0) throw new IllegalStateException("票价不能为负");
            if (price.scale() > 2 && price.stripTrailingZeros().scale() > 2) {
                throw new IllegalStateException("票价最多保留两位小数");
            }
        }
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        assertAuthorTopic(topic, authorMemberId);
        // 招募中但位置其实已经全部有人承接(例如没有必选位置):先按同一判据进入待定价。
        if (isState(topic, TopicLifecycleState.RECRUITING)) {
            slotProgress.tryAdvanceToPricing(topicId);
            topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        }
        if (isState(topic, TopicLifecycleState.RECRUITING)) {
            int open = slotProgress.slots(topicId).getOpen();
            throw new IllegalStateException("还有 " + open + " 个位置待承接，全部承接后才能填价发布");
        }
        if (!isState(topic, TopicLifecycleState.PRICING)) {
            throw new IllegalStateException("主题不在待定价阶段，不能发布开售");
        }
        if (topic.getFeePlatformRate() != null) {
            // 旧路径(定价页锁价后等运营开售)的存量主题继续走老路径,不在这里二次锁价。
            throw new IllegalStateException("该主题已锁价，等待运营开售");
        }
        if (hasOpenSaleReview(topicId)) {
            throw new IllegalStateException("该主题已提交运营开售审核，请等待审核结果");
        }
        assertCoversTopicTickets(topicId, ticketPrices);

        // ① 锁价:复用地板价、计酬必亏闸、自玩价闸与空价补齐(自由探索逐张写终价)。
        pricingService.confirmPrice(topicId, null, null, null, null, null, ticketPrices);

        // ② 全部场次同步票价(隐患 ⑨:自营场次壳复制的是发布时的空价,不同步就会 PRICE 投影不一致)。
        syncAllSessionTickets(topicId);

        // ③ canonical 草稿:人工字段按自由探索默认值,派生字段由服务端重建。
        TopicSaleCanonicalDraft existing = canonicalDraftService.get(topicId);
        TopicSaleCanonicalDraftDTO draft = new TopicSaleCanonicalDraftDTO();
        draft.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION);
        draft.setExpectedParameterVersion(existing == null ? 0L : existing.getParameterVersion());
        draft.setCanonical(FreeExploreCanonicalDefaults.draft(topic, totalInventory(topicId)));
        TopicSaleCanonicalDraft saved = canonicalDraftService.save(topicId, authorMemberId, draft);
        Long taskId = saved == null || saved.getSaleReviewProgress() == null ? null
                : saved.getSaleReviewProgress().getTaskId();
        if (taskId == null) {
            throw new IllegalStateException(saved == null || saved.getSaleReviewProgress() == null
                    ? "开售参数未能提交" : saved.getSaleReviewProgress().getNextStep());
        }

        // ④ 作者发布 = 开售审核通过(留痕),然后开售终检。
        auditTaskService.approveSaleReviewForAuthor(taskId, topicId, authorMemberId);
        Date sellingAt = finalizationService.finalizeForSale(topicId);
        ICoopInviteService invites = coopInviteService.getIfAvailable();
        if (invites != null) invites.markTopicDepositsDue(topicId);
        writeAudit(topic, authorMemberId, taskId, ticketPrices);
        return sellingAt == null ? 0L : sellingAt.getTime();
    }

    private void syncAllSessionTickets(Long topicId) {
        CmsActivity query = new CmsActivity();
        query.setTopicId(topicId);
        query.setDelFlag(0);
        List<CmsActivity> sessions = activityMapper.selectCmsActivityList(query);
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalStateException("主题还没有场次，不能发布开售");
        }
        for (CmsActivity session : sessions) {
            if (session == null || session.getId() == null) continue;
            omsTicketService.syncTopicTicketsToActivity(topicId, session.getId(), session.getMemberId());
        }
    }

    private void assertCoversTopicTickets(Long topicId, Map<Long, BigDecimal> ticketPrices) {
        Set<Long> ids = new HashSet<Long>();
        for (OmsTicket t : topicTickets(topicId)) ids.add(t.getId());
        if (ids.isEmpty() || !ids.equals(ticketPrices.keySet())) {
            throw new IllegalStateException("票种已变化，请刷新后重新填写票价");
        }
    }

    private long totalInventory(Long topicId) {
        long total = 0L;
        for (OmsTicket t : topicTickets(topicId)) {
            if (t.getTotalInventory() != null) total += t.getTotalInventory();
        }
        return total;
    }

    private List<OmsTicket> topicTickets(Long topicId) {
        OmsTicket q = new OmsTicket();
        q.setOwnerType(1L);
        q.setOwnerId(topicId);
        q.setDelFlag(0);
        List<OmsTicket> rows = ticketMapper.selectOmsTicketList(q);
        if (rows == null) return Collections.emptyList();
        List<OmsTicket> out = new ArrayList<OmsTicket>();
        for (OmsTicket t : rows) if (t != null && t.getId() != null) out.add(t);
        return out;
    }

    private boolean hasOpenSaleReview(Long topicId) {
        AuditTask q = new AuditTask();
        q.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        q.setBizId(topicId);
        List<AuditTask> tasks = auditTaskMapper.selectAuditTaskList(q);
        if (tasks == null) return false;
        for (AuditTask t : tasks) {
            if (t != null && AuditTask.STATUS_PENDING.equals(t.getStatus())) return true;
        }
        return false;
    }

    private void writeAudit(CmsTopic topic, Long authorMemberId, Long taskId, Map<Long, BigDecimal> prices) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("TOPIC");
        audit.setDomainId(topic.getId());
        audit.setOwnerMemberId(topic.getMemberId());
        audit.setActorType(BusinessAuditEvent.ACTOR_TYPE_MEMBER);
        audit.setActorMemberId(authorMemberId);
        audit.setActorRoleCode("TOPIC_AUTHOR");
        audit.setAction(AUDIT_ACTION);
        audit.setTargetType("AUDIT_TASK");
        audit.setTargetId(taskId);
        audit.setBeforeJson("{\"lifecycle\":" + TopicLifecycleState.PRICING.getCode() + "}");
        StringBuilder after = new StringBuilder("{\"lifecycle\":").append(TopicLifecycleState.SELLING.getCode())
                .append(",\"source\":\"AUTHOR_FE\",\"ticketPrices\":{");
        boolean first = true;
        for (Map.Entry<Long, BigDecimal> e : new java.util.TreeMap<Long, BigDecimal>(prices).entrySet()) {
            if (!first) after.append(',');
            after.append('"').append(e.getKey()).append("\":\"").append(e.getValue().toPlainString()).append('"');
            first = false;
        }
        audit.setAfterJson(after.append("}}").toString());
        audit.setReason("自由探索作者填价后直接发布开售");
        audit.setRequestId("author-fe-sale:" + topic.getId());
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("开售审计写入失败");
        }
    }

    private static void assertAuthorTopic(CmsTopic topic, Long authorMemberId) {
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalStateException("主题不存在");
        }
        if (authorMemberId == null || !authorMemberId.equals(topic.getMemberId())) {
            throw new IllegalStateException("只有主题作者可以填价发布");
        }
        if (!Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            throw new IllegalStateException("只有自由探索主题支持在编辑器里填价发布");
        }
    }

    private static boolean isState(CmsTopic topic, TopicLifecycleState state) {
        return Integer.valueOf(state.getCode()).equals(topic.getLifecycle());
    }
}

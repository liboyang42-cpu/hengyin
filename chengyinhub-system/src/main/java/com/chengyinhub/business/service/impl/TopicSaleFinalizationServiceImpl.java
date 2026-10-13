package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.IClubCorporatePayeeService;
import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.ITopicSaleFinalizationService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.FulfillmentWindowResolver;
import com.chengyinhub.business.service.support.ApprovedSaleCanonical;
import com.chengyinhub.business.service.support.ApprovedSaleCanonicalSource;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
import com.chengyinhub.business.service.support.TopicSaleTimeValidator;
import com.chengyinhub.business.service.support.TopicSaleCanonicalBuilder;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.constant.RoleEntityType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.Objects;

/** 合同二唯一开售终检；任何缺失的合同快照或 W2 pending 终结 seam 均从严拒绝。 */
@Service
public class TopicSaleFinalizationServiceImpl implements ITopicSaleFinalizationService {

    @Autowired private com.chengyinhub.business.service.support.PureContentExploreService pureContentExploreService;

    /** A1 已拍板：活动结束后 T+7 收齐证据。 */
    private static final long EVIDENCE_DUE_DAYS = 7L;

    private final CmsTopicMapper topicMapper;
    private final OmsTicketMapper ticketMapper;
    private final CmsTopicChapterMapper chapterMapper;
    private final MerchantChapterOfferMapper offerMapper;
    private final CoopOrderLineupSource coopOrderLineupSource;
    private final ObjectProvider<PendingRecruitWorkflowTerminator> pendingTerminatorProvider;
    private final CommercialTopicPolicy commercialTopicPolicy;
    private final CommercialReviewStatusSource reviewStatusSource;
    private final MerchantPublishedTopicTransferGate transferGate;
    @Autowired
    private TopicSaleSnapshotMapper saleSnapshotMapper;
    @Autowired
    private TopicSaleCanonicalBuilder canonicalBuilder;
    @Autowired
    private ApprovedSaleCanonicalSource approvedCanonicalSource;
    @Autowired
    private CmsActivityMapper activityMapper;
    @Autowired
    private IProductRoleService productRoleService;
    /** A4 开售闸。★必填注入：缺 bean 时 Spring 起不来，绝不允许「没配就跳过冻结」这种 fail-open。 */
    @Autowired
    private IClubCorporatePayeeService clubPayeeService;
    /** A1/A2 开售闸：补偿条款与收款人快照必须在同一次开售事务里冻结。同样必填注入。 */
    @Autowired
    private IClubEditionCompensationService compensationService;
    private Clock clock = Clock.systemUTC();

    public TopicSaleFinalizationServiceImpl(CmsTopicMapper topicMapper, OmsTicketMapper ticketMapper,
                                            CmsTopicChapterMapper chapterMapper,
                                            MerchantChapterOfferMapper offerMapper,
                                            CoopOrderLineupSource coopOrderLineupSource,
                                            ObjectProvider<PendingRecruitWorkflowTerminator> pendingTerminatorProvider,
                                            CommercialTopicPolicy commercialTopicPolicy,
                                            CommercialReviewStatusSource reviewStatusSource,
                                            MerchantPublishedTopicTransferGate transferGate) {
        this.topicMapper = topicMapper;
        this.ticketMapper = ticketMapper;
        this.chapterMapper = chapterMapper;
        this.offerMapper = offerMapper;
        this.coopOrderLineupSource = coopOrderLineupSource;
        this.pendingTerminatorProvider = pendingTerminatorProvider;
        this.commercialTopicPolicy = commercialTopicPolicy;
        this.reviewStatusSource = reviewStatusSource;
        this.transferGate = transferGate;
    }

    /** 测试/部署可注入固定 Clock；生产未配置时使用 UTC 绝对时刻。 */
    public void setClock(Clock clock) {
        if (clock != null) this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Date finalizeForSale(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("topicId 不能为空");
        Date finalizeNow = Date.from(clock.instant());
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalStateException("主题不存在");
        }
        if (Integer.valueOf(TopicLifecycleState.SELLING.getCode()).equals(topic.getLifecycle())) {
            if (topic.getSellingStartedAt() == null) {
                throw new IllegalStateException("SELLING 主题缺 sellingStartedAt，拒绝幂等重试");
            }
            if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
                requirePersistedCanonical15(topicId, topic.getProductType());
            }
            return topic.getSellingStartedAt();
        }
        if (!Integer.valueOf(TopicLifecycleState.PRICING.getCode()).equals(topic.getLifecycle())) {
            throw new IllegalStateException("主题必须处于 PRICING 才能 finalizeForSale");
        }
        if (topic.getSellingStartedAt() != null) {
            throw new IllegalStateException("PRICING 主题已有 sellingStartedAt，拒绝覆盖开售时刻");
        }
        boolean canonicalExplore = Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType());
        if (canonicalExplore) {
            reviewStatusSource.assertSaleApproved(topicId);
        }

        PendingRecruitWorkflowTerminator terminator = pendingTerminatorProvider.getIfAvailable();
        if (terminator == null) {
            throw new IllegalStateException("pending 工作流终结 seam 尚未由 W2 接入，拒绝开售");
        }
        terminator.terminatePendingUnderTopicLock(topicId, finalizeNow);

        List<OmsTicket> tickets = activeTickets(topicId);
        TopicSaleTimeValidator.assertValid(topic, tickets, finalizeNow);
        List<MerchantChapterOffer> offers = validOffers(topicId, finalizeNow);
        assertRequiredChapterCoverage(topicId, offers);

        CoopOrderLineupSource.ReadResult orderLineup = coopOrderLineupSource.readEffectiveLineup(topicId);
        if (!orderLineup.getRepairReasons().isEmpty()) {
            throw new IllegalStateException("合作单缺冻结快照，进入人工修复清单: " + orderLineup.getRepairReasons().get(0));
        }
        transferGate.assertTransferred(topic, orderLineup);
        assertNoDuplicateLineup(offers, orderLineup.getEntries());
        assertOrganizer(topic, orderLineup.getEntries());
        assertFrozenCommercialFacts(topic, tickets, offers);

        if (canonicalExplore) {
            assertActivityTicketProjection(topicId, tickets);
            ApprovedSaleCanonical approved = approvedCanonicalSource.read(topicId, topic.getProductType());
            if (approved.getCanonical().requiresFourStoreContract()) {
                assertShopDayTermsArePerk(topicId, offers);
                // 主办方角色槽仍是开售闸（平台主体、声明人非空），只是不再兼任 EXTRA 审批人。
                ProductRole organizerActor = assertShopDayOrganizerActor(topicId);
                freezeCanonical15(topic, finalizeNow);
                // 唯一执行俱乐部只解析一次，A4 快照与 A1/A2 条款必须绑同一个 club。
                Long executingClub = resolveExecutingClub(orderLineup.getEntries());
                // E2 资格槽与 terms 用同一个 executingClub，并在同一开售事务内落库、读回。
                // declaredBy 复用已经验证过的 ORGANIZER 操作账号，只作本次自动冻结的审计 actor。
                productRoleService.declareDistributionSlotForSale(
                        topicId, executingClub, organizerActor.getDeclaredBy());
                // A4 开售闸：唯一执行俱乐部的对公收款人必须已审核，且在同一事务里冻结成不可变快照。
                // 放在 PRICING→SELLING CAS 之前 ⇒ 任一失败整体回滚，绝不进入 SELLING。
                clubPayeeService.snapshotForSale(topicId, executingClub, finalizeNow);
                // A1/A2 开售闸：40 元费率、每维 200、证据截止一并冻结。
                // ★没有这一脚，club_edition_compensation_terms 永远空表 ⇒ Q/EXTRA 的六个写入口与 FINAL
                //   全部抛 EDITION_TERMS_MISSING，「开售后计划工时冻结」那道闸也永不通电。
                // 履约窗结束与审批人一并冻进条款：总纲 §2.1.2 要求「超范围劳动的小时单价和审批人必须在
                // 开售快照中冻结」。★审批人由 freezeTermsForSale 自己从当期成本版本上<b>显式登记的项目负责人</b>
                // 读取并做可达校验 —— 不再复用 ORGANIZER 槽的 declared_by（那是「谁点了那个按钮」的审计字段，
                // 契约明写「不参与主体归属判断」，幂等重试还保留最初那个人）。
                compensationService.freezeTermsForSale(topicId, executingClub, finalizeNow, evidenceDueAt(topic),
                        fulfillmentEndAt(topic));
            } else {
                freezeCanonical15(topic, finalizeNow);
            }
        }
        if (topicMapper.finalizeForSale(topicId, finalizeNow, finalizeNow) != 1) {
            throw new IllegalStateException("PRICING→SELLING CAS 或 sellingStartedAt 写入失败，已整体回滚");
        }
        return finalizeNow;
    }

    /**
     * 探店日的唯一执行俱乐部 = 冻结阵容里 role=ORGANIZER 的 CLUB 主体。
     *
     * <p>0 条或多条都阻断；<b>不得</b>退回去猜 {@code CmsTopic.club_id}。注意这里的 ORGANIZER 是
     * coop_order 的 roleSnapshot（必然 toType=club），与 FREE_EXPLORE 产品角色 ORGANIZER
     * （平台角色，见 assertShopDayOrganizerActor）同名不同物，禁止混用。
     */
    private static Long resolveExecutingClub(List<CoopOrderLineupSource.LineupEntry> entries) {
        Long resolved = null;
        for (CoopOrderLineupSource.LineupEntry entry : entries) {
            if (!"CLUB".equals(entry.getSubjectType()) || !"ORGANIZER".equals(entry.getRole())) continue;
            if (entry.getSubjectId() == null) throw new IllegalStateException("SALE_EXECUTING_CLUB_UNRESOLVED");
            if (resolved != null && !resolved.equals(entry.getSubjectId())) {
                throw new IllegalStateException("SALE_EXECUTING_CLUB_AMBIGUOUS");
            }
            resolved = entry.getSubjectId();
        }
        if (resolved == null) throw new IllegalStateException("SALE_EXECUTING_CLUB_UNRESOLVED");
        return resolved;
    }

    /**
     * A1 的证据截止：本期活动结束的<b>权威</b>时刻 + 7 天。
     *
     * <p>权威时刻取 {@link FulfillmentWindowResolver} 解出的主题履约窗结束瞬间 —— 它是仓库既有的
     * 统一活动时间口径（Asia/Shanghai 日粒度，同一函数已被 {@link TopicSaleTimeValidator} 用来卡
     * saleEndAt），不是本方法自己新造的时区解释。T+7 用绝对时刻加天数，不经过任何本地时区换算。
     *
     * <p><b>缺事实即 fail closed</b>：topic.startDate/endDate 缺一，resolver 直接抛并点名字段，
     * 开售整体回滚。绝不用 {@code new Date()} 或 JVM 默认时区凑一个截止瞬间。
     */
    private static Date evidenceDueAt(CmsTopic topic) {
        return Date.from(fulfillmentEndAt(topic).toInstant().plus(EVIDENCE_DUE_DAYS, ChronoUnit.DAYS));
    }

    /**
     * 本期履约窗结束的权威瞬间，同样走 {@link FulfillmentWindowResolver}，缺 startDate/endDate 即抛。
     *
     * <p>冻进条款给两处用：EXTRA 事前审批的上界、FINAL 的「期已结束」事实门。
     * 冻下来而不是每次回算 topic —— topic 的日期在开售之后仍然可被编辑。
     */
    private static Date fulfillmentEndAt(CmsTopic topic) {
        return FulfillmentWindowResolver.resolve(topic, null).getEndExclusive();
    }

    private ProductRole assertShopDayOrganizerActor(Long topicId) {
        ProductRole organizer = productRoleService.activeOrganizerSlot(topicId);
        if (organizer == null) {
            throw new IllegalStateException("SALE_FREE_EXPLORE_ORGANIZER_MISSING");
        }
        if (!RoleEntityType.PLATFORM.equals(organizer.getEntityType()) || organizer.getEntityId() != null) {
            throw new IllegalStateException("SALE_FREE_EXPLORE_ORGANIZER_MUST_BE_PLATFORM");
        }
        if (organizer.getId() == null || organizer.getDeclaredBy() == null
                || organizer.getDeclaredBy().longValue() <= 0L || organizer.getCreatedAt() == null) {
            throw new IllegalStateException("SALE_FREE_EXPLORE_ORGANIZER_AUDIT_MISSING");
        }
        return organizer;
    }

    private void requirePersistedCanonical15(Long topicId, Integer productType) {
        TopicSaleSnapshot snapshot = saleSnapshotMapper.selectSnapshotByTopicId(topicId);
        if (snapshot == null) throw new IllegalStateException("SALE_SNAPSHOT_MISSING");
        TopicSaleCanonicalCodec.read(snapshot.getSchemaVersion(), snapshot.getCanonicalJson(), productType);
    }

    private void assertActivityTicketProjection(Long topicId, List<OmsTicket> topicTickets) {
        CmsActivity query = new CmsActivity();
        query.setTopicId(topicId);
        query.setDelFlag(0);
        List<CmsActivity> activities = activityMapper.selectCmsActivityList(query);
        if (activities == null || activities.isEmpty()) {
            throw new IllegalStateException("SALE_TICKET_PROJECTION_MISSING");
        }
        List<OmsTicket> source = new ArrayList<OmsTicket>(topicTickets);
        source.sort(Comparator.comparing(this::ticketProjectionKey));
        for (CmsActivity activity : activities) {
            if (activity == null) throw new IllegalStateException("SALE_TICKET_PROJECTION_MISSING");
            OmsTicket activityQuery = new OmsTicket();
            activityQuery.setOwnerType(2L);
            activityQuery.setOwnerId(activity.getId());
            List<OmsTicket> projected = ticketMapper.selectOmsTicketList(activityQuery);
            if (projected == null || projected.size() != source.size()) {
                throw new IllegalStateException("SALE_TICKET_PROJECTION_MISSING");
            }
            projected = new ArrayList<OmsTicket>(projected);
            projected.sort(Comparator.comparing(this::ticketProjectionKey));
            for (int i = 0; i < source.size(); i++) assertTicketProjection(source.get(i), projected.get(i));
        }
    }

    private String ticketProjectionKey(OmsTicket ticket) {
        return String.valueOf(ticket.getName()) + '|' + String.valueOf(ticket.getMode()) + '|'
                + String.valueOf(ticket.getPrice()) + '|' + String.valueOf(ticket.getTotalInventory()) + '|'
                + String.valueOf(ticket.getStartTime()) + '|' + String.valueOf(ticket.getEndTime()) + '|'
                + String.valueOf(ticket.getSignupDeadline()) + '|' + String.valueOf(ticket.getMeetingPoint()) + '|'
                + String.valueOf(ticket.getGatherLng()) + '|' + String.valueOf(ticket.getGatherLat()) + '|'
                + String.valueOf(ticket.getSubType()) + '|' + String.valueOf(ticket.getRefundRule()) + '|'
                + String.valueOf(ticket.getNeedAudit()) + '|' + String.valueOf(ticket.getTeamSize());
    }

    private void assertTicketProjection(OmsTicket source, OmsTicket projected) {
        assertTicketField("NAME", source.getName(), projected.getName());
        assertTicketField("MODE", source.getMode(), projected.getMode());
        assertTicketField("PRICE", source.getPrice(), projected.getPrice());
        assertTicketField("TOTAL_INVENTORY", source.getTotalInventory(), projected.getTotalInventory());
        assertTicketField("START_TIME", source.getStartTime(), projected.getStartTime());
        assertTicketField("END_TIME", source.getEndTime(), projected.getEndTime());
        assertTicketField("SIGNUP_DEADLINE", source.getSignupDeadline(), projected.getSignupDeadline());
        assertTicketField("MEETING_POINT", source.getMeetingPoint(), projected.getMeetingPoint());
        assertTicketField("GATHER_LNG", source.getGatherLng(), projected.getGatherLng());
        assertTicketField("GATHER_LAT", source.getGatherLat(), projected.getGatherLat());
        assertTicketField("SUB_TYPE", source.getSubType(), projected.getSubType());
        assertTicketField("REFUND_RULE", source.getRefundRule(), projected.getRefundRule());
        assertTicketField("NEED_AUDIT", source.getNeedAudit(), projected.getNeedAudit());
        // 未设置人数和默认 0 都表示不限制成团人数，正数仍须严格一致。
        assertTicketField("TEAM_SIZE", source.getTeamSize() == null ? 0 : source.getTeamSize(),
                projected.getTeamSize() == null ? 0 : projected.getTeamSize());
    }

    private void assertTicketField(String field, Object expected, Object actual) {
        boolean equal = expected instanceof java.math.BigDecimal && actual instanceof java.math.BigDecimal
                ? ((java.math.BigDecimal) expected).compareTo((java.math.BigDecimal) actual) == 0
                : Objects.equals(expected, actual);
        if (!equal) {
            throw new IllegalStateException("SALE_TICKET_PROJECTION_MISMATCH_" + field);
        }
    }

    private void freezeCanonical15(CmsTopic topic, Date frozenAt) {
        if (saleSnapshotMapper.selectSnapshotByTopicId(topic.getId()) != null) {
            throw new IllegalStateException("SALE_SNAPSHOT_DUPLICATE");
        }
        ApprovedSaleCanonical approved = approvedCanonicalSource.read(topic.getId(), topic.getProductType());
        TopicSaleCanonicalDraft draft = saleSnapshotMapper.selectDraftByTopicIdForUpdate(topic.getId());
        if (draft == null) throw new IllegalStateException("SALE_SNAPSHOT_DRAFT_MISSING");
        if (!approved.getSchemaVersion().equals(draft.getSchemaVersion())) {
            throw new IllegalStateException("SALE_SNAPSHOT_SCHEMA_VERSION_CHANGED");
        }
        if (!approved.getParameterVersion().equals(draft.getParameterVersion())) {
            throw new IllegalStateException("SALE_SNAPSHOT_PARAMETER_VERSION_CHANGED");
        }
        TopicSaleCanonicalV1 current = canonicalBuilder.rebuild(topic,
                TopicSaleCanonicalCodec.readDraft(draft.getSchemaVersion(), draft.getCanonicalJson()));
        TopicSaleCanonicalCodec.assertFieldsEqual(approved.getCanonical(), current,
                "SALE_SNAPSHOT_REVIEW_DRIFT:", topic.getProductType());

        TopicSaleSnapshot write = new TopicSaleSnapshot();
        write.setTopicId(topic.getId());
        write.setSchemaVersion(approved.getSchemaVersion());
        write.setParameterVersion(approved.getParameterVersion());
        write.setSourceReviewTaskId(approved.getReviewTaskId());
        write.setCanonicalJson(TopicSaleCanonicalCodec.write(current, topic.getProductType()));
        write.setFrozenAt(frozenAt);
        if (saleSnapshotMapper.insertSnapshot(write) != 1) {
            throw new IllegalStateException("SALE_SNAPSHOT_INSERT_FAILED");
        }
        TopicSaleSnapshot readback = saleSnapshotMapper.selectSnapshotByTopicId(topic.getId());
        if (readback == null
                || !approved.getSchemaVersion().equals(readback.getSchemaVersion())
                || !approved.getParameterVersion().equals(readback.getParameterVersion())
                || !approved.getReviewTaskId().equals(readback.getSourceReviewTaskId())) {
            throw new IllegalStateException("SALE_SNAPSHOT_READBACK_METADATA_MISMATCH");
        }
        TopicSaleCanonicalV1 persisted = TopicSaleCanonicalCodec.read(
                readback.getSchemaVersion(), readback.getCanonicalJson(), topic.getProductType());
        TopicSaleCanonicalCodec.assertFieldsEqual(current, persisted,
                "SALE_SNAPSHOT_READBACK_MISMATCH:", topic.getProductType());
    }

    private List<OmsTicket> activeTickets(Long topicId) {
        OmsTicket query = new OmsTicket();
        query.setOwnerType(1L);
        query.setOwnerId(topicId);
        List<OmsTicket> tickets = ticketMapper.selectOmsTicketList(query);
        return tickets == null ? Collections.<OmsTicket>emptyList() : tickets;
    }

    private List<MerchantChapterOffer> validOffers(Long topicId, Date now) {
        List<MerchantChapterOffer> candidates = offerMapper.selectByTopic(topicId);
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        List<MerchantChapterOffer> valid = new ArrayList<MerchantChapterOffer>();
        for (MerchantChapterOffer offer : candidates) {
            if (offer != null && topicId.equals(offer.getTopicId()) && Integer.valueOf(1).equals(offer.getStatus())
                    && (offer.getValidEnd() == null || offer.getValidEnd().after(now))) {
                if (offer.getChapterId() == null || offer.getMerchantId() == null || offer.getTermsMode() == null) {
                    throw new IllegalStateException("merchant_chapter_offer 缺 chapter/merchant/terms 快照，进入人工修复清单");
                }
                valid.add(offer);
            }
        }
        return valid;
    }

    private void assertRequiredChapterCoverage(Long topicId, List<MerchantChapterOffer> offers) {
        if (pureContentExploreService != null && pureContentExploreService.isPureContent(topicId)) return;
        List<CmsTopicChapter> required = chapterMapper.selectRequiredByTopic(topicId);
        if (required == null) return;
        for (CmsTopicChapter chapter : required) {
            boolean covered = false;
            for (MerchantChapterOffer offer : offers) {
                if (chapter != null && chapter.getId() != null && chapter.getId().equals(offer.getChapterId())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                throw new IllegalStateException("必选章节 chapterId=" + (chapter == null ? null : chapter.getId())
                        + " 缺有效 SUPPLY 供给");
            }
        }
    }

    private void assertShopDayTermsArePerk(Long topicId, List<MerchantChapterOffer> offers) {
        List<CmsTopicChapter> required = chapterMapper.selectRequiredByTopic(topicId);
        if (required != null) {
            for (CmsTopicChapter chapter : required) {
                if (chapter == null || !TermsMode.PERK.equals(chapter.getTermsMode())) {
                    throw new IllegalStateException("SALE_FREE_EXPLORE_TERMS_NOT_PERK");
                }
            }
        }
        for (MerchantChapterOffer offer : offers) {
            if (!TermsMode.PERK.equals(offer.getTermsMode())) {
                throw new IllegalStateException("SALE_FREE_EXPLORE_TERMS_NOT_PERK");
            }
        }
    }

    private void assertNoDuplicateLineup(List<MerchantChapterOffer> offers,
                                         List<CoopOrderLineupSource.LineupEntry> orders) {
        Set<String> keys = new HashSet<String>();
        for (MerchantChapterOffer offer : offers) {
            String key = "SUPPLY:CHAPTER:" + offer.getChapterId() + ":" + offer.getMerchantId();
            if (!keys.add(key)) throw new IllegalStateException("重复 SUPPLY 承接: " + key);
        }
        for (CoopOrderLineupSource.LineupEntry entry : orders) {
            String key = entry.getRole() + ':' + entry.getScopeType() + ':' + entry.getScopeId()
                    + ':' + entry.getSubjectType() + ':' + entry.getSubjectId();
            if (!keys.add(key)) throw new IllegalStateException("重复 role/scope 承接: " + key);
        }
    }

    private void assertOrganizer(CmsTopic topic, List<CoopOrderLineupSource.LineupEntry> orders) {
        int organizers = 0;
        for (CoopOrderLineupSource.LineupEntry entry : orders) {
            if ("ORGANIZER".equals(entry.getRole())) organizers++;
        }
        boolean guided = Integer.valueOf(ProductType.ORIENTEERING).equals(topic.getProductType());
        if (guided && organizers != 1) {
            throw new IllegalStateException("GUIDED_TICKET 必须恰好一个 ORGANIZER，当前=" + organizers);
        }
        if (!guided && organizers > 1) {
            throw new IllegalStateException("FREE_EXPLORE ORGANIZER 至多一个，当前=" + organizers);
        }
    }

    private void assertFrozenCommercialFacts(CmsTopic topic, List<OmsTicket> tickets,
                                              List<MerchantChapterOffer> offers) {
        if (topic.getFeePlatformRate() == null) {
            for (OmsTicket ticket : tickets) {
                if (ticket.getPrice() != null && ticket.getPrice().signum() > 0) {
                    throw new IllegalStateException("主题费率快照缺失，拒绝开售");
                }
            }
        }
        for (MerchantChapterOffer offer : offers) {
            if (TermsMode.REVSHARE.equals(offer.getTermsMode())
                    && (offer.getPerHeadFee() == null || offer.getPerHeadFee().signum() < 0)) {
                throw new IllegalStateException("REVSHARE offer 缺固定费快照，拒绝开售");
            }
        }
    }
}

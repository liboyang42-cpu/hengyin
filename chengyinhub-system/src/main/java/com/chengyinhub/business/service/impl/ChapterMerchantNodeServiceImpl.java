package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
public class ChapterMerchantNodeServiceImpl implements IChapterMerchantNodeService {

    /** 审核态。NULL 保留给主办方自建节点(不走审核)。 */
    public static final int AUDIT_PENDING = 0;
    public static final int AUDIT_APPROVED = 1;
    public static final int AUDIT_REJECTED = 2;

    private final ICmsTopicNodeService nodeService;
    private final ICmsTopicChapterService chapterService;
    private final ICmsTopicService topicService;
    private final ICmsMemberTemplateService templateService;
    private final MerchantChapterOfferMapper offerMapper;
    private final MerchantChapterApplicationMapper applicationMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.TopicSupplyReviewService supplyReviewService;

    @Autowired
    private CmsTopicChapterMapper chapterMapper;
    @Autowired
    private CmsTopicMapper topicMapper;
    @Autowired
    private com.chengyinhub.business.mapper.RegistrationEntitlementMapper registrationEntitlementMapper;
    @Autowired
    private com.chengyinhub.business.service.support.CommittedBusinessNotice businessNotice;
    @Autowired
    private com.chengyinhub.business.service.INotifyService notifyService;

    public ChapterMerchantNodeServiceImpl(ICmsTopicNodeService nodeService,
                                          ICmsTopicChapterService chapterService,
                                          ICmsTopicService topicService,
                                          ICmsMemberTemplateService templateService,
                                          MerchantChapterOfferMapper offerMapper,
                                          MerchantChapterApplicationMapper applicationMapper) {
        this.nodeService = nodeService;
        this.chapterService = chapterService;
        this.topicService = topicService;
        this.templateService = templateService;
        this.offerMapper = offerMapper;
        this.applicationMapper = applicationMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CmsTopicNode submit(Long merchantMemberId, Long chapterId, CmsTopicNode draft) {
        if (merchantMemberId == null || merchantMemberId <= 0) throw new IllegalStateException("请先登录");
        if (chapterId == null || chapterId <= 0) throw new IllegalStateException("缺少章节");
        if (draft == null || blank(draft.getName())) throw new IllegalStateException("请填写点位名称");
        assertLocation(draft);

        // 与原承接申请共用 chapter -> topic 锁序；锁价后不能用旧快照继续改点位。
        supplyReviewService.lockBeforeChapter(chapterId);
        List<Long> lockedChapters = chapterMapper.lockChaptersForCapacity(Collections.singletonList(chapterId));
        if (lockedChapters == null || lockedChapters.size() != 1) throw new IllegalStateException("章节不存在");
        CmsTopicChapter chapter = chapterService.selectCmsTopicChapterById(chapterId);
        if (chapter == null || chapter.getTopicId() == null) throw new IllegalStateException("章节不存在");
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(chapter.getTopicId());
        if (topic == null) throw new IllegalStateException("主题不存在");

        // 闸①:必须在这一章的承接链路里 —— 有申请(待审/已批准)或已有生效供给。
        // 只认「生效供给」是不行的:2026-08-06 用户指出**报名的时候就该填点位**,
        // 那时候还没有 offer。放宽到「在链路里」不会放水 —— 未过审的点位对玩家不可见(见 mapper 可见性闸)。
        MerchantChapterApplication application =
                applicationMapper.selectByChapterAndMerchantForUpdate(chapterId, merchantMemberId);
        MerchantChapterOffer offer = liveOfferOf(chapterId, merchantMemberId);
        if (offer == null && !inPipeline(application)) {
            throw new IllegalStateException("请先申请承接该章节");
        }

        boolean replacement = application != null && application.getReplacementOfOfferId() != null
                && Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED).equals(application.getStatus());
        MerchantChapterOffer replacementOffer = replacement
                ? offerMapper.selectReplacementForUpdate(application.getReplacementOfOfferId()) : null;
        if (replacement) {
            com.chengyinhub.business.support.OrdinarySupplyReplacementPolicy.assertOpen(topic);
            if (replacementOffer != null && (!merchantMemberId.equals(replacementOffer.getMerchantId())
                    || !Integer.valueOf(0).equals(replacementOffer.getStatus())
                    || !blank(replacementOffer.getWithdrawReason()))) {
                throw new IllegalStateException("补位供给已审核或退出，不能改点位");
            }
            offer = offerMapper.selectByIdForUpdate(application.getReplacementOfOfferId());
            if (offer == null || !Integer.valueOf(0).equals(offer.getStatus())
                    || blank(offer.getWithdrawReason()) || !chapterId.equals(offer.getChapterId())
                    || !topic.getId().equals(offer.getTopicId())) {
                throw new IllegalStateException("原退出供给已变化，请刷新后补位");
            }
        }
        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("主题生命周期未知或缺失，点位配置只读，请刷新后重试");
        }
        if (!replacement && TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())) {
            throw new IllegalStateException("主题已锁价，点位配置只读");
        }
        if (!replacement && lifecycle != TopicLifecycleState.RECRUITING && lifecycle != TopicLifecycleState.PENDING_REVIEW) {
            throw new IllegalStateException("主题不在招商阶段，点位配置只读");
        }

        // 闸②:主题一开跑就冻结。玩家已经照着当前内容买了票、到了店。
        if (!replacement && hasStarted(topic)) throw new IllegalStateException("主题已开始,不能再改点位");

        // 闸③:模板必须是自己的。否则可以引用别人的玩法冒充。
        CmsMemberTemplate tpl = requireOwnTemplate(draft.getTemplateId(), merchantMemberId);

        // 闸④:玩法不得超出条款。
        // 有 offer ⇒ 用**承接时冻结的那份**(主办方事后改章节不该让已承接商家突然违规);
        // 还没 offer(报名阶段)⇒ 用章节当下公示的那份 —— 商家申请前就看得到的正是它。
        assertWithinTerms(termsOf(offer, chapter), tpl, draft);

        CmsTopicNode existing = findMine(chapter.getTopicId(), chapterId, merchantMemberId);
        CmsTopicNode toSave = existing == null ? new CmsTopicNode() : existing;

        // 白名单:只接受内容字段。归属、审核态、所属章节/主题一律服务端定,
        // 客户端传了也不算 —— 这三样是权限本身。
        toSave.setTopicId(chapter.getTopicId());
        toSave.setChapterId(chapterId);
        toSave.setMerchantMemberId(merchantMemberId);
        toSave.setName(draft.getName().trim());
        toSave.setDescription(draft.getDescription());
        toSave.setAddress(draft.getAddress());
        toSave.setLongitude(draft.getLongitude());
        toSave.setLatitude(draft.getLatitude());
        toSave.setImgUrl(draft.getImgUrl());
        toSave.setBusinessTime(draft.getBusinessTime());
        toSave.setTemplateId(draft.getTemplateId());
        if (draft.getXpValue() != null) {
            toSave.setXpValue(draft.getXpValue());
        }
        // 邀请来的免审:人是主办方主动邀的,再让他审自己邀的人没有意义。
        // 商家自己申请的则随申请一起审 —— 主办方看着实际玩法决定要不要这家。
        boolean invited = !replacement && application != null
                && Integer.valueOf(MerchantChapterApplication.SOURCE_INVITE).equals(application.getSource());
        // 改完回到待审:改一版就要重审一版,否则过审后随便换内容 = 绕过审核。
        toSave.setNodeAuditStatus(invited ? AUDIT_APPROVED : AUDIT_PENDING);
        toSave.setNodeAuditReason("");
        // ⚠️ 这两笔用 0 / 纪元时间当「已清空」,不能传 null ——
        // mapper 的 <if test="xxx != null"> 会把 null 整个跳过,旧审核人和旧时间会留在库里,
        // 表现成「这版新内容是上一任审核人批的」。清痕迹必须写得进去才算清。
        toSave.setNodeAuditMemberId(invited ? topic.getMemberId() : 0L);
        toSave.setNodeAuditTime(invited ? new Date() : new Date(0L));
        toSave.setUpdateTime(new Date());

        if (existing == null) {
            toSave.setStatus(1L);
            toSave.setCreateTime(new Date());
            nodeService.insertCmsTopicNode(toSave);
        } else {
            nodeService.updateCmsTopicNode(toSave);
        }
        return toSave;
    }

    @Override
    public List<CmsTopicNode> listMine(Long merchantMemberId, Long topicId) {
        if (merchantMemberId == null || merchantMemberId <= 0) return new ArrayList<>();
        CmsTopicNode probe = new CmsTopicNode();
        probe.setMerchantMemberId(merchantMemberId);
        if (topicId != null && topicId > 0) probe.setTopicId(topicId);
        List<CmsTopicNode> list = nodeService.selectCmsTopicNodeList(probe);
        return list == null ? new ArrayList<>() : list;
    }

    @Override
    public CmsTopicNode requireOwnNode(Long merchantMemberId, Long nodeId) {
        if (merchantMemberId == null || merchantMemberId <= 0) throw new IllegalStateException("请先登录");
        if (nodeId == null || nodeId <= 0) throw new IllegalStateException("参数有误");
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
        // 归属闸。不存在 / 不归本人 / 主办方自建(merchant_member_id 为 NULL)合并成同一句文案:
        // 分开写等于给出一个可枚举 nodeId 的探针(与 posterCodeNode 同口径)。
        if (node == null || node.getMerchantMemberId() == null
                || !node.getMerchantMemberId().equals(merchantMemberId)) {
            throw new IllegalStateException("点位不存在或无权操作");
        }
        // 承接闸:编辑权跟着**生效**的承接走。归属对但已退出/已过期,就不该再动这条点位。
        if (node.getChapterId() == null) {
            throw new IllegalStateException("点位没有挂章节，无法确认承接");
        }
        if (liveOfferOf(node.getChapterId(), merchantMemberId) == null) {
            throw new IllegalStateException("承接已失效或未生效，不能编辑节点内容");
        }
        // 只到这里为止 —— 冻结闸(主题是否已开始)是「写」的闸,读路径不吃它。
        return node;
    }

    @Override
    public CmsTopicNode requireOwnLiveNode(Long merchantMemberId, Long nodeId) {
        CmsTopicNode node = requireOwnNode(merchantMemberId, nodeId);
        CmsTopic topic = node.getTopicId() == null ? null : topicService.selectCmsTopicById(node.getTopicId());
        if (topic == null) throw new IllegalStateException("主题不存在");
        // 冻结闸:与点位内容同一口径 —— 主题一开跑就不再改内容。
        if (hasStarted(topic)) throw new IllegalStateException("主题已开始,不能再改节点内容");
        return node;
    }

    @Override
    public CmsTopicNode posterCodeNode(Long merchantMemberId, Long nodeId) {
        if (merchantMemberId == null || merchantMemberId <= 0) throw new IllegalStateException("请先登录");
        if (nodeId == null || nodeId <= 0) throw new IllegalStateException("参数有误");
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
        // 归属闸。⚠️ 不存在与不归本人合并成同一句文案:分开写等于给出一个可枚举 nodeId 的探针。
        // merchant_member_id 为 NULL 的是平台主办方节点,不属于任何商家 ⇒ 一并落到这条。
        if (node == null || node.getMerchantMemberId() == null
                || !node.getMerchantMemberId().equals(merchantMemberId)) {
            throw new IllegalStateException("点位不存在或无权操作");
        }
        // 承接闸。归属对但承接已停/已过期,就不该再印码 —— 否则退出的商家还能继续把玩家截进店。
        if (liveOfferOf(node.getChapterId(), merchantMemberId) == null) {
            throw new IllegalStateException("承接已失效或未生效，不能取码");
        }
        // 过审闸。⚠️ selectCmsTopicNodeById **不带**可见性闸,而 /api/play/checkin 走的
        // selectCmsTopicNodeList 带 —— 未过审的商家节点在那边查不到。少了这一条,
        // 商家会把一个玩家扫了报「无效的打卡码」的码印出来贴满店里,且这边一路成功、零告警。
        if (!Integer.valueOf(AUDIT_APPROVED).equals(node.getNodeAuditStatus())) {
            throw new IllegalStateException("该点位还没过审，过审后再取码");
        }
        // T1 之前建的节点可能是空码。给可行动的话,别把空串印成二维码贴到店里。
        if (blank(node.getCheckinCode())) {
            throw new IllegalStateException("该点位还没有打卡码，请联系主办方");
        }
        return node;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CmsTopicNode audit(Long publisherMemberId, Long nodeId, boolean approve, String reason) {
        if (publisherMemberId == null || publisherMemberId <= 0) throw new IllegalStateException("请先登录");
        if (nodeId == null || nodeId <= 0) throw new IllegalStateException("参数有误");
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
        if (node == null) throw new IllegalStateException("点位不存在");
        // 只有商家节点走审核;主办方自己的节点不该出现在审核台上
        if (node.getMerchantMemberId() == null) throw new IllegalStateException("该点位不是商家提交的");

        supplyReviewService.lockBeforeChapter(node.getChapterId());
        if (node.getChapterId() == null || chapterMapper.lockChaptersForCapacity(
                Collections.singletonList(node.getChapterId())).isEmpty()) {
            throw new IllegalStateException("章节不存在");
        }
        CmsTopic topic = node.getTopicId() == null ? null : topicMapper.selectCmsTopicByIdForUpdate(node.getTopicId());
        // 谁发布谁审核 —— 与章节申请审核同一口径
        if (topic == null || topic.getMemberId() == null || !topic.getMemberId().equals(publisherMemberId)) {
            throw new IllegalStateException("只有主题发布者可以审核该点位");
        }
        CmsTopicNode lockedNode = nodeService.selectCmsTopicNodeById(nodeId);
        if (lockedNode == null || !java.util.Objects.equals(node.getChapterId(), lockedNode.getChapterId())
                || !java.util.Objects.equals(node.getTopicId(), lockedNode.getTopicId())
                || !java.util.Objects.equals(node.getMerchantMemberId(), lockedNode.getMerchantMemberId())) {
            throw new IllegalStateException("点位归属已变化，请刷新后重试");
        }
        node = lockedNode;
        if (!Integer.valueOf(AUDIT_PENDING).equals(node.getNodeAuditStatus())) {
            throw new IllegalStateException("只能审核待审版本，已生效点位请走退出或处置流程");
        }
        MerchantChapterApplication application = applicationMapper.selectByChapterAndMerchantForUpdate(
                node.getChapterId(), node.getMerchantMemberId());
        if (application == null || application.getReplacementOfOfferId() == null) {
            if (TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle()) || hasStarted(topic)) {
                throw new IllegalStateException("主题已锁价或开始，不能改变点位审核结论");
            }
        }
        if (liveOfferOf(node.getChapterId(), node.getMerchantMemberId()) == null && !inPipeline(application)) {
            throw new IllegalStateException("申请已撤回或不在承接链路中，不能审核点位");
        }
        if (!approve && blank(reason)) throw new IllegalStateException("驳回要填原因");
        if (approve) assertLocation(node);
        if (application != null && application.getReplacementOfOfferId() != null) {
            com.chengyinhub.business.support.OrdinarySupplyReplacementPolicy.assertOpen(topic);
            MerchantChapterOffer original = offerMapper.selectByIdForUpdate(application.getReplacementOfOfferId());
            MerchantChapterOffer confirmed = offerMapper.selectReplacementForUpdate(application.getReplacementOfOfferId());
            if (original == null || !Integer.valueOf(0).equals(original.getStatus()) || blank(original.getWithdrawReason())
                    || confirmed == null || !node.getMerchantMemberId().equals(confirmed.getMerchantId())
                    || !blank(confirmed.getWithdrawReason())) {
                throw new IllegalStateException("请先确认补位供给，再审核点位与条款");
            }
            com.chengyinhub.business.support.OrdinarySupplyReplacementPolicy.assertCompatible(original, confirmed);
            if (approve) {
                if (blank(node.getAddress()) || !validCoordinates(node.getLatitude(), node.getLongitude())) {
                    throw new IllegalStateException("补位点位须有真实地址与有效坐标");
                }
                assertWithinTerms(termsOf(confirmed, chapterService.selectCmsTopicChapterById(node.getChapterId())),
                        requireOwnTemplate(node.getTemplateId(), node.getMerchantMemberId()), node);
                if (Integer.valueOf(0).equals(confirmed.getStatus())) {
                    if (offerMapper.approveReplacement(confirmed.getId(), confirmed.getMerchantId()) != 1) {
                        throw new IllegalStateException("补位供给已过期或状态变化，请重新确认");
                    }
                    // SC-EXIT-REPLACE-LOCKED:补位供给 0→1 只发生一次,玩家通知挂在这次状态迁移上;
                    // 重放审核走不到这里(点位已非待审 / 供给已生效),不会重复通知。口径与退出通知一致。
                    notifyPlayersOfReplacement(topic, node.getChapterId(), confirmed.getId());
                }
            }
        }

        node.setNodeAuditStatus(approve ? AUDIT_APPROVED : AUDIT_REJECTED);
        node.setNodeAuditReason(approve ? "" : reason.trim());
        // ⚠️ 留痕:审核 = 准入不是背书。内容责任仍在作者(merchantMemberId),
        // 这两笔只回答「谁放行的、什么时候」,出事时能指到人。
        node.setNodeAuditMemberId(publisherMemberId);
        node.setNodeAuditTime(new Date());
        node.setUpdateTime(new Date());
        nodeService.updateCmsTopicNode(node);
        if (approve) republishPlayerRoute(topic);
        return node;
    }

    /**
     * 玩家读的是 cms_topic.published_route_graph_json,只在首次内容过审时编译一次。
     * 主题过审后才招进来的商家点位(店名/玩法/权益/分身)不重编就永远到不了玩家端 ——
     * 2026-10-29 实走六店自由探索时玩家看到的全是建期占位节点。已发布主题在点位过审后重编一次。
     */
    private void republishPlayerRoute(CmsTopic topic) {
        if (topic == null || !Integer.valueOf(1).equals(topic.getStatus())
                || topic.getPublishedConfigVersion() == null || topic.getPublishedConfigVersion() <= 0L) return;
        topicService.publishApprovedRouteSnapshot(topic.getId());
    }

    private void notifyPlayersOfReplacement(CmsTopic topic, Long chapterId, Long replacementOfferId) {
        if (businessNotice == null) return;
        businessNotice.executeAfterCommit("chapter-supply-replace-players", replacementOfferId, () -> {
            List<Long> affected = registrationEntitlementMapper.selectAffectedPaidMembers(topic.getId(), chapterId);
            boolean delivered = true;
            if (affected != null) for (Long playerId : new java.util.LinkedHashSet<>(affected)) {
                if (playerId != null && playerId > 0 && !playerId.equals(topic.getMemberId())) {
                    delivered = notifyService.notifyOfficialBroadcast(playerId, "已购章节供给有变化",
                            "主题「" + topic.getName() + "」有待核销章节已由新商家补位，请在出发前查看最新点位，如有疑问请联系主办方。") && delivered;
                }
            }
            return delivered;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int auditAlongApplication(Long chapterId, Long merchantMemberId, Long actorMemberId,
                                     boolean approve, String reason) {
        if (chapterId == null || merchantMemberId == null) return 0;
        CmsTopicNode probe = new CmsTopicNode();
        probe.setChapterId(chapterId);
        probe.setMerchantMemberId(merchantMemberId);
        List<CmsTopicNode> mine = nodeService.selectCmsTopicNodeList(probe);
        if (mine == null || mine.isEmpty()) return 0;   // 没填点位也能申请,不是错误
        if (approve) {
            for (CmsTopicNode node : mine) assertLocation(node);
        }
        Date now = new Date();
        int n = 0;
        for (CmsTopicNode node : mine) {
            node.setNodeAuditStatus(approve ? AUDIT_APPROVED : AUDIT_REJECTED);
            node.setNodeAuditReason(approve ? "" : (reason == null ? "" : reason.trim()));
            node.setNodeAuditMemberId(actorMemberId);
            node.setNodeAuditTime(now);
            node.setUpdateTime(now);
            nodeService.updateCmsTopicNode(node);
            n++;
        }
        if (approve && n > 0) republishPlayerRoute(topicMapper.selectCmsTopicByIdForUpdate(mine.get(0).getTopicId()));
        return n;
    }

    private static void assertLocation(CmsTopicNode node) {
        if (blank(node.getAddress())) throw new IllegalStateException("请填写到店地址并重新地图选点");
        if (!validCoordinates(node.getLatitude(), node.getLongitude())) {
            throw new IllegalStateException("请在地图选择有效的到店坐标");
        }
    }

    private static boolean validCoordinates(String latitude, String longitude) {
        try {
            double lat = Double.parseDouble(latitude), lng = Double.parseDouble(longitude);
            return Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
        } catch (NullPointerException | NumberFormatException e) {
            return false;
        }
    }

    // ===== 内部 =====

    private MerchantChapterOffer liveOfferOf(Long chapterId, Long merchantMemberId) {
        List<MerchantChapterOffer> list = offerMapper.selectByChapter(chapterId);
        if (list == null) return null;
        Date now = new Date();
        for (MerchantChapterOffer o : list) {
            if (!merchantMemberId.equals(o.getMerchantId())) continue;
            if (o.getStatus() == null || o.getStatus() != 1) continue;
            // 生效是两条轴:status=1 且未过期。valid_end 到期不会自动翻 status
            // (没有任务在做这件事),只判 status 会把过期供给算成生效。
            if (o.getValidEnd() != null && !o.getValidEnd().after(now)) continue;
            return o;
        }
        return null;
    }

    private CmsTopicNode findMine(Long topicId, Long chapterId, Long merchantMemberId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setChapterId(chapterId);
        probe.setMerchantMemberId(merchantMemberId);
        List<CmsTopicNode> list = nodeService.selectCmsTopicNodeList(probe);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private CmsMemberTemplate requireOwnTemplate(Long templateId, Long merchantMemberId) {
        if (templateId == null || templateId <= 0) throw new IllegalStateException("请选择玩法模板");
        CmsMemberTemplate tpl = templateService.selectCmsMemberTemplateById(templateId);
        if (tpl == null || tpl.getMemberId() == null || !tpl.getMemberId().equals(merchantMemberId)) {
            throw new IllegalStateException("玩法模板不存在或不属于你");
        }
        return tpl;
    }

    private boolean inPipeline(MerchantChapterApplication a) {
        if (a == null || a.getStatus() == null) return false;
        return a.getStatus() == MerchantChapterApplication.STATUS_PENDING
                || a.getStatus() == MerchantChapterApplication.STATUS_APPROVED;
    }

    /**
     * 取生效条款:优先 offer 冻结的那份,**逐项回落**章节公示的那份。
     *
     * ⚠️ 早先写成「有 offer 就整份用 offer 的」是个 fail-open:承接时若没冻上
     * (存量 offer、或冻结那一步漏了),offer 侧两列是 NULL ⇒ 有 offer 的商家反而
     * 完全不受限,比报名阶段还松。逐项回落让「没冻上」退化成「按章节现行条款」,
     * 而不是退化成「无限制」。
     */
    private String[] termsOf(MerchantChapterOffer offer, CmsTopicChapter chapter) {
        String allowed = offer == null ? null : offer.getAllowedValidationMethods();
        if (blank(allowed)) allowed = chapter.getAllowedValidationMethods();
        Integer cap = offer == null ? null : offer.getMaxNodeXp();
        if (cap == null) cap = chapter.getMaxNodeXp();
        return new String[] { allowed, cap == null ? null : String.valueOf(cap) };
    }

    /**
     * 按条款校验玩法。
     * NULL 一律解释为「不限」—— 存量数据没有这两列,不能因为本次改造把它们判成违规。
     */
    private void assertWithinTerms(String[] terms, CmsMemberTemplate tpl, CmsTopicNode draft) {
        String allowed = terms[0];
        Integer cap = terms[1] == null ? null : Integer.valueOf(terms[1]);
        if (!blank(allowed) && tpl.getValidationMethod() != null) {
            boolean hit = false;
            for (String one : allowed.split(",")) {
                if (one.trim().equals(String.valueOf(tpl.getValidationMethod()))) { hit = true; break; }
            }
            if (!hit) {
                throw new IllegalStateException("本章节承接条款不允许这种验证方式,允许的是:" + allowed);
            }
        }
        if (cap != null && draft.getXpValue() != null && draft.getXpValue() > cap) {
            // 不许自发无限奖励抢流量 —— 那会让主题的收集目标通胀失去意义
            throw new IllegalStateException("探索值超过本章节上限 " + cap);
        }
    }

    /** 未开售阶段不因计划开始时间冻结内容。 */
    private boolean hasStarted(CmsTopic topic) {
        return com.chengyinhub.common.constant.RecruitmentWindowPolicy.hasStarted(
                topic.getLifecycle(), topic.getStartDate(), new Date());
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

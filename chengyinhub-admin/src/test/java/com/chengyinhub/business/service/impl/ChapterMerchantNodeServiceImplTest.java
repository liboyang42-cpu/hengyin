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
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自由探索:商家在章节下的自有点位(B 方案)。
 *
 * 四道闸,各防一件事:
 *   ① 没生效承接就不能建点 —— 否则任何商家都能往别人主题里塞内容
 *   ② 主题一开跑冻结 —— 玩家已照着当前内容买了票、到了店
 *   ③ 模板必须是自己的 —— 否则可引用别人的玩法冒充
 *   ④ 玩法不得超出**承接时冻结的**条款 —— 不是当下的章节条款
 *
 * ★闸④为什么认冻结的那份:章节条款主办方随时可改。按 A 条款承接、主办方事后改成 B,
 * 商家已上线的节点会突然「超标」。冻结后改章节只影响后来者(与 coop_perk 快照同范式)。
 *
 * ★责任口径:审核 = 准入,不是背书。所以过审时同时记「谁放行的 + 何时」,内容责任仍在作者。
 */
class ChapterMerchantNodeServiceImplTest {

    private static final Long ME = 88L;
    private static final Long OTHER = 99L;
    private static final Long CHAPTER = 9L;
    private static final Long TOPIC = 100L;
    private static final Long TPL = 700L;
    private static final Long PUBLISHER = 5L;

    private ICmsTopicNodeService nodeSvc;
    private ICmsTopicChapterService chapterSvc;
    private ICmsTopicService topicSvc;
    private ICmsMemberTemplateService tplSvc;
    private MerchantChapterOfferMapper offerMapper;
    private MerchantChapterApplicationMapper applicationMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private ChapterMerchantNodeServiceImpl service;

    @BeforeEach
    void setUp() {
        nodeSvc = mock(ICmsTopicNodeService.class);
        chapterSvc = mock(ICmsTopicChapterService.class);
        topicSvc = mock(ICmsTopicService.class);
        tplSvc = mock(ICmsMemberTemplateService.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        applicationMapper = mock(MerchantChapterApplicationMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        service = new ChapterMerchantNodeServiceImpl(nodeSvc, chapterSvc, topicSvc, tplSvc, offerMapper, applicationMapper);
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        when(chapterSvc.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        topic.setStartDate(new Date(System.currentTimeMillis() + 5 * 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);

        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        givenTemplate(ME, 1, null);
        givenOffer(null, null);
        givenApplication(MerchantChapterApplication.STATUS_APPROVED, MerchantChapterApplication.SOURCE_APPLY);
    }

    private void givenTemplate(Long owner, Integer validationMethod, Integer ignored) {
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(TPL);
        tpl.setMemberId(owner);
        tpl.setValidationMethod(validationMethod);
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(tpl);
    }

    private void givenOffer(String allowedMethods, Integer maxXp) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setMerchantId(ME);
        o.setStatus(1);
        o.setAllowedValidationMethods(allowedMethods);
        o.setMaxNodeXp(maxXp);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.singletonList(o));
    }

    private void givenApplication(int status, int source) {
        MerchantChapterApplication a = new MerchantChapterApplication();
        a.setChapterId(CHAPTER);
        a.setMerchantId(ME);
        a.setStatus(status);
        a.setSource(source);
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, ME)).thenReturn(a);
    }

    private CmsTopicNode draft() {
        CmsTopicNode n = new CmsTopicNode();
        n.setName("幸会咖啡");
        n.setTemplateId(TPL);
        n.setAddress("星海街 8 号");
        n.setLongitude("120.7");
        n.setLatitude("31.3");
        return n;
    }

    private CmsTopicNode captureSaved() {
        ArgumentCaptor<CmsTopicNode> cap = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeSvc).insertCmsTopicNode(cap.capture());
        return cap.getValue();
    }

    @Test
    void submitRejectsBlankAddressBeforeAnyWrite() {
        CmsTopicNode node = draft();
        node.setAddress("  ");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.submit(ME, CHAPTER, node)).getMessage().contains("地址"));
        verify(nodeSvc, never()).insertCmsTopicNode(any());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
    }

    @Test
    void submitRejectsInvalidCoordinatesBeforeAnyWrite() {
        for (String longitude : new String[] {null, "", "NaN", "Infinity", "181"}) {
            CmsTopicNode node = draft();
            node.setLongitude(longitude);
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> service.submit(ME, CHAPTER, node)).getMessage().contains("坐标"));
        }
        verify(nodeSvc, never()).insertCmsTopicNode(any());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
    }

    @Test
    void approveRejectsLegacyBlankAddressButRejectionStillWorks() {
        CmsTopicNode node = merchantNode();
        node.setAddress("");
        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, 1L, true, null));
        verify(nodeSvc, never()).updateCmsTopicNode(any());
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_REJECTED),
                service.audit(PUBLISHER, 1L, false, "请重新选点").getNodeAuditStatus());
    }

    @Test
    void applicationApprovalValidatesAllAttachedNodesBeforeUpdatingAny() {
        CmsTopicNode valid = draft();
        valid.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        CmsTopicNode invalid = draft();
        invalid.setLatitude("91");
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(java.util.Arrays.asList(valid, invalid));
        assertThrows(IllegalStateException.class,
                () -> service.auditAlongApplication(CHAPTER, ME, PUBLISHER, true, null));
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_PENDING), valid.getNodeAuditStatus());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
    }

    @Test
    @Timeout(5)
    void submit_setsOwnershipAndPendingAudit() {
        service.submit(ME, CHAPTER, draft());
        CmsTopicNode saved = captureSaved();

        assertEquals(ME, saved.getMerchantMemberId(), "归属必须是服务端定的当前商家");
        assertEquals(TOPIC, saved.getTopicId(), "主题从章节反查,不信客户端");
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_PENDING), saved.getNodeAuditStatus(),
                "新提交一律待审 —— 直接可见就等于没有审核");
    }

    /**
     * ★闸①:完全不在这一章的承接链路里(没申请也没供给)⇒ 拒。
     * 否则任何商家都能往别人主题里塞节点。
     */
    @Test
    @Timeout(5)
    void notInPipeline_rejected() {
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, ME)).thenReturn(null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.submit(ME, CHAPTER, draft()));
        assertTrue(e.getMessage().contains("申请承接"), e.getMessage());
        verify(nodeSvc, never()).insertCmsTopicNode(any());
    }

    /**
     * ★2026-08-06 用户定:**报名的时候就要填点位**。那时候还没有 offer,
     * 所以「只认生效供给」是错的 —— 待审申请阶段就该能提交(点位本身待审、对玩家不可见)。
     */
    @Test
    @Timeout(5)
    void pendingApplication_canSubmitNode() {
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        givenApplication(MerchantChapterApplication.STATUS_PENDING, MerchantChapterApplication.SOURCE_APPLY);
        service.submit(ME, CHAPTER, draft());
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_PENDING),
                captureSaved().getNodeAuditStatus(), "报名阶段提交的点位仍是待审");
    }

    /** ★被驳回的申请不算在链路里 —— 否则驳回等于没驳回。 */
    @Test
    @Timeout(5)
    void rejectedApplication_cannotSubmit() {
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        givenApplication(MerchantChapterApplication.STATUS_REJECTED, MerchantChapterApplication.SOURCE_APPLY);
        assertThrows(IllegalStateException.class, () -> service.submit(ME, CHAPTER, draft()));
    }

    /**
     * ★邀请来的点位免审:人是主办方主动邀的,再让他审自己邀的人没有意义。
     * 「邀请」在库里就是一条 status=已通过 / source=邀请 的申请。
     */
    @Test
    @Timeout(5)
    void invitedMerchant_nodeAutoApproved() {
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        givenApplication(MerchantChapterApplication.STATUS_APPROVED, MerchantChapterApplication.SOURCE_INVITE);
        service.submit(ME, CHAPTER, draft());
        CmsTopicNode saved = captureSaved();
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED), saved.getNodeAuditStatus());
        assertEquals(PUBLISHER, saved.getNodeAuditMemberId(), "留痕记的是主办方 —— 是他邀的人");
    }

    /** ★闸②:主题一开跑就冻结。 */
    @Test
    @Timeout(5)
    void topicStarted_rejected() {
        CmsTopic started = new CmsTopic();
        started.setId(TOPIC);
        started.setMemberId(PUBLISHER);
        started.setLifecycle(TopicLifecycleState.RUNNING.getCode());
        started.setStartDate(new Date(System.currentTimeMillis() - 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(started);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(started);
        assertThrows(IllegalStateException.class, () -> service.submit(ME, CHAPTER, draft()));
    }

    /** ★闸③:模板必须是自己的,否则可引用别人的玩法冒充。 */
    @Test
    @Timeout(5)
    void othersTemplate_rejected() {
        givenTemplate(OTHER, 1, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.submit(ME, CHAPTER, draft()));
        assertTrue(e.getMessage().contains("不属于你"), e.getMessage());
    }

    /** ★闸④:验证方式不在冻结条款里 ⇒ 拒。 */
    @Test
    @Timeout(5)
    void validationMethodOutsideFrozenTerms_rejected() {
        givenOffer("2,4", null);          // 冻结时只允许拍照/扫码
        givenTemplate(ME, 1, null);       // 商家却用了文字暗号
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.submit(ME, CHAPTER, draft()));
        assertTrue(e.getMessage().contains("不允许"), e.getMessage());
    }

    @Test
    @Timeout(5)
    void validationMethodInsideFrozenTerms_ok() {
        givenOffer("1,3", null);
        givenTemplate(ME, 3, null);
        service.submit(ME, CHAPTER, draft());
        assertEquals(ME, captureSaved().getMerchantMemberId());
    }

    /** ★闸④:探索值超上限 ⇒ 拒。不许自发无限奖励抢流量,那会让主题的收集目标通胀。 */
    @Test
    @Timeout(5)
    void xpOverFrozenCap_rejected() {
        givenOffer(null, 50);
        CmsTopicNode d = draft();
        d.setXpValue(80);
        assertThrows(IllegalStateException.class, () -> service.submit(ME, CHAPTER, d));
    }

    /**
     * ★存量兼容:冻结条款为 NULL 一律解释为「不限」。
     * 本次改造前的 offer 没有这两列,不能因为改造把它们全判成违规。
     */
    @Test
    @Timeout(5)
    void nullFrozenTerms_meansUnlimited() {
        givenOffer(null, null);
        CmsTopicNode d = draft();
        d.setXpValue(9999);
        service.submit(ME, CHAPTER, d);
        CmsTopicNode saved = captureSaved();
        assertEquals(ME, saved.getMerchantMemberId());
        assertEquals(Integer.valueOf(9999), saved.getXpValue());
    }

    // ===== 审核 =====

    private CmsTopicNode merchantNode() {
        CmsTopicNode n = draft();
        n.setId(1L);
        n.setTopicId(TOPIC);
        n.setChapterId(CHAPTER);
        n.setMerchantMemberId(ME);
        n.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        when(nodeSvc.selectCmsTopicNodeById(1L)).thenReturn(n);
        return n;
    }

    @Test
    @Timeout(5)
    void publisherApprove_recordsAuditorAndTime() {
        merchantNode();
        CmsTopicNode out = service.audit(PUBLISHER, 1L, true, null);

        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED), out.getNodeAuditStatus());
        // 审核 = 准入不是背书:留痕只回答「谁放行的、何时」,内容责任仍在作者
        assertEquals(PUBLISHER, out.getNodeAuditMemberId());
        assertTrue(out.getNodeAuditTime() != null);
        assertEquals(ME, out.getMerchantMemberId(), "作者不能被审核改写");
    }

    /** ★只有主题发布者能审 —— 与章节申请同口径。 */
    @Test
    @Timeout(5)
    void nonPublisher_cannotAudit() {
        merchantNode();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.audit(OTHER, 1L, true, null));
        assertTrue(e.getMessage().contains("主题发布者"), e.getMessage());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
    }

    @Test
    @Timeout(5)
    void withdrawnApplication_rejectsAttachedNodeButKeepsItsContent() {
        CmsTopicNode pending = new CmsTopicNode();
        pending.setId(1L);
        pending.setChapterId(CHAPTER);
        pending.setMerchantMemberId(ME);
        pending.setName("幸会咖啡");
        pending.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(pending));

        int affected = service.auditAlongApplication(
                CHAPTER, ME, ME, false, "商家已撤回承接申请");

        ArgumentCaptor<CmsTopicNode> cap = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeSvc).updateCmsTopicNode(cap.capture());
        CmsTopicNode invalidated = cap.getValue();
        assertEquals(1, affected);
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_REJECTED),
                invalidated.getNodeAuditStatus());
        assertEquals("商家已撤回承接申请", invalidated.getNodeAuditReason());
        assertEquals("幸会咖啡", invalidated.getName(), "内容保留，供重申后继续编辑");
    }

    @Test
    @Timeout(5)
    void withdrawnApplication_cannotReapproveAttachedNode() {
        merchantNode();
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, ME)).thenReturn(null);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.audit(PUBLISHER, 1L, true, null));

        assertTrue(error.getMessage().contains("已撤回或不在承接链路"));
        verify(nodeSvc, never()).updateCmsTopicNode(any());
    }

    /** 主办方自己的节点不该出现在这条审核链路上。 */
    @Test
    @Timeout(5)
    void hostOwnNode_notAuditable() {
        CmsTopicNode hostNode = new CmsTopicNode();
        hostNode.setId(2L);
        hostNode.setTopicId(TOPIC);
        when(nodeSvc.selectCmsTopicNodeById(2L)).thenReturn(hostNode);
        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, 2L, true, null));
    }

    @Test
    @Timeout(5)
    void rejectWithoutReason_refused() {
        merchantNode();
        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, 1L, false, "  "));
    }

    /** ★改一版要重审一版,否则过审后随便换内容 = 绕过审核。 */
    @Test
    @Timeout(5)
    void resubmitAfterApproval_backToPending() {
        CmsTopicNode approved = new CmsTopicNode();
        approved.setId(1L);
        approved.setTopicId(TOPIC);
        approved.setChapterId(CHAPTER);
        approved.setMerchantMemberId(ME);
        approved.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
        approved.setNodeAuditMemberId(PUBLISHER);
        approved.setXpValue(3);
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(approved));

        CmsTopicNode revised = draft();
        revised.setXpValue(9);
        service.submit(ME, CHAPTER, revised);

        ArgumentCaptor<CmsTopicNode> cap = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeSvc).updateCmsTopicNode(cap.capture());
        CmsTopicNode saved = cap.getValue();
        assertEquals(Integer.valueOf(9), saved.getXpValue());
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_PENDING), saved.getNodeAuditStatus(),
                "改完必须回到待审,否则过审一次就能随便换内容");
        // ⚠️ 清痕迹要用 0 而不是 null:mapper 的 <if test="xxx != null"> 会把 null 整个跳过,
        // 旧审核人会留在库里,表现成「这版新内容是上一任审核人批的」。清得写得进去才算清。
        assertEquals(Long.valueOf(0L), saved.getNodeAuditMemberId(),
                "旧的审核留痕要清掉,别让上一任审核人替新内容背书");
        revised.setXpValue(0);
        assertEquals(Integer.valueOf(0), service.submit(ME, CHAPTER, revised).getXpValue());
        assertEquals(Integer.valueOf(0), service.submit(ME, CHAPTER, draft()).getXpValue());
    }

    /** 别人的 offer 不算数:同章节里有别家生效供给,不能让我蹭。 */
    @Test
    @Timeout(5)
    void otherMerchantsOffer_doesNotCount() {
        MerchantChapterOffer notMine = new MerchantChapterOffer();
        notMine.setChapterId(CHAPTER);
        notMine.setMerchantId(OTHER);
        notMine.setStatus(1);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(List.of(notMine));
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, ME)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.submit(ME, CHAPTER, draft()));
    }

    // ===== 节点内容编辑闸(requireOwnLiveNode):给节点 NPC 等编辑动作共用 =====

    private CmsTopicNode myNode() {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(31L);
        n.setTopicId(TOPIC);
        n.setChapterId(CHAPTER);
        n.setMerchantMemberId(ME);
        return n;
    }

    @Test
    @Timeout(5)
    void requireOwnLiveNode_acceptsMineWithLiveOfferBeforeStart() {
        CmsTopicNode node = myNode();
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(node);

        CmsTopicNode got = service.requireOwnLiveNode(ME, 31L);
        assertEquals(31L, got.getId());
    }

    /**
     * ★归属闸:不存在与不归本人回同一句文案(分开写等于给了一个可枚举 nodeId 的探针),
     * 且主办方节点(merchant_member_id 为 NULL)一并落到这条。
     */
    @Test
    @Timeout(5)
    void requireOwnLiveNode_rejectsNotMine() {
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(null);
        assertEquals("点位不存在或无权操作",
                assertThrows(IllegalStateException.class, () -> service.requireOwnLiveNode(ME, 31L)).getMessage());

        CmsTopicNode theirs = myNode();
        theirs.setMerchantMemberId(OTHER);
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(theirs);
        assertEquals("点位不存在或无权操作",
                assertThrows(IllegalStateException.class, () -> service.requireOwnLiveNode(ME, 31L)).getMessage());

        CmsTopicNode hostNode = myNode();
        hostNode.setMerchantMemberId(null);
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(hostNode);
        assertThrows(IllegalStateException.class, () -> service.requireOwnLiveNode(ME, 31L));
    }

    /** ★承接闸:归属对但供给失效/过期,不能继续改内容 —— 已退出的商家不该再动这条点位。 */
    @Test
    @Timeout(5)
    void requireOwnLiveNode_rejectsExpiredOffer() {
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(myNode());

        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.requireOwnLiveNode(ME, 31L)).getMessage().contains("承接"));

        // 过期:status=1 但 valid_end 已过 —— 不判 valid_end 就会把过期算生效(与既有口径同)
        MerchantChapterOffer expired = new MerchantChapterOffer();
        expired.setChapterId(CHAPTER);
        expired.setMerchantId(ME);
        expired.setStatus(1);
        expired.setValidEnd(new Date(System.currentTimeMillis() - 1000L));
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(List.of(expired));
        assertThrows(IllegalStateException.class, () -> service.requireOwnLiveNode(ME, 31L));
    }

    /** ★冻结闸:主题已开跑就不能再改内容(玩家照着当前内容买了票、到了店)。 */
    @Test
    @Timeout(5)
    void requireOwnLiveNode_rejectsStartedTopic() {
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(myNode());
        CmsTopic started = new CmsTopic();
        started.setId(TOPIC);
        started.setMemberId(PUBLISHER);
        started.setLifecycle(TopicLifecycleState.RUNNING.getCode());
        started.setStartDate(new Date(System.currentTimeMillis() - 1000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(started);

        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.requireOwnLiveNode(ME, 31L)).getMessage().contains("已开始"));
    }

    /**
     * ★N1 读闸:归属 + 承接照旧,但**不判主题是否已开始** —— 冻结只挡写。
     * 主题开跑后商家仍要能回填自己的节点 NPC / 查生成中的声音状态,否则只有失败半屏、状态卡死。
     */
    @Test
    @Timeout(5)
    void requireOwnNode_ignoresStartedTopicButStillGatesOwnershipAndOffer() {
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(myNode());
        CmsTopic started = new CmsTopic();
        started.setId(TOPIC);
        started.setMemberId(PUBLISHER);
        started.setLifecycle(TopicLifecycleState.RUNNING.getCode());
        started.setStartDate(new Date(System.currentTimeMillis() - 1000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(started);

        assertEquals(31L, service.requireOwnNode(ME, 31L).getId(), "读闸不判冻结");
        // 写闸在同一份数据上仍要拒 —— 拆的是读的连坐,不是冻结本身。
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.requireOwnLiveNode(ME, 31L)).getMessage().contains("已开始"));

        // 归属/承接仍是实闸:不存在 / 不归本人 / 承接失效一律拒,读也不是越权口。
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.requireOwnNode(ME, 31L));
        CmsTopicNode theirs = myNode();
        theirs.setMerchantMemberId(OTHER);
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(theirs);
        assertThrows(IllegalStateException.class, () -> service.requireOwnNode(ME, 31L));
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(myNode());
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.requireOwnNode(ME, 31L)).getMessage().contains("承接"));
    }

    /** 畸形存量:节点没挂章节 = 无从判断承接,按无权处理,不去拼一条 selectByChapter(null)。 */
    @Test
    @Timeout(5)
    void requireOwnLiveNode_rejectsNodeWithoutChapter() {
        CmsTopicNode orphan = myNode();
        orphan.setChapterId(null);
        when(nodeSvc.selectCmsTopicNodeById(31L)).thenReturn(orphan);
        assertThrows(IllegalStateException.class, () -> service.requireOwnLiveNode(ME, 31L));
        verify(offerMapper, never()).selectByChapter(any());
    }

    @Test
    void pendingRecruitmentCanAddNodeAfterPlannedStartButSellingCannot() {
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC); topic.setMemberId(PUBLISHER);
        topic.setLifecycle(7); topic.setStartDate(new Date(System.currentTimeMillis() - 1000));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        CmsTopicNode draft = draft(); draft.setName("商家点位");
        service.submit(ME, CHAPTER, draft);
        topic.setLifecycle(3);
        assertThrows(IllegalStateException.class, () -> service.submit(ME, CHAPTER, draft));
    }

    @Test
    void lockedStagesCannotUpdateAnApprovedNodeEvenBeforePlannedStart() {
        CmsTopicNode approved = myNode();
        approved.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
        approved.setAddress("原地址");
        approved.setLongitude("120.7");
        approved.setLatitude("31.3");
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(approved));
        for (int code : new int[] {2, 3, 4, 5, 6}) {
            CmsTopic locked = new CmsTopic();
            locked.setId(TOPIC);
            locked.setMemberId(PUBLISHER);
            locked.setLifecycle(code);
            locked.setStartDate(new Date(System.currentTimeMillis() + 86400000L));
            when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(locked);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.submit(ME, CHAPTER, draft()));
            assertEquals("主题已锁价，点位配置只读", error.getMessage());
        }
        assertEquals("原地址", approved.getAddress());
        assertEquals("120.7", approved.getLongitude());
        assertEquals("31.3", approved.getLatitude());
        assertEquals(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED, approved.getNodeAuditStatus());
        verify(nodeSvc, never()).insertCmsTopicNode(any());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
        verify(tplSvc, never()).selectCmsMemberTemplateById(any());
    }

    @Test
    void unknownAndNonRecruitingStagesCannotSubmit() {
        for (Integer code : new Integer[] {null, 0, 99}) {
            CmsTopic unavailable = new CmsTopic();
            unavailable.setId(TOPIC);
            unavailable.setMemberId(PUBLISHER);
            unavailable.setLifecycle(code);
            when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(unavailable);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.submit(ME, CHAPTER, draft()));
            assertEquals(Integer.valueOf(0).equals(code) ? "主题不在招商阶段，点位配置只读"
                    : "主题生命周期未知或缺失，点位配置只读，请刷新后重试", error.getMessage());
        }
        verify(nodeSvc, never()).insertCmsTopicNode(any());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
    }

    @Test
    void submitReadsCurrentTopicAfterChapterLockInsteadOfStaleTopicSnapshot() {
        CmsTopic current = new CmsTopic();
        current.setId(TOPIC);
        current.setMemberId(PUBLISHER);
        current.setLifecycle(TopicLifecycleState.PRICING.getCode());
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(current);
        assertThrows(IllegalStateException.class, () -> service.submit(ME, CHAPTER, draft()));
        InOrder locks = inOrder(chapterMapper, chapterSvc, topicMapper, applicationMapper);
        locks.verify(chapterMapper).lockChaptersForCapacity(Collections.singletonList(CHAPTER));
        locks.verify(chapterSvc).selectCmsTopicChapterById(CHAPTER);
        locks.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        locks.verify(applicationMapper).selectByChapterAndMerchantForUpdate(CHAPTER, ME);
        verify(topicSvc, never()).selectCmsTopicById(any());
        verify(nodeSvc, never()).insertCmsTopicNode(any());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
    }

    @Test
    void springInjectsExistingMapperLocksForTheProductionService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ICmsTopicNodeService.class, () -> nodeSvc);
            context.registerBean(ICmsTopicChapterService.class, () -> chapterSvc);
            context.registerBean(ICmsTopicService.class, () -> topicSvc);
            context.registerBean(ICmsMemberTemplateService.class, () -> tplSvc);
            context.registerBean(MerchantChapterOfferMapper.class, () -> offerMapper);
            context.registerBean(MerchantChapterApplicationMapper.class, () -> applicationMapper);
            context.registerBean(CmsTopicChapterMapper.class, () -> chapterMapper);
            context.registerBean(CmsTopicMapper.class, () -> topicMapper);
            context.register(ChapterMerchantNodeServiceImpl.class);
            context.refresh();
            context.getBean(ChapterMerchantNodeServiceImpl.class).submit(ME, CHAPTER, draft());
            verify(chapterMapper).lockChaptersForCapacity(Collections.singletonList(CHAPTER));
            verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
            assertEquals(ME, captureSaved().getMerchantMemberId());
        }
    }


    @Test
    void lockedReplacementInvitationDoesNotBypassPointReview() {
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(TOPIC);
        topic.setLifecycle(3); topic.setStatus(1); topic.setUserStatus(0); topic.setStartDate(new Date(0));
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setStatus(MerchantChapterApplication.STATUS_APPROVED);
        application.setSource(MerchantChapterApplication.SOURCE_INVITE); application.setReplacementOfOfferId(800L);
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, ME)).thenReturn(application);
        MerchantChapterOffer original = new MerchantChapterOffer();
        original.setId(800L); original.setChapterId(CHAPTER); original.setTopicId(TOPIC);
        original.setStatus(0); original.setWithdrawReason("停止营业");
        when(offerMapper.selectByIdForUpdate(800L)).thenReturn(original);
        service.submit(ME, CHAPTER, draft());
        assertEquals(0, captureSaved().getNodeAuditStatus());
        verify(offerMapper, never()).approveReplacement(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void replacementApprovalChecksSupplyPointAndTemplateBeforeActivation() {
        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(TOPIC);
        topic.setLifecycle(3); topic.setStatus(1); topic.setUserStatus(0);
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setStatus(MerchantChapterApplication.STATUS_APPROVED); application.setReplacementOfOfferId(800L);
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, ME)).thenReturn(application);
        MerchantChapterOffer original = new MerchantChapterOffer();
        original.setId(800L); original.setTopicId(TOPIC); original.setChapterId(CHAPTER);
        original.setStatus(0); original.setWithdrawReason("停止营业"); original.setTermsMode("TRAFFIC");
        MerchantChapterOffer replacement = new MerchantChapterOffer();
        replacement.setId(801L); replacement.setTopicId(TOPIC); replacement.setChapterId(CHAPTER);
        replacement.setMerchantId(ME); replacement.setReplacementOfOfferId(800L); replacement.setStatus(0);
        replacement.setTermsMode("TRAFFIC");
        when(offerMapper.selectByIdForUpdate(800L)).thenReturn(original);
        when(offerMapper.selectReplacementForUpdate(800L)).thenReturn(replacement);
        CmsTopicNode node = draft(); node.setId(501L); node.setTopicId(TOPIC); node.setChapterId(CHAPTER);
        node.setMerchantMemberId(ME);
        node.setAddress("");
        when(nodeSvc.selectCmsTopicNodeById(501L)).thenReturn(node);
        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, 501L, true, ""));
        node.setAddress("既有地图点位"); node.setLatitude("NaN"); node.setLongitude("121.5");
        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, 501L, true, ""));
        node.setLatitude("31.26"); replacement.setPerHeadFee(java.math.BigDecimal.ONE);
        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, 501L, true, ""));
        replacement.setPerHeadFee(null); givenTemplate(OTHER, 1, null);
        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, 501L, true, ""));
        verify(offerMapper, never()).approveReplacement(801L, ME);
        givenTemplate(ME, 1, null); when(offerMapper.approveReplacement(801L, ME)).thenReturn(1);
        assertEquals(1, service.audit(PUBLISHER, 501L, true, "").getNodeAuditStatus());
        assertEquals(0, topic.getUserStatus(), "过审只使供给生效，仍由主办显式恢复");
        verify(offerMapper).approveReplacement(801L, ME);
    }
}

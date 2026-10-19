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
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-EXIT-REPLACE-LOCKED 验收:锁价(SELLING)后原商家退出,主办审核通过补位点位。
 *
 * <p>期望:只有「补位供给 status 0→1」与「待审补位点位 → 已过审」两笔写;
 * 原供给(冻结价格/份数)、已完成的历史点位、玩家进度/结果/权益/票价一概不碰;报名暂停仍由主办显式恢复。
 */
class LockedSupplyReplacementAcceptanceTest {

    private static final Long PUBLISHER = 5L;
    private static final Long OLD_MERCHANT = 77L;
    private static final Long NEW_MERCHANT = 88L;
    private static final Long TOPIC = 100L;
    private static final Long CHAPTER = 9L;
    private static final Long TPL = 700L;
    private static final Long ORIGINAL_OFFER = 800L;
    private static final Long REPLACEMENT_OFFER = 801L;
    private static final Long NEW_NODE = 501L;
    private static final Long HISTORY_NODE = 400L;

    private ICmsTopicNodeService nodeSvc;
    private ICmsTopicChapterService chapterSvc;
    private ICmsTopicService topicSvc;
    private ICmsMemberTemplateService tplSvc;
    private MerchantChapterOfferMapper offerMapper;
    private MerchantChapterApplicationMapper applicationMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private TopicSupplyReviewService supplyReview;
    private ChapterMerchantNodeServiceImpl service;

    private CmsTopic topic;
    private MerchantChapterOffer original;
    private MerchantChapterOffer replacement;
    private CmsTopicNode historyNode;
    private CmsTopicNode candidate;

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
        supplyReview = mock(TopicSupplyReviewService.class);
        service = new ChapterMerchantNodeServiceImpl(nodeSvc, chapterSvc, topicSvc, tplSvc, offerMapper, applicationMapper);
        ReflectionTestUtils.setField(service, "supplyReviewService", supplyReview);
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        when(chapterSvc.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);

        // 锁价售卖中,商家退出后已暂停报名(userStatus=0)
        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setLifecycle(TopicLifecycleState.SELLING.getCode());
        topic.setStatus(1);
        topic.setUserStatus(0);
        topic.setStartDate(new Date(System.currentTimeMillis() + 5 * 86400000L));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);

        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(TPL);
        tpl.setMemberId(NEW_MERCHANT);
        tpl.setValidationMethod(1);
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(tpl);

        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setChapterId(CHAPTER);
        application.setMerchantId(NEW_MERCHANT);
        application.setStatus(MerchantChapterApplication.STATUS_APPROVED);
        application.setSource(MerchantChapterApplication.SOURCE_INVITE);
        application.setReplacementOfOfferId(ORIGINAL_OFFER);
        when(applicationMapper.selectByChapterAndMerchantForUpdate(CHAPTER, NEW_MERCHANT)).thenReturn(application);

        original = new MerchantChapterOffer();
        original.setId(ORIGINAL_OFFER);
        original.setTopicId(TOPIC);
        original.setChapterId(CHAPTER);
        original.setMerchantId(OLD_MERCHANT);
        original.setStatus(0);
        original.setWithdrawReason("停止营业");
        original.setTermsMode(TermsMode.PERK);
        original.setPerHeadFee(new BigDecimal("12.50"));
        original.setAllowedValidationMethods("1");
        original.setMaxNodeXp(30);
        original.setQuotaTotal(50);
        original.setQuotaUsed(20);
        when(offerMapper.selectByIdForUpdate(ORIGINAL_OFFER)).thenReturn(original);

        replacement = new MerchantChapterOffer();
        replacement.setId(REPLACEMENT_OFFER);
        replacement.setTopicId(TOPIC);
        replacement.setChapterId(CHAPTER);
        replacement.setMerchantId(NEW_MERCHANT);
        replacement.setReplacementOfOfferId(ORIGINAL_OFFER);
        replacement.setStatus(0);
        replacement.setTermsMode(TermsMode.PERK);
        replacement.setPerHeadFee(new BigDecimal("12.50"));
        replacement.setAllowedValidationMethods("1");
        replacement.setMaxNodeXp(30);
        replacement.setQuotaTotal(30);
        replacement.setQuotaUsed(0);
        when(offerMapper.selectReplacementForUpdate(ORIGINAL_OFFER)).thenReturn(replacement);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        when(offerMapper.approveReplacement(REPLACEMENT_OFFER, NEW_MERCHANT)).thenReturn(1);

        // 原商家已过审、玩家已在其上完成过的历史点位
        historyNode = node(HISTORY_NODE, OLD_MERCHANT, 1);
        when(nodeSvc.selectCmsTopicNodeById(HISTORY_NODE)).thenReturn(historyNode);
        candidate = node(NEW_NODE, NEW_MERCHANT, 0);
        when(nodeSvc.selectCmsTopicNodeById(NEW_NODE)).thenReturn(candidate);
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(historyNode, candidate));
    }

    private static CmsTopicNode node(Long id, Long merchant, int auditStatus) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setChapterId(CHAPTER);
        n.setMerchantMemberId(merchant);
        n.setNodeAuditStatus(auditStatus);
        n.setName("店" + id);
        n.setTemplateId(TPL);
        n.setAddress("星海街 " + id + " 号");
        n.setLongitude("120.7");
        n.setLatitude("31.3");
        return n;
    }

    @Test
    void approvingLockedReplacementOnlyActivatesReplacementAndCandidateNode() {
        CmsTopicNode approved = service.audit(PUBLISHER, NEW_NODE, true, "");

        assertEquals(1, approved.getNodeAuditStatus());
        verify(offerMapper).approveReplacement(REPLACEMENT_OFFER, NEW_MERCHANT);
        ArgumentCaptor<CmsTopicNode> updated = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeSvc).updateCmsTopicNode(updated.capture());
        assertEquals(NEW_NODE, updated.getValue().getId(), "只能改这次待审的补位点位");

        // 已完成的历史点位原样有效
        assertEquals(1, historyNode.getNodeAuditStatus());
        assertEquals(OLD_MERCHANT, historyNode.getMerchantMemberId());
        // 原供给的冻结价格/份数/退出事实不被改写,也不被复活
        assertEquals(0, original.getPerHeadFee().compareTo(new BigDecimal("12.50")));
        assertEquals(Integer.valueOf(50), original.getQuotaTotal());
        assertEquals(Integer.valueOf(20), original.getQuotaUsed());
        assertEquals(Integer.valueOf(0), original.getStatus());
        assertEquals("停止营业", original.getWithdrawReason());
        // 报名恢复仍由主办显式操作
        assertEquals(Integer.valueOf(0), topic.getUserStatus());

        assertOnlyWrites(Arrays.asList("approveReplacement", "updateCmsTopicNode"));
        List<Invocation> offerWrites = writes(offerMapper);
        assertEquals(1, offerWrites.size());
        assertEquals(REPLACEMENT_OFFER, offerWrites.get(0).getArgument(0), "唯一的供给写必须落在补位供给上");
    }

    @Test
    void replacementWithRaisedPriceIsRefusedWithoutAnyWrite() {
        replacement.setPerHeadFee(new BigDecimal("15.00"));

        assertThrows(IllegalStateException.class, () -> service.audit(PUBLISHER, NEW_NODE, true, ""));

        verify(offerMapper, never()).approveReplacement(anyLong(), anyLong());
        verify(nodeSvc, never()).updateCmsTopicNode(any());
        assertOnlyWrites(Collections.emptyList());
    }

    /**
     * 结构性保证:审核服务根本没有玩家进度/结果/报名/权益/票价/结算的写入口 ⇒
     * 补位审核不可能改写玩家已完成的历史或冻结价格。将来若有人把这些依赖接进来,这条会提醒复核。
     */
    private static final Set<String> READ_PREFIXES = new HashSet<>(Arrays.asList("select", "lock", "count", "sum"));

    private static boolean isRead(Invocation invocation) {
        String name = invocation.getMethod().getName();
        for (String p : READ_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    private static List<Invocation> writes(Object mock) {
        List<Invocation> out = new ArrayList<>();
        for (Invocation i : Mockito.mockingDetails(mock).getInvocations()) {
            if (!isRead(i)) out.add(i);
        }
        return out;
    }

    private void assertOnlyWrites(List<String> allowed) {
        List<String> unexpected = new ArrayList<>();
        for (Object m : Arrays.asList(nodeSvc, chapterSvc, topicSvc, tplSvc, offerMapper, applicationMapper,
                chapterMapper, topicMapper, supplyReview)) {
            for (Invocation i : writes(m)) {
                if (!allowed.contains(i.getMethod().getName())) unexpected.add(i.toString());
            }
        }
        assertTrue(unexpected.isEmpty(), "补位审核出现了意外写入: " + unexpected);
    }
}

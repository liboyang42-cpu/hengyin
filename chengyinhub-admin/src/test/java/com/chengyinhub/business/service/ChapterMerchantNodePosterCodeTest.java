package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 5 · T2:探店日商家取本店海报码。
 *
 * <p>为什么不能照抄漫游那条:{@code ApiMerchantNodeController:303 poster-code} 比的是
 * {@code roam_poi.merchant_id} = {@code mms_merchant.id};而探店日
 * {@code merchant_chapter_offer.merchant_id} 存的是 **memberId**,照抄归属闸会恒 null。
 *
 * <p>两道闸都要过,缺一都是越权:
 * ① 节点归属 —— {@code cms_topic_node.merchant_member_id} 必须等于当前登录 memberId;
 * ② 承接生效 —— 该章节上必须有本人 status=1 且未过期的 offer。只判 ① 会让退出承接的商家
 *    继续印码截流;只判 ② 会让同章节的另一家商家把邻居的码印出来。
 *
 * <p>码本身是 {@code cms_topic_node.checkin_code}(玩家扫它由 {@code /api/play/checkin}
 * 按码反查),**不是** VerifyDynCode —— 动态码那套是玩家掏给商家扫的核销码,
 * 签出来的串 {@code /checkin} 反查不到任何节点。
 */
class ChapterMerchantNodePosterCodeTest {

    private static final long MERCHANT = 8801L;
    private static final long OTHER_MERCHANT = 8802L;
    private static final long CHAPTER = 9L;
    private static final long NODE = 77L;
    private static final String CODE = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";

    private MerchantChapterOfferMapper offerMapper;
    private ICmsTopicNodeService nodeService;

    private ChapterMerchantNodeServiceImpl service(CmsTopicNode node, MerchantChapterOffer offer) {
        nodeService = mock(ICmsTopicNodeService.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(offerMapper.selectByChapter(anyLong()))
                .thenReturn(offer == null ? Collections.<MerchantChapterOffer>emptyList() : Arrays.asList(offer));
        return new ChapterMerchantNodeServiceImpl(nodeService,
                mock(ICmsTopicChapterService.class), mock(ICmsTopicService.class),
                mock(ICmsMemberTemplateService.class), offerMapper,
                mock(MerchantChapterApplicationMapper.class));
    }

    private CmsTopicNode boundNode(Long ownerMemberId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(100L);
        node.setChapterId(CHAPTER);
        node.setName("毛孩子咖啡");
        node.setMerchantMemberId(ownerMemberId);
        node.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
        node.setCheckinCode(CODE);
        return node;
    }

    private MerchantChapterOffer liveOffer(Long merchantMemberId) {
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setId(5L);
        offer.setChapterId(CHAPTER);
        offer.setMerchantId(merchantMemberId);
        offer.setStatus(1);
        return offer;
    }

    @Test
    void ownMerchantGetsOwnStaticCheckinCode() {
        CmsTopicNode node = boundNode(MERCHANT);
        CmsTopicNode got = service(node, liveOffer(MERCHANT)).posterCodeNode(MERCHANT, NODE);

        assertSame(node, got);
        assertEquals(CODE, got.getCheckinCode(), "海报码就是店内静态码本身,别另签一个 /checkin 反查不到的串");
    }

    /** IDOR 负控:同章节的另一家商家拿着邻居的 nodeId 来取码,必须被拒。 */
    @Test
    void otherMerchantCannotFetchNeighbourCode() {
        CmsTopicNode neighbour = boundNode(OTHER_MERCHANT);
        // 请求方自己在这一章确实有生效承接 —— 只有节点归属这一道闸能拦住他。
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service(neighbour, liveOffer(MERCHANT)).posterCodeNode(MERCHANT, NODE));
        assertEquals("点位不存在或无权操作", e.getMessage());
    }

    /** 平台主办方节点(merchant_member_id 为 NULL)不属于任何商家,谁都取不到。 */
    @Test
    void hostOwnedNodeIsNotFetchableByAnyMerchant() {
        CmsTopicNode host = boundNode(null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service(host, liveOffer(MERCHANT)).posterCodeNode(MERCHANT, NODE));
        assertEquals("点位不存在或无权操作", e.getMessage());
    }

    /** 承接失效后不得继续印码 —— 否则退出的商家还能把玩家截到自己店里。 */
    @Test
    void expiredOfferBlocksPosterCode() {
        MerchantChapterOffer expired = liveOffer(MERCHANT);
        expired.setValidEnd(new Date(System.currentTimeMillis() - 60_000L));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service(boundNode(MERCHANT), expired).posterCodeNode(MERCHANT, NODE));
        assertEquals("承接已失效或未生效，不能取码", e.getMessage());
    }

    @Test
    void deactivatedOfferBlocksPosterCode() {
        MerchantChapterOffer stopped = liveOffer(MERCHANT);
        stopped.setStatus(0);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service(boundNode(MERCHANT), stopped).posterCodeNode(MERCHANT, NODE));
        assertEquals("承接已失效或未生效，不能取码", e.getMessage());
    }

    @Test
    void missingNodeIsRejectedWithoutLeakingExistence() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service(null, liveOffer(MERCHANT)).posterCodeNode(MERCHANT, NODE));
        assertEquals("点位不存在或无权操作", e.getMessage());
    }

    /**
     * 未过审的节点不许印码。
     *
     * <p>{@code selectCmsTopicNodeById} 不带可见性闸,而玩家扫码走的
     * {@code selectCmsTopicNodeList} 带 —— 未过审的商家节点在那边查不到。
     * 少了这道闸,商家会把一个「玩家扫了报无效打卡码」的码印满店里,而取码这边一路成功、零告警。
     */
    @Test
    void pendingOrRejectedNodeCannotBePrinted() {
        for (Integer auditStatus : new Integer[]{null,
                ChapterMerchantNodeServiceImpl.AUDIT_PENDING,
                ChapterMerchantNodeServiceImpl.AUDIT_REJECTED}) {
            CmsTopicNode node = boundNode(MERCHANT);
            node.setNodeAuditStatus(auditStatus);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service(node, liveOffer(MERCHANT)).posterCodeNode(MERCHANT, NODE));
            assertEquals("该点位还没过审，过审后再取码", e.getMessage(),
                    "审核态 " + auditStatus + " 的节点玩家扫不出来,不许印");
        }
    }

    /** T1 之前建的空码节点:必须给出可行动的错误，而不是把空串印成二维码。 */
    @Test
    void blankCheckinCodeIsRejected() {
        CmsTopicNode node = boundNode(MERCHANT);
        node.setCheckinCode("  ");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service(node, liveOffer(MERCHANT)).posterCodeNode(MERCHANT, NODE));
        assertEquals("该点位还没有打卡码，请联系主办方", e.getMessage());
    }

    @Test
    void anonymousAndBadParamsRejected() {
        assertThrows(IllegalStateException.class,
                () -> service(boundNode(MERCHANT), liveOffer(MERCHANT)).posterCodeNode(0L, NODE));
        assertThrows(IllegalStateException.class,
                () -> service(boundNode(MERCHANT), liveOffer(MERCHANT)).posterCodeNode(MERCHANT, null));
    }

    /**
     * 工作台 DTO 不得因为本端点开口而顺手暴露核销码。
     *
     * <p>{@code TopicWorkspaceServiceImplTest:385} 已经锁着同一条契约;这里再钉一遍是因为
     * T2 的诱惑正是「工作台已经查到节点了,顺手把码也带出去」——
     * 工作台是**后台运营**的读面,商家取码必须走本端点这条带归属闸的路。
     */
    @Test
    void workspaceNodeDtoStillHasNoCheckinCode() {
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Node.class.getMethod("getCheckinCode"),
                "工作台节点不得暴露核销码 —— T2 端点是唯一出码口");
    }
}

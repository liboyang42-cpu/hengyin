package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerWxacodeService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 5 · T2 端点层:二维码里装的必须是店内静态码本身。
 *
 * <p>这一条单独钉,是因为审计原文写的是「复用 {@code VerifyDynCodeUtil}」——
 * 那是**漫游据点**的口径(那条码给 {@code /api/city/nodes/{id}/complete} 用)。
 * 探店日玩家扫完由 {@code /api/play/checkin} 按 {@code cms_topic_node.checkin_code}
 * **等值反查**节点,所以签一个 dyncode 串贴到店里 = 玩家扫了报「无效的打卡码」,零告警。
 */
class ApiChapterMerchantNodePosterCodeControllerTest {

    private static final long PRINCIPAL = 8801L;
    private static final long MERCHANT_OWNER = 8802L;
    private static final String CODE = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";

    private IChapterMerchantNodeService nodeService;
    private ICmsTopicNodeService topicNodes;
    private GameSessionRuntimeMapper gameSessions;
    private ICmsRegistrationMerchantService registrations;
    private IMerchantAccessService merchantAccessService;
    private TestableController controller;

    @BeforeEach
    void setUp() {
        nodeService = mock(IChapterMerchantNodeService.class);
        topicNodes = mock(ICmsTopicNodeService.class);
        gameSessions = mock(GameSessionRuntimeMapper.class);
        registrations = mock(ICmsRegistrationMerchantService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.BASIC_READ))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 91L, MERCHANT_OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(gameSessions.countMerchantOperableStation(eq(MERCHANT_OWNER), anyLong())).thenReturn(0);
        when(registrations.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(Collections.emptyList());
        IQrcodeService qrcodeService = mock(IQrcodeService.class);
        when(qrcodeService.generateQrcode(anyString(), anyInt(), anyInt())).thenReturn("https://oss/qr.png");
        IPlayerWxacodeService playerWxacodeService = mock(IPlayerWxacodeService.class);
        when(playerWxacodeService.getOrCreateScene(CODE)).thenReturn("https://oss/wxacode.png");
        controller = new TestableController(PRINCIPAL);
        ReflectionTestUtils.setField(controller, "chapterMerchantNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "nodeService", topicNodes);
        ReflectionTestUtils.setField(controller, "gameSessionRuntimeMapper", gameSessions);
        ReflectionTestUtils.setField(controller, "registrationMerchantService", registrations);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "qrcodeService", qrcodeService);
        ReflectionTestUtils.setField(controller, "playerWxacodeService", playerWxacodeService);
    }

    private CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(77L);
        node.setTopicId(100L);
        node.setChapterId(9L);
        node.setName("毛孩子咖啡");
        node.setCheckinCode(CODE);
        return node;
    }

    @Test
    @SuppressWarnings("unchecked")
    void posterQrcodeCarriesStaticCheckinCodeNotDynCode() {
        when(nodeService.posterCodeNode(MERCHANT_OWNER, 77L)).thenReturn(node());

        AjaxResult r = controller.posterCode(77L);

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertEquals(CODE, data.get("code"), "二维码内容必须是 checkin_code 本身");
        assertEquals("https://oss/wxacode.png", data.get("qrcodeUrl"));
        assertEquals(77L, data.get("nodeId"));
        assertEquals(9L, data.get("chapterId"));
        // 负控:动态核销码那套串一定带分隔符与签名段,长度也不同。出现它就说明有人照抄漫游了。
        String issued = VerifyDynCodeUtil.issuePoiPoster(77L, "any-secret");
        assertFalse(issued.equals(String.valueOf(data.get("code"))),
                "海报码不得是 VerifyDynCode —— /api/play/checkin 反查不到它");
        assertTrue(String.valueOf(data.get("code")).matches("[0-9a-f]{32}"));
    }

    @Test
    void anonymousRejectedBeforeTouchingService() {
        TestableController anon = new TestableController(0L);
        ReflectionTestUtils.setField(anon, "chapterMerchantNodeService", nodeService);
        AjaxResult r = anon.posterCode(77L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", r.get(AjaxResult.MSG_TAG));
    }

    /** service 层的归属/承接闸抛的话，端点必须转成可读错误而不是 500 堆栈。 */
    @Test
    void serviceGateBecomesReadableError() {
        when(nodeService.posterCodeNode(eq(MERCHANT_OWNER), eq(78L)))
                .thenThrow(new IllegalStateException("点位不存在或无权操作"));
        AjaxResult r = controller.posterCode(78L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("点位不存在或无权操作", r.get(AjaxResult.MSG_TAG));
    }

    /**
     * 城市定向中标站常常是主办方节点(merchant_member_id 为空)，
     * 商家取码必须仍是静态 checkin_code，不能因此改签发一次性串。
     */
    @Test
    @SuppressWarnings("unchecked")
    void assignedStationGetsStaticCheckinCode() {
        when(nodeService.posterCodeNode(eq(MERCHANT_OWNER), eq(77L)))
                .thenThrow(new IllegalStateException("点位不存在或无权操作"));
        CmsRegistrationMerchant won = new CmsRegistrationMerchant();
        won.setMemberId(MERCHANT_OWNER);
        won.setNodeId(77L);
        won.setStatus(1L);
        won.setAuditStatus(1L);
        when(registrations.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(Collections.singletonList(won));
        CmsTopicNode hostNode = node();
        hostNode.setMerchantMemberId(null);
        when(topicNodes.selectCmsTopicNodeById(77L)).thenReturn(hostNode);

        AjaxResult r = controller.posterCode(77L);

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertEquals(CODE, data.get("code"));
        assertEquals("https://oss/wxacode.png", data.get("qrcodeUrl"));
        assertTrue(String.valueOf(data.get("code")).matches("[0-9a-f]{32}"));
    }

    private static class TestableController extends ApiChapterMerchantNodeController {
        private final Long appUserId;

        private TestableController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}

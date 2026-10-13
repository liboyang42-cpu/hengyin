package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.dto.TopicStateKeyRegisterDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicPublishCheckService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8 创作者端状态键接口:越权被拒;删除被引用键被拒并列出引用位置;
 * 发布前试检查原样返回结构化列表。getAppUserId 不可 mock,所以按既有做法 spy 掉。
 */
class ApiTopicStateKeyControllerTest {

    private static final long CALLER = 901L;
    private static final long TOPIC_ID = 7001L;

    private ICmsTopicService topicService;
    private ThemeStateKeyService keyService;
    private TopicStateKeyRefService refService;
    private TopicPublishCheckService checkService;
    private ApiTopicStateKeyController controller;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        keyService = mock(ThemeStateKeyService.class);
        refService = mock(TopicStateKeyRefService.class);
        checkService = mock(TopicPublishCheckService.class);
        controller = spy(new ApiTopicStateKeyController());
        doReturn(CALLER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "themeStateKeyService", keyService);
        ReflectionTestUtils.setField(controller, "topicStateKeyRefService", refService);
        ReflectionTestUtils.setField(controller, "topicPublishCheckService", checkService);
    }

    @Test
    void listRejectsOtherMembersTopic() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(999L));

        AjaxResult result = controller.listStateKeys(TOPIC_ID, null);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权操作该主题", result.get(AjaxResult.MSG_TAG));
        verify(keyService, never()).listByTopic(anyLong());
    }

    @Test
    void listRejectsAnonymous() {
        doThrow(new RuntimeException("no token")).when(controller).getAppUserId();

        AjaxResult result = controller.listStateKeys(TOPIC_ID, null);

        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void registerRejectsOtherMembersTopic() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(999L));
        TopicStateKeyRegisterDTO dto = registerDto();

        AjaxResult result = controller.registerStateKeys(dto);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(keyService, never()).register(anyLong(), any());
    }

    @Test
    void registerDelegatesToServiceAfterOwnershipPasses() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(CALLER));
        TopicStateKeyRegisterDTO dto = registerDto();

        AjaxResult result = controller.registerStateKeys(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        verify(keyService).register(eq(TOPIC_ID), eq(dto.getKeys()));
    }

    /**
     * R18-DRAFT:草稿主题(未发布)上注册状态键必须可用 —— 服务端草稿 id 就是为这一步存在的,
     * 没有它作者在第一次发布前根本没有 topicId 可传。
     */
    @Test
    void registerAllowedOnDraftTopic() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(draftTopic(CALLER));
        when(topicService.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        TopicStateKeyRegisterDTO dto = registerDto();

        AjaxResult result = controller.registerStateKeys(dto);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        verify(keyService).register(eq(TOPIC_ID), eq(dto.getKeys()));
    }

    /** R18-DRAFT:草稿上发布前试检查必须可用 —— 试检查就是给「发布前」用的,只读不看发布态。 */
    @Test
    void publishCheckAllowedOnDraftTopic() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(draftTopic(CALLER));
        when(topicService.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        when(checkService.check(any(CmsTopic.class)))
                .thenReturn(Collections.<TopicPublishCheckService.PublishCheckIssue>emptyList());

        AjaxResult result = controller.publishCheck(TOPIC_ID, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        verify(checkService).check(any(CmsTopic.class));
    }

    /**
     * 编辑窗口与主题 update 同一口径:SELLING 起商业结构冻结,状态键注册表同属结构。
     *
     * <p>负控:把 editWindowDenial 调用摘掉,本用例立刻红。
     */
    @Test
    void registerRejectedWhenTopicIsSellingFrozen() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(sellingTopic(CALLER));

        AjaxResult result = controller.registerStateKeys(registerDto());

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("SELLING"), result.toString());
        verify(keyService, never()).register(anyLong(), any());
    }

    /** 删除是写接口,同样被编辑窗口拦;不能靠删键绕开冻结。 */
    @Test
    void deleteRejectedWhenTopicIsSellingFrozen() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(sellingTopic(CALLER));

        AjaxResult result = controller.deleteStateKey(TOPIC_ID, "tag.hurried", null);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("SELLING"), result.toString());
        verify(refService, never()).referencesOf(any(CmsTopic.class), eq("tag.hurried"));
        verify(keyService, never()).delete(anyLong(), any());
    }

    /** GET 与 publish-check 是只读,冻结主题照常可读 —— 不然创作者连问题清单都看不到。 */
    @Test
    void listAndPublishCheckStayReadableWhenTopicIsSellingFrozen() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(sellingTopic(CALLER));
        when(keyService.listByTopic(TOPIC_ID)).thenReturn(Collections.<ThemeStateKey>emptyList());
        when(checkService.check(any(CmsTopic.class)))
                .thenReturn(Collections.<TopicPublishCheckService.PublishCheckIssue>emptyList());

        assertEquals(200, controller.listStateKeys(TOPIC_ID, null).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.publishCheck(TOPIC_ID, null).get(AjaxResult.CODE_TAG));
    }

    @Test
    void registerSurfacesBatchValidationError() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(CALLER));
        doThrow(new ServiceException("状态键名不合法:BadKey")).when(keyService).register(anyLong(), any());

        AjaxResult result = controller.registerStateKeys(registerDto());

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("状态键名不合法"), result.toString());
    }

    @Test
    void deleteRejectsReferencedKeyWithPositions() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(CALLER));
        when(refService.referencesOf(any(CmsTopic.class), eq("counter.coins")))
                .thenReturn(Arrays.asList("节点「书房」", "主题数值规则"));

        AjaxResult result = controller.deleteStateKey(TOPIC_ID, "counter.coins", null);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        String message = String.valueOf(result.get(AjaxResult.MSG_TAG));
        assertTrue(message.contains("counter.coins"), message);
        assertTrue(message.contains("节点「书房」"), message);
        assertTrue(message.contains("主题数值规则"), message);
        verify(keyService, never()).delete(anyLong(), any());
    }

    @Test
    void deleteRemovesUnreferencedKey() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(CALLER));
        when(refService.referencesOf(any(CmsTopic.class), eq("tag.hurried")))
                .thenReturn(Collections.<String>emptyList());
        when(keyService.delete(TOPIC_ID, "tag.hurried")).thenReturn(true);

        AjaxResult result = controller.deleteStateKey(TOPIC_ID, "tag.hurried", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        verify(keyService).delete(TOPIC_ID, "tag.hurried");
    }

    @Test
    void deleteFailsClosedWhenConfigCannotBeParsed() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(CALLER));
        when(refService.referencesOf(any(CmsTopic.class), eq("tag.hurried")))
                .thenThrow(new ServiceException("主题存在无法解析的配置,暂不能删除状态键:节点「书房」..."));

        AjaxResult result = controller.deleteStateKey(TOPIC_ID, "tag.hurried", null);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(keyService, never()).delete(anyLong(), any());
    }

    @Test
    void publishCheckReturnsStructuredIssues() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(CALLER));
        List<TopicPublishCheckService.PublishCheckIssue> issues = Arrays.asList(
                TopicPublishCheckService.PublishCheckIssue.error("STATE_KEY_UNREGISTERED", 77L, "状态键未注册:clue.x"));
        when(checkService.check(any(CmsTopic.class))).thenReturn(issues);

        AjaxResult result = controller.publishCheck(TOPIC_ID, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        assertEquals(issues, result.get("data"));
    }

    @Test
    void publishCheckRejectsOtherMembersTopic() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(999L));

        AjaxResult result = controller.publishCheck(TOPIC_ID, null);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(checkService, never()).check(any(CmsTopic.class));
    }

    private static CmsTopic topic(Long ownerMemberId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(ownerMemberId);
        topic.setDelFlag(0);
        topic.setLifecycle(0);
        return topic;
    }

    /** 已开卖主题:生命周期 SELLING,编辑窗口判定为 SELLING_FROZEN。 */
    private static CmsTopic sellingTopic(Long ownerMemberId) {
        CmsTopic topic = topic(ownerMemberId);
        topic.setStatus(1);
        topic.setLifecycle(3);
        return topic;
    }

    /** 服务端草稿:status=0 且 is_draft=1,从未公开过。 */
    private static CmsTopic draftTopic(Long ownerMemberId) {
        CmsTopic topic = topic(ownerMemberId);
        topic.setStatus(0);
        topic.setUserStatus(0);
        return topic;
    }

    private static TopicStateKeyRegisterDTO registerDto() {
        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey("clue.ledger_page");
        key.setKeyType("flag");
        key.setScope("run");
        TopicStateKeyRegisterDTO dto = new TopicStateKeyRegisterDTO();
        dto.setTopicId(TOPIC_ID);
        dto.setKeys(Collections.singletonList(key));
        return dto;
    }
}

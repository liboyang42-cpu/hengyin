package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.TopicLocationBindingCommand;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 地点绑定写的边界：归属、编辑窗口(FULL)、父行 fence、节点归属、revision 冲突、回读。
 *
 * <p>关键负例：已过审(WORD 白名单档)主题也必须拒 —— 绑定是内容结构，不是售卖中可改的文案。
 */
class TopicLocationBindingServiceTest {

    private static final long TOPIC_ID = 42L;
    private static final long OWNER = 2000L;
    private static final long STRANGER = 9000L;

    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private TopicContentWriteGuard guard;
    private TopicLocationBindingService service;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        guard = mock(TopicContentWriteGuard.class);
        service = new TopicLocationBindingService();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "contentWriteGuard", guard);

        topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(OWNER);
        topic.setDelFlag(0);
        topic.setStatus(0);
        topic.setLifecycle(0);
        topic.setCityCode("320100");
        String contract = TopicLocationContract.buildAdoptionContract(
                sourceTopic(), Collections.singletonList(sourceNode()), Collections.singletonMap(11L, 111L),
                TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());
        topic.setContentContractJson(contract);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);
        // 唯一写口:SQL 里再判 owner;stub 同样模拟 owner 不匹配返回 0,不留“忽略返回值”的口子。
        when(topicMapper.updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (!java.util.Objects.equals(TOPIC_ID, inv.getArgument(0))
                            || !java.util.Objects.equals(OWNER, inv.getArgument(1))) return 0;
                    topic.setContentContractJson(inv.getArgument(2));
                    return 1;
                });
        when(nodeMapper.selectCmsTopicNodeById(anyLong())).thenReturn(null);
    }

    private static CmsTopic sourceTopic() {
        CmsTopic source = new CmsTopic();
        source.setId(100L);
        source.setCityCode("310100");
        source.setConfigVersion(3L);
        return source;
    }

    private static CmsTopicNode sourceNode() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(11L);
        node.setTopicId(100L);
        node.setName("旧城地点");
        node.setAddress("上海市黄浦区");
        return node;
    }

    private CmsTopicNode boundNode(long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC_ID);
        node.setName("新地点");
        node.setAddress("南京市秦淮区");
        node.setLongitude("118.78");
        node.setLatitude("32.04");
        return node;
    }

    private TopicLocationBindingCommand command(Long nodeId) {
        TopicLocationBindingCommand command = new TopicLocationBindingCommand();
        command.setTopicId(TOPIC_ID);
        command.setSlotKey("loc-11");
        command.setNodeId(nodeId);
        command.setExpectedRevision(0);
        return command;
    }

    @Test
    void draftBindingWritesServerFactsAndReadsBack() {
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));
        when(nodeMapper.selectCmsTopicNodeById(502L)).thenReturn(boundNode(502L));
        TopicLocationBindingCommand command = command(501L);
        command.setApprovedFallbackNodeIds(Collections.singletonList(502L));
        command.setObservationNote("站在东侧台阶");

        Map<String, Object> result = service.bind(command, OWNER, OWNER);

        verify(guard).lockTopicContent(TOPIC_ID);
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(topicMapper).updateTopicLocationContract(org.mockito.ArgumentMatchers.eq(TOPIC_ID),
                org.mockito.ArgumentMatchers.eq(OWNER), written.capture(),
                org.mockito.ArgumentMatchers.anyString(), any());
        var binding = TopicLocationContract.parse(written.getValue())
                .path("locationBindings").get(0);
        assertEquals("501", binding.path("nodeId").asText());
        assertEquals("BOUND_UNVERIFIED", binding.path("verificationStatus").asText(),
                "本仓没有现场核验服务，不得自报已核验");
        assertEquals(String.valueOf(OWNER), binding.path("confirmedBy").asText());
        assertEquals("502", binding.path("approvedFallbackNodeIds").get(0).asText());
        assertNotNull(result.get("contract"));
        assertTrue(((java.util.List<?>) result.get("bindingIssues")).isEmpty());
    }

    @Test
    void whitelistWindowIsRejectedEvenForOwner() {
        topic.setStatus(1);
        topic.setStartDate(new Date(System.currentTimeMillis() + 86_400_000L));
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command(501L), OWNER, OWNER));
        assertEquals(Integer.valueOf(403), rejected.getCode());
        verify(topicMapper, never()).updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void sellingFrozenIsRejected() {
        topic.setStatus(1);
        topic.setLifecycle(3);
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command(501L), OWNER, OWNER));
        assertEquals(Integer.valueOf(403), rejected.getCode());
        verify(topicMapper, never()).updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void strangerCannotBind() {
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command(501L), STRANGER, STRANGER));
        assertEquals(Integer.valueOf(403), rejected.getCode());
        verify(guard, never()).lockTopicContent(anyLong());
        verify(topicMapper, never()).updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void nodeFromAnotherTopicIsRejected() {
        CmsTopicNode foreign = boundNode(501L);
        foreign.setTopicId(999L);
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(foreign);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command(501L), OWNER, OWNER));
        assertEquals(Integer.valueOf(400), rejected.getCode());
        verify(topicMapper, never()).updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void nodeWithoutAddressCannotFillRealPlaceSlot() {
        CmsTopicNode noPlace = boundNode(501L);
        noPlace.setAddress(null);
        noPlace.setLongitude(null);
        noPlace.setLatitude(null);
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(noPlace);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command(501L), OWNER, OWNER));
        assertEquals(Integer.valueOf(400), rejected.getCode());
    }

    @Test
    void staleRevisionIsRejected() {
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));
        TopicLocationBindingCommand command = command(501L);
        command.setExpectedRevision(5);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command, OWNER, OWNER));
        assertEquals(Integer.valueOf(409), rejected.getCode());
    }

    @Test
    void unknownSlotIsRejected() {
        TopicLocationBindingCommand command = command(501L);
        command.setSlotKey("missing");
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command, OWNER, OWNER));
        assertEquals(Integer.valueOf(404), rejected.getCode());
    }

    @Test
    void unbindingClearsNodeAndKeepsSlot() {
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));
        service.bind(command(501L), OWNER, OWNER);
        TopicLocationBindingCommand unbind = command(null);
        unbind.setExpectedRevision(1);

        Map<String, Object> result = service.bind(unbind, OWNER, OWNER);

        var binding = TopicLocationContract.parse(topic.getContentContractJson())
                .path("locationBindings").get(0);
        assertEquals("UNBOUND", binding.path("verificationStatus").asText());
        assertTrue(binding.path("nodeId").isNull());
        assertTrue(((java.util.List<?>) result.get("bindingIssues")).stream()
                .anyMatch(issue -> "LOCATION_SLOT_UNBOUND".equals(((Map<?, ?>) issue).get("code"))));
        assertEquals("501", binding.path("previousBinding").path("nodeId").asText(),
                "解绑后仍保留上一次绑定，便于比对");
    }

    @Test
    void statusReadIsOwnerOnlyAndExposesContract() {
        Map<String, Object> status = service.status(TOPIC_ID, OWNER);
        assertEquals("320100", status.get("cityCode"));
        assertNotNull(status.get("contract"));
        assertEquals("100", status.get("sourceTemplateTopicId"));
        assertThrows(ServiceException.class, () -> service.status(TOPIC_ID, STRANGER));
    }

    @Test
    void fallbackNodesMustBelongToTopic() {
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));
        CmsTopicNode foreignFallback = boundNode(502L);
        foreignFallback.setTopicId(999L);
        when(nodeMapper.selectCmsTopicNodeById(502L)).thenReturn(foreignFallback);
        TopicLocationBindingCommand command = command(501L);
        command.setApprovedFallbackNodeIds(Arrays.asList(502L));
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command, OWNER, OWNER));
        assertEquals(Integer.valueOf(400), rejected.getCode());
    }

    @Test
    void ownerRejectionInSqlIsNotIgnored() {
        // SQL 侧 owner 判定失败(0 行)必须是拒绝,而不是当作已保存 —— 先读后判不能替代写时判定。
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));
        when(topicMapper.updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any())).thenReturn(0);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(command(501L), OWNER, OWNER));
        assertEquals(Integer.valueOf(403), rejected.getCode());
    }

    @Test
    void missingContentFenceFailsClosedInsteadOfWritingUnlocked() {
        ReflectionTestUtils.setField(service, "contentWriteGuard", null);
        assertThrows(IllegalStateException.class,
                () -> service.bind(command(501L), OWNER, OWNER),
                "没有父行 fence 时不得降级成无锁写");
        verify(topicMapper, never()).updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void revisionAdvancesSoASecondWriterMustRefresh() {
        when(nodeMapper.selectCmsTopicNodeById(501L)).thenReturn(boundNode(501L));
        when(nodeMapper.selectCmsTopicNodeById(502L)).thenReturn(boundNode(502L));

        service.bind(command(501L), OWNER, OWNER);
        assertEquals(1, TopicLocationContract.parse(topic.getContentContractJson())
                .path("locationBindings").get(0).path("bindingRevision").asInt());

        TopicLocationBindingCommand second = command(502L);
        second.setExpectedRevision(1);
        service.bind(second, OWNER, OWNER);
        assertEquals("502", TopicLocationContract.parse(topic.getContentContractJson())
                .path("locationBindings").get(0).path("nodeId").asText());

        // 旧 revision 不能再写一次。
        TopicLocationBindingCommand stale = command(501L);
        stale.setExpectedRevision(1);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.bind(stale, OWNER, OWNER));
        assertEquals(Integer.valueOf(409), rejected.getCode());
    }
}

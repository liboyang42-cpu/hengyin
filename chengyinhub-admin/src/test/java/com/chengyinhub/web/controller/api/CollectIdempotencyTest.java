package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.MemberCollectibleMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * collect 幂等性：并发唯一键冲突静默，非 DuplicateKeyException 必须传播不能吞。
 */
class CollectIdempotencyTest {

    private static final Long MEMBER = 88L;
    private static final Long TOPIC = 300L;
    private static final Long NODE = 401L;

    private ApiPlayProgressController controller;
    private MemberCollectibleMapper collectibleMapper;

    @BeforeEach
    void setUp() {
        collectibleMapper = mock(MemberCollectibleMapper.class);
        controller = new ApiPlayProgressController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper", collectibleMapper);
    }

    private void invokeCollect(Long memberId, int type, Long topicId, Long refId, String content) {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "collect",
                memberId, type, topicId, refId, content);
    }

    @Test
    void duplicateKeyException_isSilentlyTolerated() {
        when(collectibleMapper.countOne(anyLong(), anyInt(), anyLong())).thenReturn(0);
        doThrow(new DuplicateKeyException("Duplicate entry for uk_member_collectible"))
                .when(collectibleMapper).insertMemberCollectible(any());

        assertDoesNotThrow(() ->
                invokeCollect(MEMBER, 1, TOPIC, NODE, "碎片内容"));
    }

    @Test
    void nonDuplicateKeyException_propagates() {
        when(collectibleMapper.countOne(anyLong(), anyInt(), anyLong())).thenReturn(0);
        RuntimeException dbError = new RuntimeException("DB连接失败");
        doThrow(dbError).when(collectibleMapper).insertMemberCollectible(any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                invokeCollect(MEMBER, 1, TOPIC, NODE, "碎片内容"));
        org.junit.jupiter.api.Assertions.assertSame(dbError, thrown);
    }

    @Test
    void existingRecord_skipsInsertEntirely() {
        when(collectibleMapper.countOne(eq(MEMBER), eq(2), eq(NODE))).thenReturn(1);

        invokeCollect(MEMBER, 2, TOPIC, NODE, "彩蛋内容");

        verify(collectibleMapper, never()).insertMemberCollectible(any());
    }

    @Test
    void guardClause_nullMemberId_skipsInsert() {
        invokeCollect(null, 1, TOPIC, NODE, null);

        verify(collectibleMapper, never()).countOne(anyLong(), anyInt(), anyLong());
        verify(collectibleMapper, never()).insertMemberCollectible(any());
    }

    @Test
    void guardClause_nonPositiveMemberId_skipsInsert() {
        invokeCollect(0L, 1, TOPIC, NODE, null);

        verify(collectibleMapper, never()).countOne(anyLong(), anyInt(), anyLong());
        verify(collectibleMapper, never()).insertMemberCollectible(any());
    }

    @Test
    void guardClause_nullRefId_skipsInsert() {
        invokeCollect(MEMBER, 1, TOPIC, null, null);

        verify(collectibleMapper, never()).countOne(anyLong(), anyInt(), anyLong());
        verify(collectibleMapper, never()).insertMemberCollectible(any());
    }
}

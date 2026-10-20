package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-SCAN-CODE 验收:商家只能为自己能操作的点位签发游戏打卡码。
 *
 * <p>签发判据 = 海报码归属({@code posterCodeNode})或本商家在该节点有 READY/ACTIVE 站点
 * ({@code countMerchantOperableStation})。两条都不成立时必须原样抛出归属拒绝,
 * 且不得写入 Redis 活码、不得出图 —— 否则别家商家能刷掉本店的活码。
 */
class PlayCheckinCodeOwnershipAcceptanceTest {

    private static final String SECRET = "play-checkin-acceptance-secret";
    private static final Long OWNER = 8801L;
    private static final Long STRANGER = 8802L;
    private static final Long NODE_ID = 77L;

    private IChapterMerchantNodeService chapterNodes;
    private ICmsTopicNodeService nodes;
    private GameSessionRuntimeMapper gameSessions;
    private RedisCache redis;
    private IQrcodeService qrcodeService;
    private PlayCheckinCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        chapterNodes = mock(IChapterMerchantNodeService.class);
        nodes = mock(ICmsTopicNodeService.class);
        gameSessions = mock(GameSessionRuntimeMapper.class);
        redis = mock(RedisCache.class);
        qrcodeService = mock(IQrcodeService.class);
        service = new PlayCheckinCodeServiceImpl(chapterNodes, nodes, gameSessions, redis, qrcodeService, SECRET);
        when(qrcodeService.generateQrcode(anyString(), anyInt(), anyInt())).thenReturn("https://oss/live-qr.png");
        when(chapterNodes.posterCodeNode(OWNER, NODE_ID)).thenReturn(node());
        when(nodes.selectCmsTopicNodeById(NODE_ID)).thenReturn(node());
    }

    @Test
    void ownerIssuesCodeBoundToTheChosenNodeWithSixtySecondExpiry() {
        long before = System.currentTimeMillis();
        IPlayCheckinCodeService.Issued issued = service.issue(OWNER, NODE_ID);
        long after = System.currentTimeMillis();

        assertEquals(NODE_ID, issued.nodeId);
        VerifyDynCodeUtil.Result parsed = VerifyDynCodeUtil.verify(issued.code, System.currentTimeMillis(), SECRET);
        assertTrue(parsed.valid);
        assertEquals(NODE_ID, parsed.regId, "码必须解析回商家选中的点位");
        assertEquals(PlayCheckinCodeServiceImpl.TYPE, parsed.type);
        long expiresAt = Long.parseLong(issued.code.split("\\.")[3]);
        assertTrue(expiresAt >= before + 60_000L && expiresAt <= after + 60_000L,
                "到期时刻 = 签发时刻 + 60s");
        verify(redis).setCacheObject(eq("play:checkin:live:" + NODE_ID), eq(issued.code), eq(60), eq(TimeUnit.SECONDS));
        // 自有点位走海报归属,不需要退到站点判据
        verify(gameSessions, never()).countMerchantOperableStation(anyLong(), anyLong());
    }

    @Test
    void nonOwnerWithoutOperableStationIsRefusedAndNothingIsWritten() {
        IllegalStateException denied = new IllegalStateException("点位不存在或无权操作");
        when(chapterNodes.posterCodeNode(STRANGER, NODE_ID)).thenThrow(denied);
        when(gameSessions.countMerchantOperableStation(STRANGER, NODE_ID)).thenReturn(0);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.issue(STRANGER, NODE_ID));

        assertSame(denied, thrown, "必须原样抛出归属拒绝");
        verify(gameSessions).countMerchantOperableStation(STRANGER, NODE_ID);
        verify(nodes, never()).selectCmsTopicNodeById(any());
        verify(redis, never()).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
        verify(qrcodeService, never()).generateQrcode(anyString(), anyInt(), anyInt());
    }

    @Test
    void operableStationButMissingNodeIsStillRefused() {
        IllegalStateException denied = new IllegalStateException("点位不存在或无权操作");
        when(chapterNodes.posterCodeNode(STRANGER, NODE_ID)).thenThrow(denied);
        when(gameSessions.countMerchantOperableStation(STRANGER, NODE_ID)).thenReturn(1);
        when(nodes.selectCmsTopicNodeById(NODE_ID)).thenReturn(null);

        assertSame(denied, assertThrows(IllegalStateException.class, () -> service.issue(STRANGER, NODE_ID)));
        verify(redis, never()).setCacheObject(anyString(), any(), anyInt(), any(TimeUnit.class));
        verify(qrcodeService, never()).generateQrcode(anyString(), anyInt(), anyInt());
    }

    private static CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setName("毛孩子咖啡");
        node.setMerchantMemberId(OWNER);
        return node;
    }
}

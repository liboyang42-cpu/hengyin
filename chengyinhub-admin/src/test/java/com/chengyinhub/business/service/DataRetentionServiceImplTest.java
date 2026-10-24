package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.BizEventLogMapper;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.mapper.TmsBrowseRecordMapper;
import com.chengyinhub.business.service.impl.DataRetentionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 留存清理服务单测:验证分批循环(删干净即停 / 达 maxBatches 护栏即停)、总数累加、非法参数短路。
 */
class DataRetentionServiceImplTest {

    private TmsBrowseRecordMapper browseMapper;
    private BizEventLogMapper eventMapper;
    private NpcChatLogMapper npcChatMapper;
    private NpcChatRequestMapper npcChatRequestMapper;
    private DataRetentionServiceImpl svc;
    private Date cutoff;

    @BeforeEach
    void setUp() {
        browseMapper = mock(TmsBrowseRecordMapper.class);
        eventMapper = mock(BizEventLogMapper.class);
        npcChatMapper = mock(NpcChatLogMapper.class);
        npcChatRequestMapper = mock(NpcChatRequestMapper.class);
        svc = new DataRetentionServiceImpl();
        ReflectionTestUtils.setField(svc, "tmsBrowseRecordMapper", browseMapper);
        ReflectionTestUtils.setField(svc, "bizEventLogMapper", eventMapper);
        ReflectionTestUtils.setField(svc, "npcChatLogMapper", npcChatMapper);
        ReflectionTestUtils.setField(svc, "npcChatRequestMapper", npcChatRequestMapper);
        ReflectionTestUtils.setField(svc,"npcAsrIntents",mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        cutoff = new Date();
    }

    @Test
    void cleanBrowse_deletesUntilBatchNotFull_thenStops() {
        // 三批:1000、1000、300 → 第三批 <batchSize,停;共 2300,调 3 次
        when(browseMapper.deleteBrowseRecordsBefore(any(), eq(1000)))
                .thenReturn(1000).thenReturn(1000).thenReturn(300);

        int total = svc.cleanBrowseRecords(cutoff, 1000, 500);

        assertEquals(2300, total);
        verify(browseMapper, times(3)).deleteBrowseRecordsBefore(any(), eq(1000));
    }

    @Test
    void cleanBrowse_stopsAtMaxBatchesGuard() {
        // 每批都满(1000),maxBatches=3 → 只删 3 批 = 3000,不无限循环
        when(browseMapper.deleteBrowseRecordsBefore(any(), eq(1000))).thenReturn(1000);

        int total = svc.cleanBrowseRecords(cutoff, 1000, 3);

        assertEquals(3000, total);
        verify(browseMapper, times(3)).deleteBrowseRecordsBefore(any(), eq(1000));
    }

    @Test
    void cleanBrowse_emptyFirstBatch_stopsImmediately() {
        when(browseMapper.deleteBrowseRecordsBefore(any(), eq(1000))).thenReturn(0);

        int total = svc.cleanBrowseRecords(cutoff, 1000, 500);

        assertEquals(0, total);
        verify(browseMapper, times(1)).deleteBrowseRecordsBefore(any(), eq(1000));
    }

    @Test
    void invalidParams_shortCircuit_noMapperCall() {
        assertEquals(0, svc.cleanBrowseRecords(null, 1000, 500));
        assertEquals(0, svc.cleanBrowseRecords(cutoff, 0, 500));
        assertEquals(0, svc.cleanBrowseRecords(cutoff, 1000, 0));
        verify(browseMapper, never()).deleteBrowseRecordsBefore(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void cleanBizEvent_deletesUntilBatchNotFull() {
        when(eventMapper.deleteBizEventLogsBefore(any(), eq(500)))
                .thenReturn(500).thenReturn(120);

        int total = svc.cleanBizEventLogs(cutoff, 500, 500);

        assertEquals(620, total);
        verify(eventMapper, times(2)).deleteBizEventLogsBefore(any(), eq(500));
    }

    @Test
    void cleanNpcChat_deletesUntilBatchNotFull_thenStops() {
        // 两批:1000、400 → 第二批 <batchSize,停;共 1400,调 2 次
        when(npcChatMapper.deleteNpcChatLogsBefore(any(), eq(1000)))
                .thenReturn(1000).thenReturn(400);

        int total = svc.cleanNpcChatLogs(cutoff, 1000, 500);

        assertEquals(1400, total);
        verify(npcChatMapper, times(2)).deleteNpcChatLogsBefore(any(), eq(1000));
    }

    @Test
    void cleanNpcChat_stopsAtMaxBatchesGuard() {
        // 每批都满(1000),maxBatches=3 → 只删 3 批 = 3000,不无限循环
        when(npcChatMapper.deleteNpcChatLogsBefore(any(), eq(1000))).thenReturn(1000);

        int total = svc.cleanNpcChatLogs(cutoff, 1000, 3);

        assertEquals(3000, total);
        verify(npcChatMapper, times(3)).deleteNpcChatLogsBefore(any(), eq(1000));
    }

    @Test
    void cleanNpcChat_invalidParams_shortCircuit_noMapperCall() {
        assertEquals(0, svc.cleanNpcChatLogs(null, 1000, 500));
        assertEquals(0, svc.cleanNpcChatLogs(cutoff, 0, 500));
        assertEquals(0, svc.cleanNpcChatLogs(cutoff, 1000, 0));
        verify(npcChatMapper, never()).deleteNpcChatLogsBefore(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void cleanNpcChatRequests_deletesShortReplayStateInBatches() {
        when(npcChatRequestMapper.deleteNpcChatRequestsBefore(any(), eq(1000)))
                .thenReturn(1000).thenReturn(4);

        int total = svc.cleanNpcChatRequests(cutoff, 1000, 500);

        assertEquals(1004, total);
        verify(npcChatRequestMapper, times(2)).deleteNpcChatRequestsBefore(any(), eq(1000));
    }
}

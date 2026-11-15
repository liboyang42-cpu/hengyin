package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IRandomDispatchService;
import com.chengyinhub.business.service.PlayScanEntry;
import com.chengyinhub.business.service.impl.PlayScanEntryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlayBasicCommandRandomDispatchTest {

    @Test
    void scanEntryDispatchesOnlyOnPlayActionAndPreservesEntryNodeId() {
        PlayBasicCommandApplication app = new PlayBasicCommandApplication();
        PlayScanEntryService scanEntryService = mock(PlayScanEntryService.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        IRandomDispatchService dispatchService = mock(IRandomDispatchService.class);
        ReflectionTestUtils.setField(app, "playScanEntryService", scanEntryService);
        ReflectionTestUtils.setField(app, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(app, "randomDispatchService", dispatchService);

        CmsTopicNode entryNode = new CmsTopicNode();
        entryNode.setId(501L);
        entryNode.setName("入口门店");
        when(nodeService.selectCmsTopicNodeById(501L)).thenReturn(entryNode);

        when(scanEntryService.resolve(88L, "CODE-PLAY")).thenReturn(PlayScanEntry.play(10L, 501L, 900L));
        when(dispatchService.dispatchForEntryNode(88L, entryNode))
                .thenReturn(Map.of("poolId", 7L, "nodeId", 502L, "nodeName", "盲盒手冲任务"));

        AjaxResult playResult = app.scanEntry(88L, "CODE-PLAY");
        assertTrue(playResult.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> playData = (Map<String, Object>) playResult.get("data");
        assertEquals(501L, playData.get("nodeId"));
        assertNotNull(playData.get("dispatchedTask"));

        // 未购买仅落购票页时，不得提前消耗/派发盲盒任务
        when(scanEntryService.resolve(88L, "CODE-BUY")).thenReturn(PlayScanEntry.purchase(10L, 501L));
        AjaxResult buyResult = app.scanEntry(88L, "CODE-BUY");
        assertTrue(buyResult.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> buyData = (Map<String, Object>) buyResult.get("data");
        assertNull(buyData.get("dispatchedTask"));
        verify(dispatchService, times(1)).dispatchForEntryNode(eq(88L), any());
    }

    @Test
    void attachDispatchedTaskPreservesExistingReceiptKeys() {
        PlayBasicCommandApplication app = new PlayBasicCommandApplication();
        IRandomDispatchService dispatchService = mock(IRandomDispatchService.class);
        ReflectionTestUtils.setField(app, "randomDispatchService", dispatchService);

        CmsTopicNode entryNode = new CmsTopicNode();
        entryNode.setId(501L);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("nodeId", 501L);
        when(dispatchService.dispatchForEntryNode(88L, entryNode))
                .thenReturn(Map.of("poolId", 7L, "nodeId", 503L));

        ReflectionTestUtils.invokeMethod(app, "attachDispatchedTask", receipt, 88L, entryNode);
        assertEquals(501L, receipt.get("nodeId"));
        assertNotNull(receipt.get("dispatchedTask"));
    }
}

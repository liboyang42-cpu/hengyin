package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 节点级前置解锁闸(unlock_after_node_id):
 * ①无前置放行;②前置无进度→拦;③前置只有 mode2 进店行(status=0)→仍拦(进店≠完成);
 * ④前置完成(status=1)→放行。拦截文案须带前置节点名,不是泛化报错。
 */
class ApiPlayUnlockGateTest {

    private static final Long MEMBER = 88L;
    private static final Long ACT = 500L;
    private static final Long PREV_NODE = 401L;

    private ApiPlayProgressController controller;
    private IPlayerNodeProgressService progressService;
    private ICmsTopicNodeService nodeService;

    @BeforeEach
    void setUp() {
        controller = new ApiPlayProgressController();
        progressService = mock(IPlayerNodeProgressService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);

        CmsTopicNode prev = new CmsTopicNode();
        prev.setId(PREV_NODE);
        prev.setName("第一件:门口");
        when(nodeService.selectCmsTopicNodeById(PREV_NODE)).thenReturn(prev);
    }

    private String gate(Long unlockAfter) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(402L);
        node.setUnlockAfterNodeId(unlockAfter);
        return com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "unlockGateError", MEMBER, ACT, node);
    }

    private PlayerNodeProgress progress(int status) {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setNodeId(PREV_NODE);
        p.setStatus(status);
        return p;
    }

    @Test
    void noPrerequisite_passes() {
        assertNull(gate(null));
        assertNull(gate(0L));
    }

    @Test
    void prerequisiteWithoutProgress_blocksWithNodeName() {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        String err = gate(PREV_NODE);
        assertNotNull(err);
        assertTrue(err.contains("第一件:门口"), "拦截文案须带前置节点名,实际:" + err);
    }

    @Test
    void arrivedButNotCompleted_stillBlocks() {
        // mode2 扫码进店建 status=0 行 —— 进店不等于完成前置玩法
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(progress(0)));
        assertNotNull(gate(PREV_NODE));
    }

    @Test
    void completedPrerequisite_passes() {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(progress(1)));
        assertNull(gate(PREV_NODE));
    }

    @Test
    void preferenceFlow_reusesTheSameUnlockGate() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/chengyinhub/web/service/play/PlayPreferenceApplication.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf("public PreferencePlayCtx resolvePreferencePlayCtx");
        int end = source.indexOf("\n    }\n}", start);
        assertTrue(start >= 0 && end > start, "偏好玩法上下文解析器必须存在");
        String body = source.substring(start, end);
        assertTrue(body.contains("unlockGateError(memberId, out.playCtx.actId, out.node)"),
                "VM6 不得绕过任意模式都生效的 unlock_after_node_id 硬闸");
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.TrackDraft;
import com.chengyinhub.business.mapper.TrackDraftMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-12:/api/track/to-topic 对坏 waypointJson 必须 fail-closed。
 *
 * 旧行为 = catch(Exception ignore) 后仍以 nodes=[] 把 status 改成 1 并返回 success ——
 * 用户的停靠点全部静默丢失,草稿却被标记为「已转路线」不可回头。
 * 新行为 = 解析失败即返回失败、且不执行 status=1 更新;合法/空 waypointJson 的成功路径不变。
 */
class ApiTrackControllerToTopicFailClosedTest {

    private static final long ME = 42L;
    private static final long DRAFT_ID = 9001L;

    private ApiTrackController controller;
    private TrackDraftMapper trackDraftMapper;
    private TrackDraft draft;

    @BeforeEach
    void setUp() {
        trackDraftMapper = mock(TrackDraftMapper.class);
        controller = spy(new ApiTrackController());
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "trackDraftMapper", trackDraftMapper);

        draft = new TrackDraft();
        draft.setId(DRAFT_ID);
        draft.setMemberId(ME);
        draft.setName("周末骑行");
        draft.setPolyline("gfo}EtohhU");
        when(trackDraftMapper.selectTrackDraftById(DRAFT_ID)).thenReturn(draft);
    }

    private AjaxResult toTopicWith(String waypointJson) {
        draft.setWaypointJson(waypointJson);
        return controller.toTopic(String.valueOf(DRAFT_ID));
    }

    private void assertFailClosed(AjaxResult r) {
        assertEquals(500, r.get(AjaxResult.CODE_TAG), "解析失败必须返回失败响应");
        assertEquals("轨迹停靠点数据异常,无法转为路线", r.get(AjaxResult.MSG_TAG),
                "必须是停靠点解析这道闸的文案,且不得回传原始异常文本");
        verify(trackDraftMapper, never()).updateTrackDraft(any(TrackDraft.class));
    }

    /** 非法 JSON:parseArray 直接抛,必须失败且不标记已转路线。 */
    @Test
    void malformedJson_failsClosed_andDoesNotMarkConverted() {
        assertFailClosed(toTopicWith("not-a-json"));
    }

    /** waypointJson 是对象而非数组:必须失败且不标记已转路线。 */
    @Test
    void nonArrayJson_failsClosed_andDoesNotMarkConverted() {
        assertFailClosed(toTopicWith("{\"lng\":121.0}"));
    }

    /** 数组元素不是对象:fastjson2 返回 null 而非抛异常,靠显式 null 闸拦下。 */
    @Test
    void nonObjectElement_failsClosed_andDoesNotMarkConverted() {
        assertFailClosed(toTopicWith("[1,2]"));
    }

    /** 字段结构异常:fastjson2 的 getString() 遇到嵌套对象/数组不会抛,而是返回序列化后的 JSON,
     * w.get("lng") 更是直接把 JSONObject 塞进节点 —— 光靠 catch 兜不住,必须显式拒绝。 */
    @Test
    void nestedObjectFieldValue_failsClosed_andDoesNotMarkConverted() {
        assertFailClosed(toTopicWith("[{\"name\":{\"a\":1},\"lng\":121.5,\"lat\":31.2}]"));
    }

    /** 经纬度是嵌套结构时同样必须 fail-closed,不能把 JSONObject 当坐标下发。 */
    @Test
    void nestedGeoFieldValue_failsClosed_andDoesNotMarkConverted() {
        assertFailClosed(toTopicWith("[{\"name\":\"起点\",\"lng\":{\"x\":1},\"lat\":[31.2]}]"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validArray_stillSucceeds_withNodeMappingAndStatusUpdate() {
        AjaxResult r = toTopicWith("[{\"name\":\"起点\",\"lng\":121.5,\"lat\":31.2,"
                + "\"photo\":\"a.jpg\",\"note\":\"打卡\"},{\"lng\":121.6,\"lat\":31.3}]");

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        Map<String, Object> fabuDraft = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertNotNull(fabuDraft);
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) fabuDraft.get("chapters");
        Map<String, Object> chapter = chapters.get(0);
        assertEquals("gfo}EtohhU", chapter.get("routeGeometry"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) chapter.get("nodes");
        assertEquals(2, nodes.size());
        assertEquals("起点", nodes.get(0).get("name"));
        assertEquals("a.jpg", nodes.get(0).get("imgUrl"));
        assertEquals("打卡", nodes.get(0).get("description"));
        assertEquals(1, nodes.get(0).get("sortId"));
        assertEquals("停靠点 2", nodes.get(1).get("name"), "缺 name 时兜底命名不变");

        verify(trackDraftMapper, times(1)).updateTrackDraft(any(TrackDraft.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankWaypointJson_stillSucceeds_withEmptyNodes() {
        AjaxResult r = toTopicWith("   ");

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        Map<String, Object> fabuDraft = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) fabuDraft.get("chapters");
        assertTrue(((List<?>) chapters.get(0).get("nodes")).isEmpty());
        verify(trackDraftMapper, times(1)).updateTrackDraft(any(TrackDraft.class));
    }

    /** 归属校验不受本次改动影响。 */
    @Test
    void foreignDraft_stillRejected() {
        draft.setMemberId(ME + 1);
        AjaxResult r = toTopicWith("[]");
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("无权操作", r.get(AjaxResult.MSG_TAG));
        verify(trackDraftMapper, never()).updateTrackDraft(any(TrackDraft.class));
    }
}

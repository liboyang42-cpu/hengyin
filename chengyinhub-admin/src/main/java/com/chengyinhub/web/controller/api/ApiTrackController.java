package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.TrackDraft;
import com.chengyinhub.business.mapper.TrackDraftMapper;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * M8 以走代编 · 轨迹草稿。
 * 说明:轨迹点批量写 player_lit_block(source_type=2)依赖 M4 漫游,本期先不接。
 */
@RestController
@Tag(name = "M8 轨迹草稿")
@RequestMapping("/api/track")
public class ApiTrackController extends BaseController {

    @Autowired
    private TrackDraftMapper trackDraftMapper;

    /**
     * 字段结构校验:fastjson2 的 getString() 遇到嵌套对象/数组不抛异常,而是返回序列化后的 JSON,
     * w.get("lng") 更会把整个 JSONObject 塞进节点 —— 只靠 catch 兜不住,必须显式拒绝。
     */
    private static void requireScalarFields(JSONObject w, String... keys) {
        for (String k : keys) {
            Object v = w.get(k);
            if (v instanceof Map || v instanceof Collection) {
                throw new IllegalArgumentException("waypoint field is not scalar: " + k);
            }
        }
    }

    private Long uid() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Operation(summary = "保存轨迹草稿")
    @PostMapping("/draft")
    public AjaxResult saveDraft(@RequestBody TrackDraft draft) {
        Long memberId = uid();
        if (memberId <= 0) {
            return error("请先登录");
        }
        Date now = new Date();
        if (draft.getId() != null) {
            // 更新:归属校验
            TrackDraft old = trackDraftMapper.selectTrackDraftById(draft.getId());
            if (old == null || OwnershipUtils.notOwner(old.getMemberId(), memberId)) {
                return error("无权操作");
            }
            draft.setUpdatedAt(now);
            trackDraftMapper.updateTrackDraft(draft);
            return AjaxResult.success("已保存", draft.getId());
        }
        draft.setMemberId(memberId);
        if (draft.getStatus() == null) {
            draft.setStatus(0); // 草稿
        }
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        trackDraftMapper.insertTrackDraft(draft);
        return AjaxResult.success("已保存", draft.getId());
    }

    @Operation(summary = "我的轨迹草稿列表")
    @PostMapping("/drafts")
    public AjaxResult myDrafts() {
        Long memberId = uid();
        if (memberId <= 0) {
            return error("请先登录");
        }
        TrackDraft probe = new TrackDraft();
        probe.setMemberId(memberId);
        List<TrackDraft> list = trackDraftMapper.selectTrackDraftList(probe);
        // 不下发大字段 polyline/waypoint 明细的完整体,列表够用即可(此处直接返回,前端按需取详情)
        return success(list);
    }

    @Operation(summary = "轨迹转路线草稿(返回 fabu 预填结构)")
    @PostMapping("/to-topic")
    public AjaxResult toTopic(String id) {
        Long memberId = uid();
        if (memberId <= 0) {
            return error("请先登录");
        }
        Long draftId = com.chengyinhub.common.core.text.Convert.toLong(id, 0L);
        TrackDraft d = draftId > 0 ? trackDraftMapper.selectTrackDraftById(draftId) : null;
        if (d == null) {
            return error("草稿不存在");
        }
        if (OwnershipUtils.notOwner(d.getMemberId(), memberId)) {
            return error("无权操作");
        }

        // 停靠点 → 节点;抽稀轨迹 → 章节 routeGeometry;默认 1 章
        List<Map<String, Object>> nodes = new ArrayList<>();
        try {
            if (d.getWaypointJson() != null && !d.getWaypointJson().trim().isEmpty()) {
                JSONArray arr = JSON.parseArray(d.getWaypointJson());
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject w = arr.getJSONObject(i);
                    if (w == null) {
                        // 元素不是对象时 fastjson2 返回 null 而非抛异常
                        throw new IllegalArgumentException("waypoint element is not an object");
                    }
                    requireScalarFields(w, "name", "lng", "lat", "photo", "note");
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("name", w.getString("name") != null ? w.getString("name") : ("停靠点 " + (i + 1)));
                    node.put("longitude", w.get("lng"));
                    node.put("latitude", w.get("lat"));
                    node.put("imgUrl", w.getString("photo"));
                    node.put("description", w.getString("note"));
                    node.put("sortId", i + 1);
                    nodes.add(node);
                }
            }
        } catch (Exception e) {
            // fail-closed:停靠点解析失败时绝不能吞掉——旧行为会以 nodes=[] 把草稿标成「已转路线」,
            // 用户的停靠点静默全失且不可回头。只记内部 ID 与异常类型,不落原始 body、不回传异常文本。
            logger.warn("track_to_topic_waypoint_parse_failed draftId={} memberId={} err={}",
                    d.getId(), memberId, e.getClass().getSimpleName());
            return error("轨迹停靠点数据异常,无法转为路线");
        }

        Map<String, Object> chapter = new LinkedHashMap<>();
        chapter.put("name", "第一章");
        chapter.put("routeGeometry", d.getPolyline());
        chapter.put("nodes", nodes);

        Map<String, Object> fabuDraft = new LinkedHashMap<>();
        fabuDraft.put("name", d.getName());
        fabuDraft.put("fromTrackId", d.getId());
        fabuDraft.put("totalMileage", d.getDistanceM());
        fabuDraft.put("chapters", new ArrayList<>(java.util.Collections.singletonList(chapter)));
        fabuDraft.put("hint", "AI 将为停靠点生成玩法");

        // 标记已转路线
        TrackDraft upd = new TrackDraft();
        upd.setId(d.getId());
        upd.setStatus(1);
        upd.setUpdatedAt(new Date());
        trackDraftMapper.updateTrackDraft(upd);

        return success(fabuDraft);
    }

    @Operation(summary = "删除轨迹草稿(软删)")
    @PostMapping("/delete")
    public AjaxResult delete(String id) {
        Long memberId = uid();
        if (memberId <= 0) {
            return error("请先登录");
        }
        Long draftId = com.chengyinhub.common.core.text.Convert.toLong(id, 0L);
        TrackDraft d = draftId > 0 ? trackDraftMapper.selectTrackDraftById(draftId) : null;
        if (d == null) {
            return error("草稿不存在");
        }
        if (OwnershipUtils.notOwner(d.getMemberId(), memberId)) {
            return error("无权操作");
        }
        TrackDraft upd = new TrackDraft();
        upd.setId(draftId);
        upd.setStatus(2);
        upd.setUpdatedAt(new Date());
        trackDraftMapper.updateTrackDraft(upd);
        return AjaxResult.success("已删除");
    }
}

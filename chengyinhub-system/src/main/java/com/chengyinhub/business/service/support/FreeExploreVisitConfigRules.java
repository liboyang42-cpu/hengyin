package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.FreeExploreVisitConfig;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.OwnershipUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FE-STORY 7.10:自由探索作者逐个地点选到访方式 —— GPS(默认,半径 300–500 米)/ 扫码 / 仅线上。
 * 只有主题作者本人可读写;只改 cms_topic_node.visit_check / visit_radius_m 两列,不碰内容与审核状态。
 * 纯规则类,由 {@code ApiTopicController} 以既有依赖构造调用(不新增 Spring 边界)。
 */
public final class FreeExploreVisitConfigRules {

    public static final String VISIT_CHECK_INVALID = "VISIT_CHECK_INVALID";
    public static final String VISIT_RADIUS_INVALID = "VISIT_RADIUS_INVALID";
    public static final String VISIT_GPS_COORD_REQUIRED = "VISIT_GPS_COORD_REQUIRED";
    public static final String NOT_FREE_EXPLORE = "NOT_FREE_EXPLORE";

    private final ICmsTopicService cmsTopicService;
    private final ICmsTopicNodeService cmsTopicNodeService;
    private final CmsTopicNodeMapper cmsTopicNodeMapper;

    public FreeExploreVisitConfigRules(ICmsTopicService cmsTopicService, ICmsTopicNodeService cmsTopicNodeService,
                                       CmsTopicNodeMapper cmsTopicNodeMapper) {
        this.cmsTopicService = cmsTopicService;
        this.cmsTopicNodeService = cmsTopicNodeService;
        this.cmsTopicNodeMapper = cmsTopicNodeMapper;
    }

    public AjaxResult list(Long memberId, Long topicId) {
        AjaxResult denied = authorGate(memberId, topicId);
        if (denied != null) return denied;
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        List<CmsTopicNode> nodes = cmsTopicNodeService.selectCmsTopicNodeList(probe);
        Map<Long, FreeExploreVisitConfig> configs = new HashMap<>();
        List<FreeExploreVisitConfig> rows = cmsTopicNodeMapper.selectVisitConfigByTopicId(topicId);
        if (rows != null) for (FreeExploreVisitConfig row : rows) if (row != null && row.getNodeId() != null) configs.put(row.getNodeId(), row);
        List<Map<String, Object>> out = new ArrayList<>();
        if (nodes != null) for (CmsTopicNode node : nodes) {
            if (node == null || node.getId() == null) continue;
            out.add(view(node, configs.get(node.getId())));
        }
        return AjaxResult.success(out);
    }

    public AjaxResult update(Long memberId, Long nodeId, String visitCheck, Integer visitRadius) {
        if (nodeId == null || nodeId <= 0L) return AjaxResult.error("参数有误");
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTopicId() == null) return AjaxResult.error(404, "地点不存在");
        AjaxResult denied = authorGate(memberId, node.getTopicId());
        if (denied != null) return denied;
        Integer check = parseCheck(visitCheck);
        if (check == null) return reject("到访方式只能是 GPS、扫码或仅线上", VISIT_CHECK_INVALID);
        Integer radius = null;
        if (check == FreeExploreVisit.CHECK_GPS && visitRadius != null) {
            if (visitRadius < ArrivalPolicy.VISIT_MIN_RADIUS_METERS || visitRadius > ArrivalPolicy.VISIT_MAX_RADIUS_METERS) {
                return reject("范围只能是 300 到 500 米", VISIT_RADIUS_INVALID);
            }
            radius = visitRadius;
        }
        if (check == FreeExploreVisit.CHECK_GPS && !hasCoordinates(node)) {
            return reject("这里还没有坐标，请先选位置或改为扫码", VISIT_GPS_COORD_REQUIRED);
        }
        if (cmsTopicNodeMapper.updateVisitConfig(nodeId, check, radius) != 1) {
            return AjaxResult.error("保存失败，请重试");
        }
        return AjaxResult.success(view(node, new FreeExploreVisitConfig(nodeId, check, radius)));
    }

    /** 只有主题作者本人;非自由探索主题不提供这项设置。 */
    private AjaxResult authorGate(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0L) return AjaxResult.error(401, "请先登录");
        if (topicId == null || topicId <= 0L) return AjaxResult.error("参数有误");
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || !OwnershipUtils.isOwner(topic.getMemberId(), memberId)) {
            return AjaxResult.error(403, "只有主题作者可以设置到访方式");
        }
        if (!Integer.valueOf(PlayMode.FREE_COLLECT).equals(topic.getProductType())) {
            return reject("只有自由探索主题可以设置到访方式", NOT_FREE_EXPLORE);
        }
        return null;
    }

    private static AjaxResult reject(String message, String code) {
        return AjaxResult.error(message).put("errorCode", code);
    }

    static Integer parseCheck(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        switch (raw.trim().toUpperCase()) {
            case "GPS": return FreeExploreVisit.CHECK_GPS;
            case "SCAN": return FreeExploreVisit.CHECK_SCAN;
            case "ONLINE": return FreeExploreVisit.CHECK_ONLINE;
            default: return null;
        }
    }

    private static boolean hasCoordinates(CmsTopicNode node) {
        return StringUtils.isNotBlank(node.getLatitude()) && StringUtils.isNotBlank(node.getLongitude());
    }

    private static Map<String, Object> view(CmsTopicNode node, FreeExploreVisitConfig config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node.getId());
        m.put("name", node.getName());
        m.put("chapterId", node.getChapterId());
        m.put("visitCheck", FreeExploreVisit.checkLabel(config == null ? null : config.getVisitCheck()));
        Integer radius = config == null ? null : config.getVisitRadiusM();
        m.put("visitRadius", FreeExploreVisit.normalizeCheck(config == null ? null : config.getVisitCheck()) == FreeExploreVisit.CHECK_GPS ? radius : null);
        m.put("hasCoordinates", hasCoordinates(node));
        return m;
    }
}

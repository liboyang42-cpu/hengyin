package com.chengyinhub.business.service;

import java.util.List;

import com.chengyinhub.business.domain.CmsTopicNode;

/**
 * 主题路线服务：把章节内有序节点连成"沿城市道路"的路线坐标。
 *
 * @author chengyinhub
 */
public interface ITopicRouteService
{
    /**
     * 根据有序节点（按 sort_id）生成沿路路线坐标 JSON。
     *
     * @param orderedNodes 已按 sort_id 升序排好的节点列表
     * @return JSON 字符串 [{"latitude":..,"longitude":..}, ...]；
     *         未配置 Key / 已禁用 / 任一段失败或未知 时返回 null，不能返回混有直连的供应商路径。
     *         界面可另画明确标注的节点直线预览，不能用它作为道路距离、通关或奖励依据。
     */
    String buildGeometryJson(List<? extends CmsTopicNode> orderedNodes);

    /**
     * MPP-7：玩家当前位置到一站的步行路线（腾讯路线规划 walking，Key 只在后端）。
     * 起点按 20 米取整后缓存 60 秒，同一站附近反复重算不会刷配额。
     *
     * @return 路线；腾讯地图客户端未配置、供应商拒绝或坐标非法时返回 null（调用方回 ROUTE_UNAVAILABLE）
     */
    default WalkRoute walkRoute(double fromLat, double fromLng, double toLat, double toLng)
    {
        return null;
    }
}

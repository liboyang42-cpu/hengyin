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
     *         未配置 Key / 已禁用 / 计算失败 时返回 null（前端用节点直连兜底）
     */
    String buildGeometryJson(List<? extends CmsTopicNode> orderedNodes);
}

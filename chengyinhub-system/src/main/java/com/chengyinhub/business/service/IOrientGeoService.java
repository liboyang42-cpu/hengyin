package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.common.utils.geo.GeoCoordinates;
import com.chengyinhub.common.utils.geo.Wgs84Point;

import java.util.Map;

/** 城市定向坐标:WGS84 入库、边界只转一次、脏值隔离(Doc2 F1d)。 */
public interface IOrientGeoService {

    /**
     * 发布者把自己主题下节点的 varchar 坐标按声明的坐标系归一到 WGS84。
     * apply=false 只出报告;apply=true 写 orient_node_geo(指纹未变的行不重写)。脏值只标记,绝不写成 0。
     */
    Map<String, Object> normalizeTopic(Long memberId, Long topicId, String coordSys, boolean apply);

    /**
     * 节点的 WGS84 坐标。只认「已归一且原值未变」的行;未归一 / 原值已改 / 脏值都返回非 clean,
     * 调用方不得据此做距离判定,也不得把它当 0。
     */
    NodeGeo resolveNode(CmsTopicNode node);

    /** 客户端上报位置的边界转换:coord_sys 必填,未知坐标系或脏值直接拒绝。 */
    Wgs84Point reportedToWgs84(String coordSys, Double lng, Double lat);

    /** 两点距离(米);两侧都必须是已校验的 WGS84 点。 */
    int distanceMeters(Wgs84Point a, Wgs84Point b);

    /** 节点坐标状态。 */
    final class NodeGeo {
        public static final String UNNORMALIZED = "UNNORMALIZED";
        public static final String STALE = "STALE";

        private final Wgs84Point point;
        private final String status;

        public NodeGeo(Wgs84Point point, String status) {
            this.point = point;
            this.status = status;
        }

        public boolean clean() { return point != null; }

        public Wgs84Point point() { return point; }

        /** CLEAN / UNNORMALIZED / STALE / 脏值原因({@link GeoCoordinates.DirtyReason})。 */
        public String status() { return status; }
    }
}

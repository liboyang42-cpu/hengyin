package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OrientNodeGeo;
import com.chengyinhub.business.mapper.OrientMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOrientGeoService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.business.service.support.OrientSwitches;
import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import com.chengyinhub.common.utils.geo.CoordSys;
import com.chengyinhub.common.utils.geo.GeoCoordinates;
import com.chengyinhub.common.utils.geo.Wgs84Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class OrientGeoServiceImpl implements IOrientGeoService {

    @Autowired
    private OrientMapper orientMapper;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private ICmsTopicNodeService topicNodeService;
    @Autowired
    private DistanceCalculatorUtils distanceCalculator;
    @Autowired
    private OrientSwitches switches;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> normalizeTopic(Long memberId, Long topicId, String coordSysRaw, boolean apply) {
        switches.require(switches.geoOn());
        OrientOwnership.requireTopicOwner(topicService, memberId, topicId);
        CoordSys coordSys = CoordSys.parse(coordSysRaw);
        if (coordSys == null) {
            throw new OrientRejection(OrientRejection.COORD_SYS_INVALID, "请声明节点坐标的坐标系(WGS84/GCJ02/BD09)");
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        List<CmsTopicNode> nodes = topicNodeService.selectCmsTopicNodeList(probe);
        Date now = new Date();
        int clean = 0;
        int written = 0;
        int unchanged = 0;
        List<Map<String, Object>> dirty = new ArrayList<>();
        for (CmsTopicNode node : nodes) {
            GeoCoordinates.Parsed parsed = GeoCoordinates.parse(node.getLongitude(), node.getLatitude(), coordSys);
            if (parsed.clean()) {
                clean++;
            } else {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("nodeId", node.getId());
                d.put("name", node.getName());
                d.put("rawLongitude", node.getLongitude());
                d.put("rawLatitude", node.getLatitude());
                d.put("reason", parsed.dirtyReason().name());
                dirty.add(d);
            }
            if (!apply) continue;
            String fp = fingerprint(coordSys, node.getLongitude(), node.getLatitude());
            OrientNodeGeo existing = orientMapper.selectNodeGeo(node.getId());
            if (existing != null && fp.equals(existing.getSourceFingerprint())) {
                unchanged++;
                continue;
            }
            OrientNodeGeo row = new OrientNodeGeo();
            row.setNodeId(node.getId());
            row.setTopicId(topicId);
            row.setSourceCoordSys(coordSys.name());
            row.setSourceLng(node.getLongitude());
            row.setSourceLat(node.getLatitude());
            row.setSourceFingerprint(fp);
            if (parsed.clean()) {
                row.setWgs84Lng(scale(parsed.point().lng()));
                row.setWgs84Lat(scale(parsed.point().lat()));
                row.setGeoStatus(0);
            } else {
                row.setGeoStatus(1);
                row.setDirtyReason(parsed.dirtyReason().name());
            }
            row.setUpdateTime(now);
            if (existing == null) {
                row.setCreateTime(now);
                try {
                    orientMapper.insertNodeGeo(row);
                } catch (DuplicateKeyException race) {
                    orientMapper.updateNodeGeo(row);
                }
            } else {
                orientMapper.updateNodeGeo(row);
            }
            written++;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("topicId", topicId);
        report.put("coordSys", coordSys.name());
        report.put("applied", apply);
        report.put("total", nodes.size());
        report.put("clean", clean);
        report.put("dirtyCount", dirty.size());
        report.put("dirty", dirty);
        report.put("written", written);
        report.put("unchanged", unchanged);
        return report;
    }

    @Override
    public NodeGeo resolveNode(CmsTopicNode node) {
        OrientNodeGeo row = node == null || node.getId() == null ? null : orientMapper.selectNodeGeo(node.getId());
        if (row == null) return new NodeGeo(null, NodeGeo.UNNORMALIZED);
        CoordSys sys = CoordSys.parse(row.getSourceCoordSys());
        if (sys == null || !Objects.equals(row.getSourceFingerprint(),
                fingerprint(sys, node.getLongitude(), node.getLatitude()))) {
            return new NodeGeo(null, NodeGeo.STALE);
        }
        if (!Integer.valueOf(0).equals(row.getGeoStatus())) {
            return new NodeGeo(null, row.getDirtyReason() == null ? "DIRTY" : row.getDirtyReason());
        }
        GeoCoordinates.Parsed stored = GeoCoordinates.parseStoredWgs84(
                row.getWgs84Lng() == null ? null : row.getWgs84Lng().doubleValue(),
                row.getWgs84Lat() == null ? null : row.getWgs84Lat().doubleValue());
        return stored.clean() ? new NodeGeo(stored.point(), "CLEAN")
                : new NodeGeo(null, stored.dirtyReason().name());
    }

    @Override
    public Wgs84Point reportedToWgs84(String coordSysRaw, Double lng, Double lat) {
        CoordSys coordSys = CoordSys.parse(coordSysRaw);
        if (coordSys == null) {
            throw new OrientRejection(OrientRejection.COORD_SYS_INVALID, "定位缺少坐标系(coordSys)");
        }
        GeoCoordinates.Parsed parsed = GeoCoordinates.parse(lng, lat, coordSys);
        if (!parsed.clean()) {
            throw new OrientRejection(OrientRejection.COORD_INVALID, "定位坐标无效:" + parsed.dirtyReason().name());
        }
        return parsed.point();
    }

    @Override
    public int distanceMeters(Wgs84Point a, Wgs84Point b) {
        // 沿用仓内既有距离工具(F1c LocationGuard 的共享工具落地后切过去)。
        // 两侧已严格校验,不会走到它「解析失败返回 0」的分支。
        double km = distanceCalculator.calculateDistance(
                Double.toString(a.lng()), Double.toString(a.lat()),
                Double.toString(b.lng()), Double.toString(b.lat()));
        return (int) Math.round(km * 1000.0);
    }

    /** 原值指纹:坐标系 + varchar 原值。原值或声明坐标系任一变化,缓存的 WGS84 结果即失效。 */
    static String fingerprint(CoordSys coordSys, String lng, String lat) {
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(
                coordSys.name(), lng == null ? "" : lng, lat == null ? "" : lat));
    }

    static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(7, RoundingMode.HALF_UP);
    }
}

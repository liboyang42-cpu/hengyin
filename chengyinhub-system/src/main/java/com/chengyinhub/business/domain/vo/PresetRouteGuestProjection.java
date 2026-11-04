package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.domain.CmsPresetRouteNode;
import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.List;

/**
 * 第九轮 m4:游客(未登录)打开分享来的官方路线时的公开字段投影。
 * 路线与站点只留展示所需(城市、名称、预览图、轨迹、距离、时长、站点名称/地址/坐标/图片);
 * 去掉后台审计字段(创建人/修改人/时间/备注)、上架与删除标记。
 * 已下架、已删除的路线与站点本来就不会进到这里(PresetServiceImpl 与 Mapper 已过滤)。
 */
public final class PresetRouteGuestProjection {
    private PresetRouteGuestProjection() {
    }

    public static CmsPresetRoute apply(CmsPresetRoute route) {
        if (route == null) return null;
        clearAudit(route);
        route.setStatus(null);
        route.setDelFlag(null);
        List<CmsPresetRouteNode> nodes = route.getNodes();
        if (nodes != null) {
            for (CmsPresetRouteNode node : nodes) {
                if (node == null) continue;
                clearAudit(node);
                node.setDelFlag(null);
            }
        }
        return route;
    }

    public static List<CmsPresetRoute> applyAll(List<CmsPresetRoute> routes) {
        if (routes != null) {
            for (CmsPresetRoute route : routes) apply(route);
        }
        return routes;
    }

    private static void clearAudit(BaseEntity entity) {
        entity.setSearchValue(null);
        entity.setCreateBy(null);
        entity.setCreateTime(null);
        entity.setUpdateBy(null);
        entity.setUpdateTime(null);
        entity.setRemark(null);
        entity.setParams(null);
    }
}

package com.chengyinhub.business.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.domain.CmsPresetThemeStyle;
import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.mapper.CmsPresetThemeStyleMapper;
import com.chengyinhub.business.mapper.CmsPresetRouteMapper;
import com.chengyinhub.business.mapper.CmsPresetRouteNodeMapper;
import com.chengyinhub.business.service.IPresetService;

/**
 * 建议版发布预设(风格/路线)只读服务实现
 *
 * @author chengyin
 */
@Service
public class PresetServiceImpl implements IPresetService
{
    @Autowired
    private CmsPresetThemeStyleMapper styleMapper;

    @Autowired
    private CmsPresetRouteMapper routeMapper;

    @Autowired
    private CmsPresetRouteNodeMapper routeNodeMapper;

    @Override
    public List<CmsPresetThemeStyle> listStyles()
    {
        CmsPresetThemeStyle query = new CmsPresetThemeStyle();
        query.setStatus("1");
        return styleMapper.selectCmsPresetThemeStyleList(query);
    }

    @Override
    public List<CmsPresetRoute> listRoutes(String city)
    {
        CmsPresetRoute query = new CmsPresetRoute();
        query.setStatus("1");
        if (city != null && !city.trim().isEmpty())
        {
            query.setCity(city.trim());
        }
        return routeMapper.selectCmsPresetRouteList(query);
    }

    @Override
    public CmsPresetRoute getRouteWithNodes(Long id)
    {
        CmsPresetRoute route = routeMapper.selectCmsPresetRouteById(id);
        // R9-25 + 裁决 20(2026-09-15):公开详情对「已删除(del_flag≠0)」与「已下架(status≠1)」
        // 一视同仁 —— 直接返回不存在,不设已购例外(这条链路本就与报名/订单无关)。
        // ⚠️ 后台详情走另一条服务路径(CmsPresetRouteServiceImpl / ICmsPresetRouteService),
        // 不能把过滤加进 selectCmsPresetRouteById 的 SQL(那会把后台一起挡掉)。
        if (route == null || isNotPubliclyVisible(route))
        {
            return null;
        }
        route.setNodes(routeNodeMapper.selectByRouteId(id));
        return route;
    }

    /** 公开可见 = 未删除且已上架。status 为字符串列('0' 下架 / '1' 上架);存量 null 视为不可见,宁缺勿放。 */
    private boolean isNotPubliclyVisible(CmsPresetRoute route)
    {
        if (route.getDelFlag() != null && route.getDelFlag() != 0)
        {
            return true;
        }
        return !"1".equals(route.getStatus());
    }
}

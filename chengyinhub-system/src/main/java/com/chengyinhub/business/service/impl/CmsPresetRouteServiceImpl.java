package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.mapper.CmsPresetRouteMapper;
import com.chengyinhub.business.mapper.CmsPresetRouteNodeMapper;
import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.domain.CmsPresetRouteNode;
import com.chengyinhub.business.service.ICmsPresetRouteService;

/**
 * 建议版城市路线预设Service实现(含站点 master-detail)
 *
 * @author chengyin
 */
@Service
public class CmsPresetRouteServiceImpl implements ICmsPresetRouteService
{
    @Autowired
    private CmsPresetRouteMapper cmsPresetRouteMapper;

    @Autowired
    private CmsPresetRouteNodeMapper cmsPresetRouteNodeMapper;

    @Override
    public CmsPresetRoute selectCmsPresetRouteById(Long id)
    {
        CmsPresetRoute route = cmsPresetRouteMapper.selectCmsPresetRouteById(id);
        if (route != null)
        {
            route.setNodes(cmsPresetRouteNodeMapper.selectByRouteId(id));
        }
        return route;
    }

    @Override
    public List<CmsPresetRoute> selectCmsPresetRouteList(CmsPresetRoute cmsPresetRoute)
    {
        return cmsPresetRouteMapper.selectCmsPresetRouteList(cmsPresetRoute);
    }

    @Override
    @Transactional
    public int insertCmsPresetRoute(CmsPresetRoute cmsPresetRoute)
    {
        if (cmsPresetRoute.getStatus() == null) { cmsPresetRoute.setStatus("1"); }
        cmsPresetRoute.setDelFlag(0);
        cmsPresetRoute.setCreateTime(DateUtils.getNowDate());
        int rows = cmsPresetRouteMapper.insertCmsPresetRoute(cmsPresetRoute);
        saveNodes(cmsPresetRoute);
        return rows;
    }

    @Override
    @Transactional
    public int updateCmsPresetRoute(CmsPresetRoute cmsPresetRoute)
    {
        cmsPresetRoute.setUpdateTime(DateUtils.getNowDate());
        int rows = cmsPresetRouteMapper.updateCmsPresetRoute(cmsPresetRoute);
        // 站点重建:软删旧站点再插新站点
        cmsPresetRouteNodeMapper.deleteByRouteId(cmsPresetRoute.getId());
        saveNodes(cmsPresetRoute);
        return rows;
    }

    /** 保存路线下站点(按列表顺序写 sort) */
    private void saveNodes(CmsPresetRoute route)
    {
        List<CmsPresetRouteNode> nodes = route.getNodes();
        if (nodes == null || nodes.isEmpty()) { return; }
        int i = 0;
        for (CmsPresetRouteNode node : nodes)
        {
            node.setId(null);
            node.setRouteId(route.getId());
            if (node.getSort() == null) { node.setSort(i); }
            node.setDelFlag(0);
            node.setCreateTime(DateUtils.getNowDate());
            cmsPresetRouteNodeMapper.insertCmsPresetRouteNode(node);
            i++;
        }
    }

    @Override
    public int deleteCmsPresetRouteByIds(Long[] ids)
    {
        for (Long id : ids)
        {
            cmsPresetRouteNodeMapper.deleteByRouteId(id);
        }
        return cmsPresetRouteMapper.deleteCmsPresetRouteByIds(ids);
    }

    @Override
    public int deleteCmsPresetRouteById(Long id)
    {
        cmsPresetRouteNodeMapper.deleteByRouteId(id);
        return cmsPresetRouteMapper.deleteCmsPresetRouteById(id);
    }
}

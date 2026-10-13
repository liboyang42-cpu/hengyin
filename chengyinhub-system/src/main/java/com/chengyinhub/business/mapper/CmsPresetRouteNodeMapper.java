package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsPresetRouteNode;

/**
 * 建议版路线站点预设Mapper接口
 *
 * @author chengyin
 */
public interface CmsPresetRouteNodeMapper
{
    public CmsPresetRouteNode selectCmsPresetRouteNodeById(Long id);

    public List<CmsPresetRouteNode> selectCmsPresetRouteNodeList(CmsPresetRouteNode cmsPresetRouteNode);

    /** 按路线ID查站点(按 sort 升序) */
    public List<CmsPresetRouteNode> selectByRouteId(Long routeId);

    public int insertCmsPresetRouteNode(CmsPresetRouteNode cmsPresetRouteNode);

    public int updateCmsPresetRouteNode(CmsPresetRouteNode cmsPresetRouteNode);

    public int deleteCmsPresetRouteNodeById(Long id);

    public int deleteCmsPresetRouteNodeByIds(Long[] ids);

    /** 删除某路线下全部站点(编辑路线时重建用) */
    public int deleteByRouteId(Long routeId);
}

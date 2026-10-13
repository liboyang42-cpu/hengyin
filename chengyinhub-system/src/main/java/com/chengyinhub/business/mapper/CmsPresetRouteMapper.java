package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsPresetRoute;

/**
 * 建议版城市路线预设Mapper接口
 *
 * @author chengyin
 */
public interface CmsPresetRouteMapper
{
    public CmsPresetRoute selectCmsPresetRouteById(Long id);

    public List<CmsPresetRoute> selectCmsPresetRouteList(CmsPresetRoute cmsPresetRoute);

    public int insertCmsPresetRoute(CmsPresetRoute cmsPresetRoute);

    public int updateCmsPresetRoute(CmsPresetRoute cmsPresetRoute);

    public int deleteCmsPresetRouteById(Long id);

    public int deleteCmsPresetRouteByIds(Long[] ids);
}

package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsPresetRoute;

/**
 * 建议版城市路线预设Service接口(含站点 master-detail)
 *
 * @author chengyin
 */
public interface ICmsPresetRouteService
{
    /** 详情含站点 */
    public CmsPresetRoute selectCmsPresetRouteById(Long id);

    public List<CmsPresetRoute> selectCmsPresetRouteList(CmsPresetRoute cmsPresetRoute);

    /** 新增路线并保存其站点 */
    public int insertCmsPresetRoute(CmsPresetRoute cmsPresetRoute);

    /** 修改路线并重建其站点 */
    public int updateCmsPresetRoute(CmsPresetRoute cmsPresetRoute);

    public int deleteCmsPresetRouteByIds(Long[] ids);

    public int deleteCmsPresetRouteById(Long id);
}

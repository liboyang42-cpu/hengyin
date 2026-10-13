package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsPresetThemeStyle;
import com.chengyinhub.business.domain.CmsPresetRoute;

/**
 * 建议版发布预设(风格/路线)只读服务
 *
 * @author chengyin
 */
public interface IPresetService
{
    /** 上架主题风格列表(status=1,按 sort) */
    List<CmsPresetThemeStyle> listStyles();

    /** 上架城市路线列表(可按 city 过滤,status=1,按 sort) */
    List<CmsPresetRoute> listRoutes(String city);

    /** 单条路线含站点(供建议版减站) */
    CmsPresetRoute getRouteWithNodes(Long id);
}

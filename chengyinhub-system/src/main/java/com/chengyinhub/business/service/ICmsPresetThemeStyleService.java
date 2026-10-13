package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsPresetThemeStyle;

/**
 * 建议版主题风格预设Service接口
 *
 * @author chengyin
 */
public interface ICmsPresetThemeStyleService
{
    public CmsPresetThemeStyle selectCmsPresetThemeStyleById(Long id);

    public List<CmsPresetThemeStyle> selectCmsPresetThemeStyleList(CmsPresetThemeStyle cmsPresetThemeStyle);

    public int insertCmsPresetThemeStyle(CmsPresetThemeStyle cmsPresetThemeStyle);

    public int updateCmsPresetThemeStyle(CmsPresetThemeStyle cmsPresetThemeStyle);

    public int deleteCmsPresetThemeStyleByIds(Long[] ids);

    public int deleteCmsPresetThemeStyleById(Long id);
}

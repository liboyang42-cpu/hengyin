package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsPresetThemeStyle;

/**
 * 建议版主题风格预设Mapper接口
 *
 * @author chengyin
 */
public interface CmsPresetThemeStyleMapper
{
    public CmsPresetThemeStyle selectCmsPresetThemeStyleById(Long id);

    public List<CmsPresetThemeStyle> selectCmsPresetThemeStyleList(CmsPresetThemeStyle cmsPresetThemeStyle);

    public int insertCmsPresetThemeStyle(CmsPresetThemeStyle cmsPresetThemeStyle);

    public int updateCmsPresetThemeStyle(CmsPresetThemeStyle cmsPresetThemeStyle);

    public int deleteCmsPresetThemeStyleById(Long id);

    public int deleteCmsPresetThemeStyleByIds(Long[] ids);
}

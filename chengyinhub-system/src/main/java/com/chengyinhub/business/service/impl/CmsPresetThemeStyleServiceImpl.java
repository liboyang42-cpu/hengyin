package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsPresetThemeStyleMapper;
import com.chengyinhub.business.domain.CmsPresetThemeStyle;
import com.chengyinhub.business.service.ICmsPresetThemeStyleService;

/**
 * 建议版主题风格预设Service实现
 *
 * @author chengyin
 */
@Service
public class CmsPresetThemeStyleServiceImpl implements ICmsPresetThemeStyleService
{
    @Autowired
    private CmsPresetThemeStyleMapper cmsPresetThemeStyleMapper;

    @Override
    public CmsPresetThemeStyle selectCmsPresetThemeStyleById(Long id)
    {
        return cmsPresetThemeStyleMapper.selectCmsPresetThemeStyleById(id);
    }

    @Override
    public List<CmsPresetThemeStyle> selectCmsPresetThemeStyleList(CmsPresetThemeStyle cmsPresetThemeStyle)
    {
        return cmsPresetThemeStyleMapper.selectCmsPresetThemeStyleList(cmsPresetThemeStyle);
    }

    @Override
    public int insertCmsPresetThemeStyle(CmsPresetThemeStyle cmsPresetThemeStyle)
    {
        if (cmsPresetThemeStyle.getStatus() == null) { cmsPresetThemeStyle.setStatus("1"); }
        cmsPresetThemeStyle.setDelFlag(0);
        cmsPresetThemeStyle.setCreateTime(DateUtils.getNowDate());
        return cmsPresetThemeStyleMapper.insertCmsPresetThemeStyle(cmsPresetThemeStyle);
    }

    @Override
    public int updateCmsPresetThemeStyle(CmsPresetThemeStyle cmsPresetThemeStyle)
    {
        cmsPresetThemeStyle.setUpdateTime(DateUtils.getNowDate());
        return cmsPresetThemeStyleMapper.updateCmsPresetThemeStyle(cmsPresetThemeStyle);
    }

    @Override
    public int deleteCmsPresetThemeStyleByIds(Long[] ids)
    {
        return cmsPresetThemeStyleMapper.deleteCmsPresetThemeStyleByIds(ids);
    }

    @Override
    public int deleteCmsPresetThemeStyleById(Long id)
    {
        return cmsPresetThemeStyleMapper.deleteCmsPresetThemeStyleById(id);
    }
}

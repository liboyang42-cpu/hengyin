package com.chengyinhub.business.service.impl;

import java.util.List;

import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.ICmsMemberTemplateService;

/**
 * 我的模板Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-02
 */
@Service
public class CmsMemberTemplateServiceImpl implements ICmsMemberTemplateService 
{
    @Autowired
    private CmsMemberTemplateMapper cmsMemberTemplateMapper;

     @Override
    public int selectNumByTitle(CmsMemberTemplate cmsMemberTemplate)
    {
        return cmsMemberTemplateMapper.selectNumByTitle(cmsMemberTemplate);
    }


    /**
     * 查询我的模板
     * 
     * @param id 我的模板主键
     * @return 我的模板
     */
    @Override
    public CmsMemberTemplate selectCmsMemberTemplateById(Long id)
    {
        return cmsMemberTemplateMapper.selectCmsMemberTemplateById(id);
    }

    /**
     * 查询我的模板
     * @param ids  List<Long>
     * @return 我的模拟
     */
    @Override
    public List<CmsMemberTemplate> selectCmsMemberTemplateByIds(List<Long> ids) {
        return cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(ids);
    }


    /**
     * 查询我的模板列表
     * 
     * @param cmsMemberTemplate 我的模板
     * @return 我的模板
     */
    @Override
    public List<CmsMemberTemplate> selectCmsMemberTemplateList(CmsMemberTemplate cmsMemberTemplate)
    {
        return cmsMemberTemplateMapper.selectCmsMemberTemplateList(cmsMemberTemplate);
    }

    @Override
    public List<MemberTemplateVO> selectMemberTemplateList(MemberTemplateVO memberTemplateVO)
    {
        return cmsMemberTemplateMapper.selectMemberTemplateList(memberTemplateVO);
    }


    /**
     * 新增我的模板
     * 
     * @param cmsMemberTemplate 我的模板
     * @return 结果
     */
    @Override
    public int insertCmsMemberTemplate(CmsMemberTemplate cmsMemberTemplate)
    {
        cmsMemberTemplate.setCreateTime(DateUtils.getNowDate());
        return cmsMemberTemplateMapper.insertCmsMemberTemplate(cmsMemberTemplate);
    }

    /**
     * 修改我的模板
     * 
     * @param cmsMemberTemplate 我的模板
     * @return 结果
     */
    @Override
    public int updateCmsMemberTemplate(CmsMemberTemplate cmsMemberTemplate)
    {
        cmsMemberTemplate.setUpdateTime(DateUtils.getNowDate());
        return cmsMemberTemplateMapper.updateCmsMemberTemplate(cmsMemberTemplate);
    }

    /**
     * 批量删除我的模板
     * 
     * @param ids 需要删除的我的模板主键
     * @return 结果
     */
    @Override
    public int deleteCmsMemberTemplateByIds(Long[] ids)
    {
        return cmsMemberTemplateMapper.deleteCmsMemberTemplateByIds(ids);
    }

    /**
     * 删除我的模板信息
     * 
     * @param id 我的模板主键
     * @return 结果
     */
    @Override
    public int deleteCmsMemberTemplateById(Long id)
    {
        return cmsMemberTemplateMapper.deleteCmsMemberTemplateById(id);
    }
}

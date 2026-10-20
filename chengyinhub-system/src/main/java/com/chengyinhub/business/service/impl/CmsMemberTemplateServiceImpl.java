package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

import com.chengyinhub.business.service.support.LegacyCmsMutationGuard;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private LegacyCmsMutationGuard legacyCmsMutationGuard;

    @Autowired
    private LegacyCmsTemplateBodyReader legacyCmsTemplateBodyReader;

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
        return legacyCmsTemplateBodyReader.findLegacySnapshot(id);
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
        requireUnmarkedInput(cmsMemberTemplate);
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
    @Transactional(rollbackFor = Exception.class)
    public int updateCmsMemberTemplate(CmsMemberTemplate cmsMemberTemplate)
    {
        requireUnmarkedInput(cmsMemberTemplate);
        legacyCmsMutationGuard.requireLegacyForMutation(cmsMemberTemplate.getId());
        // Re-read under the existing canonical lock, rather than a possibly older RR snapshot.
        List<CmsMemberTemplate> locked = cmsMemberTemplateMapper.selectCmsMemberTemplateByIdsForUpdate(
                Collections.singletonList(cmsMemberTemplate.getId()));
        if (locked == null || locked.size() != 1 || locked.get(0) == null
                || !cmsMemberTemplate.getId().equals(locked.get(0).getId())) {
            throw new ServiceException("模板不存在或不可通过旧入口修改");
        }
        cmsMemberTemplate.setOriginalTemplateId(locked.get(0).getOriginalTemplateId());
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
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsMemberTemplateByIds(Long[] ids)
    {
        if (ids == null) throw new ServiceException("模板 ID 不能为空");
        SortedSet<Long> ordered = new TreeSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0L) throw new ServiceException("模板 ID 无效");
            ordered.add(id);
        }
        if (ordered.isEmpty()) return 0;
        legacyCmsMutationGuard.requireLegacyForMutations(ordered);
        // Never delete sidecars. All canonical rows must pass before this one mutation.
        int rows = cmsMemberTemplateMapper.deleteCmsMemberTemplateByIds(ordered.toArray(new Long[0]));
        if (rows != ordered.size()) throw new ServiceException("模板删除不完整，已取消本次删除");
        return rows;
    }

    /**
     * 删除我的模板信息
     * 
     * @param id 我的模板主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsMemberTemplateById(Long id)
    {
        legacyCmsMutationGuard.requireLegacyForMutation(id);
        int rows = cmsMemberTemplateMapper.deleteCmsMemberTemplateById(id);
        if (rows != 1) throw new ServiceException("模板删除失败");
        return rows;
    }

    private void requireUnmarkedInput(CmsMemberTemplate template) {
        if (template == null || template.getW18ProvenanceKind() != null) {
            throw new ServiceException("受管模板不能通过旧入口写入");
        }
    }
}

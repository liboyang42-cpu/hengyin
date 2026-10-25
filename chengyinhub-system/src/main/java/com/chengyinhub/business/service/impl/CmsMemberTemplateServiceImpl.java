package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Date;
import com.chengyinhub.common.utils.bean.BeanUtils;

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
    private com.chengyinhub.business.service.ICouponRewardAuthorizationService couponRewardAuthorizationService;
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

    @Override
    public Long copyForContentOwner(Long templateId, Long newOwnerMemberId, boolean forceCopy) {
        if (templateId == null || templateId <= 0L || newOwnerMemberId == null) {
            return templateId;
        }
        CmsMemberTemplate source = cmsMemberTemplateMapper.selectCmsMemberTemplateById(templateId);
        if (source == null || (!forceCopy && newOwnerMemberId.equals(source.getMemberId()))) {
            return templateId;
        }
        // A complete-topic copy uses this same funnel. Content reuse cannot transfer another owner's coupon asset.
        java.util.Set<Long> coupons = new java.util.LinkedHashSet<>();
        if (source.getCouponId() != null && source.getCouponId() > 0L) coupons.add(source.getCouponId());
        coupons.addAll(new com.chengyinhub.business.service.support.RewardRulesValidator().couponIds(source.getRewardRulesJson()));
        for (Long coupon : coupons) couponRewardAuthorizationService.assertOwnedRewardCoupon(coupon, newOwnerMemberId);
        CmsMemberTemplate copy = new CmsMemberTemplate();
        BeanUtils.copyProperties(source, copy);
        copy.setId(null);
        copy.setMemberId(newOwnerMemberId);
        copy.setOriginalTemplateId(source.getOriginalTemplateId() == null ? 0L : source.getOriginalTemplateId());
        copy.setIsSync(0);
        copy.setCreateBy("system_copy");
        copy.setCreateTime(new Date());
        copy.setUpdateBy("system_copy");
        copy.setUpdateTime(new Date());
        cmsMemberTemplateMapper.insertCmsMemberTemplate(copy);
        if (copy.getId() == null) {
            throw new IllegalStateException("复制主题失败: 节点模板深拷贝未回填主键, templateId=" + templateId);
        }
        return copy.getId();
    }
}

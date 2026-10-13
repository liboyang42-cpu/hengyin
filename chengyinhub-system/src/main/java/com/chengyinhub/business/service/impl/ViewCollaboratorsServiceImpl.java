package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ViewCollaboratorsMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.domain.ViewCollaborators;
import com.chengyinhub.business.service.IViewCollaboratorsService;

/**
 * 合作者视图Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-04
 */
@Service
public class ViewCollaboratorsServiceImpl implements IViewCollaboratorsService 
{
    @Autowired
    private ViewCollaboratorsMapper viewCollaboratorsMapper;

    /**
     * 查询合作者视图
     * 
     * @param id 合作者视图主键
     * @return 合作者视图
     */
    @Override
    public ViewCollaborators selectViewCollaboratorsById(Long id)
    {
        ViewCollaborators row = viewCollaboratorsMapper.selectViewCollaboratorsById(id);
        maskPii(row);
        return row;
    }

    /**
     * 查询合作者视图列表
     *
     * @param viewCollaborators 合作者视图
     * @return 合作者视图
     */
    @Override
    public List<ViewCollaborators> selectViewCollaboratorsList(ViewCollaborators viewCollaborators)
    {
        List<ViewCollaborators> rows = viewCollaboratorsMapper.selectViewCollaboratorsList(viewCollaborators);
        if (rows != null)
        {
            rows.forEach(this::maskPii);
        }
        return rows;
    }

    /**
     * 在 service 层脱敏,而不是逐个 controller 挂闸门。
     *
     * <p>本方法有 6 个调用方:后台 4 个(ViewCollaboratorsController 的 list/export、
     * CmsTopicController、CmsActivityController)+ <b>小程序 2 个</b>(ApiTopicController:588、
     * ApiActivityController:720 的「主题/活动阵容」)。逐个 controller 挂的话,新调用方默认漏,
     * 且当初正是靠这个 service 直通,把 memberPhone 明文一路发给了**所有看主题的小程序用户**——
     * 后台泄漏面是员工,那条的泄漏面是公众。收在 service 层一次覆盖全部。
     * (这也是全仓做得最对的 UmsMemberWithdrawalServiceImpl 的做法。)
     *
     * <p>无条件脱敏、不接权限:全仓没有任何代码消费 getMemberPhone() 的明文——它纯粹是白送出去的。
     * memberNickname/memberAvatar 不动,阵容展示靠它们就够。
     *
     * <p>view_collaborators 是<b>数据库视图</b>(定义不在仓库里),线上实测
     * member_phone = um.phone、member_real_name = um.name。
     */
    private void maskPii(ViewCollaborators row)
    {
        if (row == null)
        {
            return;
        }
        row.setMemberPhone(PiiCryptoService.maskPhone(row.getMemberPhone()));
        row.setMemberRealName(PiiCryptoService.maskName(row.getMemberRealName()));
    }

    /**
     * 新增合作者视图
     * 
     * @param viewCollaborators 合作者视图
     * @return 结果
     */
    @Override
    public int insertViewCollaborators(ViewCollaborators viewCollaborators)
    {
        viewCollaborators.setCreateTime(DateUtils.getNowDate());
        return viewCollaboratorsMapper.insertViewCollaborators(viewCollaborators);
    }

    /**
     * 修改合作者视图
     * 
     * @param viewCollaborators 合作者视图
     * @return 结果
     */
    @Override
    public int updateViewCollaborators(ViewCollaborators viewCollaborators)
    {
        viewCollaborators.setUpdateTime(DateUtils.getNowDate());
        return viewCollaboratorsMapper.updateViewCollaborators(viewCollaborators);
    }

    /**
     * 批量删除合作者视图
     * 
     * @param ids 需要删除的合作者视图主键
     * @return 结果
     */
    @Override
    public int deleteViewCollaboratorsByIds(Long[] ids)
    {
        return viewCollaboratorsMapper.deleteViewCollaboratorsByIds(ids);
    }

    /**
     * 删除合作者视图信息
     * 
     * @param id 合作者视图主键
     * @return 结果
     */
    @Override
    public int deleteViewCollaboratorsById(Long id)
    {
        return viewCollaboratorsMapper.deleteViewCollaboratorsById(id);
    }
}

package com.chengyinhub.business.service.impl;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IUmsMemberService;

/**
 * 配额校验 Service 业务层处理。
 */
@Service
public class QuotaServiceImpl implements IQuotaService
{
    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private ISmsCouponService smsCouponService;

    @Override
    public String checkCreate(Long memberId, String type)
    {
        if (memberId == null || memberId <= 0)
        {
            return "请先登录";
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null)
        {
            return "用户不存在";
        }
        UserPermission perm = roleService.getPermission(member);
        if (perm == null)
        {
            return "当前身份权限配置缺失，请联系管理员";
        }

        if (TYPE_THEME.equals(type))
        {
            if (Boolean.FALSE.equals(perm.getCanCreateTheme()))
            {
                return "当前身份不可创建主题";
            }
            // 草稿/待审无限:创建阶段不再按创建总数拦截。
            // 主题「同时在架」上限改在上架时刻校验(checkThemeOnline),下架即释放、selfPlay 与草稿不占。
            return null;
        }
        if (TYPE_EVENT.equals(type))
        {
            if (Boolean.FALSE.equals(perm.getCanCreateEvent()))
            {
                return "当前身份不可创建活动";
            }
            int used = cmsActivityService.selectCmsActivityNumByMemberId(memberId);
            if (perm.getMaxEvents() != null && used >= perm.getMaxEvents())
            {
                return "已达到当前身份活动数量上限（" + perm.getMaxEvents() + "）";
            }
            return null;
        }
        if (TYPE_COUPON.equals(type))
        {
            if (Boolean.FALSE.equals(perm.getCanPublishCoupon()))
            {
                return "当前身份不可发布优惠券";
            }
            int used = smsCouponService.selectSmsCouponNumByMemberId(memberId);
            if (perm.getMaxCoupons() != null && used >= perm.getMaxCoupons())
            {
                return "已达到当前身份优惠券数量上限（" + perm.getMaxCoupons() + "）";
            }
            return null;
        }
        return null;
    }

    @Override
    public String checkNodesPerTheme(Long memberId, int nodeCount)
    {
        if (nodeCount <= 0)
        {
            return null;
        }
        if (memberId == null || memberId <= 0)
        {
            return "请先登录";
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null)
        {
            return "用户不存在";
        }
        UserPermission perm = roleService.getPermission(member);
        if (perm == null)
        {
            return "当前身份权限配置缺失，请联系管理员";
        }
        return null;
    }

    @Override
    public String checkThemeOnline(Long memberId, Long excludeTopicId)
    {
        if (memberId == null || memberId <= 0)
        {
            return "请先登录";
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null)
        {
            return "用户不存在";
        }
        UserPermission perm = roleService.getPermission(member);
        if (perm == null)
        {
            return "当前身份权限配置缺失，请联系管理员";
        }
        Integer max = perm.getMaxThemes();
        if (max == null)
        {
            return null; // null=不限（俱乐部/商家）
        }
        int online = cmsTopicService.selectOnlineThemeNumByMemberId(memberId, excludeTopicId);
        if (online >= max)
        {
            return "同时上架的主题已达上限（" + max + "），请先下架其他主题或保存为草稿";
        }
        return null;
    }

    @Override
    public String lockAndCheckThemeOnline(Long memberId, Long excludeTopicId)
    {
        // ① 悲观锁串行化该会员的上架操作:并发请求在此排队,消除 check-then-write 的 TOCTOU 窗口
        umsMemberService.lockMemberById(memberId);
        // ② 持锁复查在架数(排除自身保证重复上架幂等)
        return checkThemeOnline(memberId, excludeTopicId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public <T> T runWithThemeOnlineGuard(Long memberId, Long excludeTopicId, Supplier<T> writeAction)
    {
        String err = lockAndCheckThemeOnline(memberId, excludeTopicId);
        if (err != null)
        {
            throw new ServiceException(err);
        }
        // ③ 同事务内写库,提交后方释放锁
        return writeAction.get();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public <T> T runWithCreateGuard(Long memberId, String type, Supplier<T> writeAction)
    {
        // ① 悲观锁串行化该会员的创建操作 ② 持锁复查配额 ③ 同事务写库,消除 check-then-insert 竞态
        umsMemberService.lockMemberById(memberId);
        String err = checkCreate(memberId, type);
        if (err != null)
        {
            throw new ServiceException(err);
        }
        return writeAction.get();
    }
}

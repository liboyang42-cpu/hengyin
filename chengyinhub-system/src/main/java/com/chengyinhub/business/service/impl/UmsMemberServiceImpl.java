package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.regex.Pattern;
import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.PointsStatisticsDTO;
import com.chengyinhub.business.domain.vo.HomeDataVO;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-13
 */
@Service
public class UmsMemberServiceImpl implements IUmsMemberService
{
    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private IUmsMemberPointsDetailService pointsDetailService;


    @Autowired
    private IUmsMemberBalanceDetailService balanceDetailService;

    @Autowired
    private IAppSessionRegistryService appSessionRegistryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ICmsPointsSettingService cmsPointsSettingService;

    @Value("${chengyin.default-avatar-url:}")
    private String defaultAvatarUrl;

    private static final Pattern WECHAT_DUPLICATE_KEY = Pattern.compile(
            "for key ['`](?:[^'`]*\\.)?uk_ums_member_wx_open_id_nonblank['`]");

    /**
     * 获取用户积分统计信息
     * @param memberId 用户ID
     * @return 积分统计信息
     */
    @Override
    public PointsStatisticsDTO getBalanceStatistics(Long memberId) {
        PointsStatisticsDTO statistics = new PointsStatisticsDTO();

        try {
            // 1. 获取本周积分收入
            BigDecimal weekPoints = balanceDetailService.getWeekBalanceIncome(memberId);
            statistics.setWeekPoints(weekPoints);

            // 2. 获取用户排名信息
            Map<String, Object> rankInfo = this.getUserBalanceRankPercentage(memberId);
            statistics.setRankPercentage((String) rankInfo.get("rankPercentage"));
            statistics.setTotalUsers((Integer) rankInfo.get("totalUsers"));
            statistics.setUserRank((Integer) rankInfo.get("userRank"));

        } catch (Exception e) {
            // 异常处理，设置默认值
            statistics.setWeekPoints(BigDecimal.ZERO);
            statistics.setRankPercentage("TOP0%");
            statistics.setTotalUsers(0);
            statistics.setUserRank(0);
        }

        return statistics;
    }

    /**
     * 获取用户排名百分比信息
     * @param memberId 用户ID
     * @return 排名信息
     */
    @Override
    public Map<String, Object> getUserBalanceRankPercentage(Long memberId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 使用SQL查询排名信息
            Map<String, Object> rankInfo = umsMemberMapper.selectUserBalanceRankInfo(memberId);

            if (rankInfo != null && !rankInfo.isEmpty()) {
                result.put("rankPercentage", rankInfo.get("rank_percentage"));
                result.put("totalUsers", ((Number) rankInfo.get("total_users")).intValue());
                result.put("userRank", ((Number) rankInfo.get("user_rank")).intValue());
            } else {
                // 如果用户不存在或已被删除
                result.put("rankPercentage", "TOP0%");
                result.put("totalUsers", umsMemberMapper.selectTotalUserCount());
                result.put("userRank", 0);
            }
        } catch (Exception e) {
            // 异常处理，使用简化逻辑
            Integer totalUsers = umsMemberMapper.selectTotalUserCount();
            result.put("rankPercentage", "TOP0%");
            result.put("totalUsers", totalUsers != null ? totalUsers : 0);
            result.put("userRank", 0);
        }

        return result;
    }


    /**
     * 获取用户积分统计信息
     * @param memberId 用户ID
     * @return 积分统计信息
     */
    @Override
    public PointsStatisticsDTO getPointsStatistics(Long memberId) {
        PointsStatisticsDTO statistics = new PointsStatisticsDTO();

        try {
            // 1. 获取本周积分收入
            BigDecimal weekPoints = pointsDetailService.getWeekPointsIncome(memberId);
            statistics.setWeekPoints(weekPoints);

            // 2. 获取用户排名信息
            Map<String, Object> rankInfo = this.getUserRankPercentage(memberId);
            statistics.setRankPercentage((String) rankInfo.get("rankPercentage"));
            statistics.setTotalUsers((Integer) rankInfo.get("totalUsers"));
            statistics.setUserRank((Integer) rankInfo.get("userRank"));

        } catch (Exception e) {
            // 异常处理，设置默认值
            statistics.setWeekPoints(BigDecimal.ZERO);
            statistics.setRankPercentage("TOP0%");
            statistics.setTotalUsers(0);
            statistics.setUserRank(0);
        }

        return statistics;
    }

    /**
     * 获取用户排名百分比信息
     * @param memberId 用户ID
     * @return 排名信息
     */
    @Override
    public Map<String, Object> getUserRankPercentage(Long memberId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 使用SQL查询排名信息
            Map<String, Object> rankInfo = umsMemberMapper.selectUserRankInfo(memberId);

            if (rankInfo != null && !rankInfo.isEmpty()) {
                result.put("rankPercentage", rankInfo.get("rank_percentage"));
                result.put("totalUsers", ((Number) rankInfo.get("total_users")).intValue());
                result.put("userRank", ((Number) rankInfo.get("user_rank")).intValue());
            } else {
                // 如果用户不存在或已被删除
                result.put("rankPercentage", "TOP0%");
                result.put("totalUsers", umsMemberMapper.selectTotalUserCount());
                result.put("userRank", 0);
            }
        } catch (Exception e) {
            // 异常处理，使用简化逻辑
            Integer totalUsers = umsMemberMapper.selectTotalUserCount();
            result.put("rankPercentage", "TOP0%");
            result.put("totalUsers", totalUsers != null ? totalUsers : 0);
            result.put("userRank", 0);
        }

        return result;
    }

    /**
     * 查询用户
     * 
     * @param id 用户主键
     * @return 用户
     */
    @Override
    public UmsMember selectUmsMemberById(Long id)
    {
        return umsMemberMapper.selectUmsMemberById(id);
    }

    @Override
    public Long lockMemberById(Long id)
    {
        return umsMemberMapper.lockMemberById(id);
    }

    /**
     * 查询用户列表
     * 
     * @param umsMember 用户
     * @return 用户
     */
    @Override
    public List<UmsMember> selectUmsMemberList(UmsMember umsMember)
    {
        return umsMemberMapper.selectUmsMemberList(umsMember);
    }

    /**
     * 新增用户
     * 
     * @param umsMember 用户
     * @return 结果
     */
    @Override
    public int insertUmsMember(UmsMember umsMember)
    {
        umsMember.setRole(com.chengyinhub.business.service.IRoleService.ROLE_PLAYER);
        umsMember.setUserType(1L);
        umsMember.setCreateTime(DateUtils.getNowDate());
        try
        {
            return umsMemberMapper.insertUmsMember(umsMember);
        }
        catch (DuplicateKeyException failure)
        {
            if (isWechatIdentityDuplicate(failure))
            {
                throw new ServiceException("微信身份已绑定其他账号");
            }
            throw failure;
        }
    }

    @Override
    public UmsMember getOrCreateWechatMember(String openid)
    {
        if (StringUtils.isEmpty(openid))
        {
            throw new ServiceException("身份凭证无效");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try
        {
            return transaction.execute(status -> {
                UmsMember existing = findUniqueWechatMember(openid);
                if (existing != null)
                {
                    return existing;
                }
                UmsMember member = new UmsMember();
                member.setNickname("城瘾用户");
                member.setName("城瘾用户");
                member.setUserType(1L);
                member.setRole("player");
                member.setPhone("");
                member.setPassword("");
                member.setAvatar(defaultAvatarUrl);
                member.setWxOpenId(openid);
                member.setStatus(1L);
                member.setDelFlag(0);
                // 保留原规则：会员余额 50，赠送流水取注册配置。
                member.setPoint(50L);
                member.setCreateTime(DateUtils.getNowDate());
                int rows;
                try
                {
                    rows = umsMemberMapper.insertUmsMember(member);
                }
                catch (DuplicateKeyException failure)
                {
                    if (isWechatIdentityDuplicate(failure))
                    {
                        throw new WechatMemberInsertConflict(failure);
                    }
                    throw failure;
                }
                if (rows != 1 || member.getId() == null)
                {
                    throw new ServiceException("注册失败");
                }
                CmsPointsSetting setting = cmsPointsSettingService.selectCmsPointsSettingById(1L);
                long points = setting == null ? 0L : setting.getValue().intValue();
                UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
                detail.setMemberId(member.getId());
                detail.setCurrentPoints(points);
                detail.setAfterPoints(points);
                detail.setChangePoints(points);
                detail.setChangeReason("注册赠送");
                detail.setEventType(1L);
                detail.setEventId(0L);
                detail.setChangeType(1L);
                detail.setDelFlag(0);
                detail.setCreateTime(DateUtils.getNowDate());
                if (pointsDetailService.insertUmsMemberPointsDetail(detail) != 1)
                {
                    throw new ServiceException("注册赠送失败");
                }
                return member;
            });
        }
        catch (WechatMemberInsertConflict conflict)
        {
            DuplicateKeyException failure = conflict.duplicate;
            // 新事务读取已提交的赢家，避免复用失败事务的 REPEATABLE READ 快照。
            return transaction.execute(status -> {
                UmsMember member = findUniqueWechatMember(openid);
                if (member == null)
                {
                    throw failure;
                }
                return member;
            });
        }
    }

    private UmsMember findUniqueWechatMember(String openid)
    {
        UmsMember query = new UmsMember();
        query.setWxOpenId(openid);
        List<UmsMember> members = umsMemberMapper.selectUmsMemberList(query);
        if (members.size() > 1)
        {
            throw new ServiceException("身份凭证无效");
        }
        return members.isEmpty() ? null : members.get(0);
    }

    private boolean isWechatIdentityDuplicate(DuplicateKeyException failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
        {
            if (cause instanceof SQLException && ((SQLException) cause).getErrorCode() == 1062
                    && cause.getMessage() != null
                    && WECHAT_DUPLICATE_KEY.matcher(cause.getMessage()).find())
            {
                return true;
            }
        }
        return false;
    }

    private static class WechatMemberInsertConflict extends RuntimeException
    {
        private final DuplicateKeyException duplicate;

        private WechatMemberInsertConflict(DuplicateKeyException duplicate)
        {
            super(duplicate);
            this.duplicate = duplicate;
        }
    }

    /**
     * 修改用户
     * 
     * @param umsMember 用户
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateUmsMember(UmsMember umsMember)
    {
        UmsMember current = umsMember.getId() == null ? null
                : umsMemberMapper.selectUmsMemberById(umsMember.getId());
        if (umsMember.getRole() != null && (current == null || !Objects.equals(current.getRole(), umsMember.getRole()))) {
            throw new com.chengyinhub.common.exception.ServiceException("角色修改必须通过身份审核或移交流程", 403);
        }
        boolean authenticationStateChanged = changesAuthenticationState(current, umsMember);
        String mutationId = null;
        if (authenticationStateChanged)
        {
            // 门闩覆盖整个数据库事务，阻止提交窗口内用旧凭证签出新会话。
            mutationId = appSessionRegistryService.beginMemberMutation(umsMember.getId());
        }
        try
        {
            umsMember.setUpdateTime(DateUtils.getNowDate());
            int rows = umsMemberMapper.updateUmsMember(umsMember);
            if (authenticationStateChanged)
            {
                appSessionRegistryService.revokeAll(umsMember.getId());
                appSessionRegistryService.finishMemberMutationAfterTransaction(umsMember.getId(), mutationId);
                mutationId = null;
            }
            return rows;
        }
        catch (RuntimeException e)
        {
            finishFailedMutation(umsMember.getId(), mutationId, e);
            if (e instanceof DuplicateKeyException && isWechatIdentityDuplicate((DuplicateKeyException) e))
            {
                throw new ServiceException("微信身份已绑定其他账号");
            }
            throw e;
        }
    }

    private boolean changesAuthenticationState(UmsMember current, UmsMember update)
    {
        if (current == null)
        {
            return false;
        }
        return update.getPassword() != null && !Objects.equals(update.getPassword(), current.getPassword())
                || update.getStatus() != null && !Objects.equals(update.getStatus(), current.getStatus())
                || update.getDelFlag() != null && !Objects.equals(update.getDelFlag(), current.getDelFlag())
                || update.getPhone() != null && !Objects.equals(update.getPhone(), current.getPhone())
                || update.getWxOpenId() != null && !Objects.equals(update.getWxOpenId(), current.getWxOpenId())
                || update.getUnionId() != null && !Objects.equals(update.getUnionId(), current.getUnionId())
                || update.getAppleId() != null && !Objects.equals(update.getAppleId(), current.getAppleId());
    }

    /**
     * 批量删除用户
     * 
     * @param ids 需要删除的用户主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteUmsMemberByIds(Long[] ids)
    {
        Map<Long, String> mutations = new HashMap<>();
        try
        {
            for (Long id : ids)
            {
                mutations.put(id, appSessionRegistryService.beginMemberMutation(id));
            }
            int rows = umsMemberMapper.deleteUmsMemberByIds(ids);
            for (Long id : ids)
            {
                appSessionRegistryService.revokeAll(id);
                appSessionRegistryService.finishMemberMutationAfterTransaction(id, mutations.remove(id));
            }
            return rows;
        }
        catch (RuntimeException e)
        {
            for (Map.Entry<Long, String> mutation : mutations.entrySet())
            {
                finishFailedMutation(mutation.getKey(), mutation.getValue(), e);
            }
            throw e;
        }
    }

    /**
     * 删除用户信息
     * 
     * @param id 用户主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteUmsMemberById(Long id)
    {
        String mutationId = appSessionRegistryService.beginMemberMutation(id);
        try
        {
            int rows = umsMemberMapper.deleteUmsMemberById(id);
            appSessionRegistryService.revokeAll(id);
            appSessionRegistryService.finishMemberMutationAfterTransaction(id, mutationId);
            return rows;
        }
        catch (RuntimeException e)
        {
            finishFailedMutation(id, mutationId, e);
            throw e;
        }
    }

    private void finishFailedMutation(Long memberId, String mutationId, RuntimeException failure)
    {
        if (mutationId == null)
        {
            return;
        }
        try
        {
            appSessionRegistryService.finishMemberMutation(memberId, mutationId);
        }
        catch (RuntimeException cleanupFailure)
        {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 扣除用户积分
     *
     * @param id 用户ID
     * @param point 扣除的积分数
     * @return 是否扣除成功
     */
    @Override
    public boolean deductPoints(Long id, Integer point) {
        if (id == null || point == null || point <= 0) {
            throw new IllegalArgumentException("参数错误：用户ID和积分必须为正数");
        }

        try {
            int affectedRows = umsMemberMapper.deductPoints(id, point);
            if (affectedRows == 0) {
                // 可能是用户不存在或积分不足
                UmsMember member = umsMemberMapper.selectUmsMemberById(id);
                if (member == null) {
                    throw new RuntimeException("用户不存在");
                }
                if (member.getPoint() == null || member.getPoint() < point) {
                    throw new RuntimeException("用户积分不足，当前积分：" + member.getPoint());
                }
                throw new RuntimeException("扣除积分失败");
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("扣除积分操作失败: " + e.getMessage(), e);
        }
    }

    /**
     *
     * @param id 用户ID
     * @param point 增加的积分数
     * @return 是否添加成功
     */
    @Override
    public boolean addPoints(Long id, Integer point) {
        if (id == null || point <= 0) {
            throw new IllegalArgumentException("参数错误：用户ID和积分必须为正数");
        }

        // 检查用户是否存在
//        UmsMember member = umsMemberMapper.selectUmsMemberById(id);
//        if (member == null) {
//            throw new RuntimeException("用户不存在");
//        }

        // 执行增加操作
        int affectedRows = umsMemberMapper.addPoints(id, point);
        if (affectedRows == 0) {
            throw new RuntimeException("增加积分失败");
        }

        return true;
    }


    /**
     * 扣除用户余额
     *
     * @param id 用户ID
     * @param balance 扣除的余额
     * @return 是否扣除成功
     */
    @Override
    public boolean deductBalance(Long id, BigDecimal balance) {
        if (id == null || balance == null || balance.compareTo(BigDecimal.ZERO)<= 0) {
            throw new IllegalArgumentException("参数错误：变动余额需大于0");
        }

        try {
            int affectedRows = umsMemberMapper.deductBalance(id, balance);
            if (affectedRows == 0) {
                UmsMember member = umsMemberMapper.selectUmsMemberById(id);
                if (member == null) {
                    throw new RuntimeException("用户不存在");
                }
                if (member.getBalance() == null || member.getBalance().compareTo( balance)<= 0) {
                    throw new RuntimeException("用户余额不足，当前余额：" + member.getBalance());
                }
                throw new RuntimeException("扣除用户余额失败");
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("扣除用户余额操作失败: " + e.getMessage(), e);
        }
    }

    /**
     * 扣余额并给冻结额留底(提现专用)。护栏与扣款在同一条 UPDATE ⇒ 原子,
     * 不能用「Java 里先算 available 再 deductBalance」代替(check-then-act,并发下冻结额会被提走)。
     */
    @Override
    public boolean deductWithdrawableBalance(Long id, BigDecimal amount, BigDecimal frozen) {
        if (id == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("参数错误：变动余额需大于0");
        }
        BigDecimal floor = frozen == null ? BigDecimal.ZERO : frozen;
        if (floor.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("参数错误：冻结额不能为负");
        }
        return umsMemberMapper.deductWithdrawableBalance(id, amount, floor) > 0;
    }

    /**
     *
     * @param id 用户ID
     * @param balance 增加的余额数量
     * @return 是否添加成功
     */
    @Override
    public boolean addBalance(Long id, BigDecimal balance) {
        if (id == null || balance == null || balance.compareTo(BigDecimal.ZERO)<= 0) {
            throw new IllegalArgumentException("参数错误：变动余额需大于0");
        }

        // 检查用户是否存在
//        UmsMember member = umsMemberMapper.selectUmsMemberById(id);
//        if (member == null) {
//            throw new RuntimeException("用户不存在");
//        }

        // 执行增加操作
        int affectedRows = umsMemberMapper.addBalance(id, balance);
        if (affectedRows == 0) {
            throw new RuntimeException("增加余额失败");
        }

        return true;
    }

    /**
     * 查询后台首页数据
     *
     * @return
     */
    @Override
    public HomeDataVO selectHomeDataList() {
        return umsMemberMapper.selectHomeDataList();
    }

}

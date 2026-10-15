package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.PointsStatisticsDTO;
import com.chengyinhub.business.domain.vo.HomeDataVO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 用户Service接口
 * 
 * @author ruoyi
 * @date 2025-08-13
 */
public interface IUmsMemberService 
{

    /**
     * 获取用户积分统计信息
     * @param memberId 用户ID
     * @return 积分统计信息
     */
    PointsStatisticsDTO getPointsStatistics(Long memberId);

    /**
     * 获取用户排名百分比信息
     * @param memberId 用户ID
     * @return 排名信息
     */
    Map<String, Object> getUserRankPercentage(Long memberId);




    /**
     * 获取用户积分统计信息
     * @param memberId 用户ID
     * @return 积分统计信息
     */
    PointsStatisticsDTO getBalanceStatistics(Long memberId);

    /**
     * 获取用户排名百分比信息
     * @param memberId 用户ID
     * @return 排名信息
     */
    Map<String, Object> getUserBalanceRankPercentage(Long memberId);


    /**
     * 查询用户
     * 
     * @param id 用户主键
     * @return 用户
     */
    public UmsMember selectUmsMemberById(Long id);

    /**
     * 对会员行加悲观行锁(SELECT ... FOR UPDATE),串行化该会员的配额敏感写操作。
     * 必须在事务内调用,锁持有到事务结束。
     *
     * @param id 用户主键
     * @return 命中返回该会员 id,不存在返回 null
     */
    public Long lockMemberById(Long id);

    /**
     * 查询用户列表
     * 
     * @param umsMember 用户
     * @return 用户集合
     */
    public List<UmsMember> selectUmsMemberList(UmsMember umsMember);

    /**
     * 新增用户
     * 
     * @param umsMember 用户
     * @return 结果
     */
    public int insertUmsMember(UmsMember umsMember);

    /** 查询唯一微信会员；首次创建与注册赠送原子提交。 */
    UmsMember getOrCreateWechatMember(String openid);

    /**
     * 修改用户
     * 
     * @param umsMember 用户
     * @return 结果
     */
    public int updateUmsMember(UmsMember umsMember);

    /**
     * 批量删除用户
     * 
     * @param ids 需要删除的用户主键集合
     * @return 结果
     */
    public int deleteUmsMemberByIds(Long[] ids);

    /**
     * 删除用户信息
     * 
     * @param id 用户主键
     * @return 结果
     */
    public int deleteUmsMemberById(Long id);

    /**
     * 扣除用户积分
     *
     * @param id 用户ID
     * @param point 扣除的积分数
     * @return 是否扣除成功
     */
    public boolean deductPoints(Long id, Integer point);


    /**
     * 增加用户积分
     *
     * @param id 用户ID
     * @param point 增加的积分数
     * @return 是否增加成功
     */
    public boolean addPoints(Long id, Integer point);


    /**
     * 扣除用户余额
     *
     * @param id 用户ID
     * @param balance 扣除的余额
     * @return 是否扣除成功
     */
    public boolean deductBalance(Long id, BigDecimal balance);


    /**
     * 扣除用户余额,但必须给 {@code frozen}(不可提现的冻结额)留底 —— 提现路径专用。
     *
     * <p>护栏与扣款在同一条 UPDATE 里(见 UmsMemberMapper#deductWithdrawableBalance),
     * 故是原子的;不要用「Java 里先算 available 再 deductBalance」代替 —— 那是 check-then-act,
     * 并发下冻结额会被提走。
     *
     * @param frozen 冻结额(null 视作 0)
     * @return true=扣款成功;false=可用余额不足(含并发被抢先)
     */
    public boolean deductWithdrawableBalance(Long id, BigDecimal amount, BigDecimal frozen);


    /**
     * 增加用户余额
     *
     * @param id 用户ID
     * @param balance 增加的余额
     * @return 是否增加成功
     */
    public boolean addBalance(Long id, BigDecimal balance);


    /**
     * 查询后台首页数据
     *
     * @return 用户首页数据
     */
    public HomeDataVO selectHomeDataList();
}

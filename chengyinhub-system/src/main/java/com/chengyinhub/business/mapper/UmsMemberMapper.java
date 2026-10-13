package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.HomeDataVO;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 用户Mapper接口
 *
 * @author ruoyi
 * @date 2025-08-13
 */
public interface UmsMemberMapper
{
    /**
     * 查询用户
     *
     * @param id 用户主键
     * @return 用户
     */
    public UmsMember selectUmsMemberById(Long id);

    /**
     * 对会员行加悲观行锁(SELECT ... FOR UPDATE),用于串行化该会员的配额敏感写操作(如主题上架)。
     * 必须在事务内调用,锁持有到事务结束。
     *
     * @param id 用户主键
     * @return 命中则返回该会员 id,不存在返回 null
     */
    public Long lockMemberById(Long id);

    /**
     * 加锁读当前已提交余额(null 列按 0)。RR 下普通 select 读事务快照、且同事务同语句会命中
     * MyBatis 一级缓存 ⇒「先 lockMemberById 再 selectUmsMemberById」读到的可能是旧余额。
     * 需要按余额算扣款额的资金路径用这条。行不存在返回 null。
     */
    public java.math.BigDecimal selectBalanceForUpdate(Long id);

    /**
     * 锁定并原子确认会员仍启用；用于权限写入在锁后重新授权，避免前置查询的一级缓存被当成当前事实。
     *
     * @param id 用户主键
     * @return 当前仍启用且未删除则返回 id，否则返回 null
     */
    public Long lockActiveMemberById(Long id);

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

    /**
     * 修改用户
     *
     * @param umsMember 用户
     * @return 结果
     */
    public int updateUmsMember(UmsMember umsMember);

    /**
     * 仅当会员尚未绑定微信身份时原子绑定 OpenID。
     *
     * @return 1=本次绑定成功；0=会员不存在或已被其他请求先行绑定
     */
    public int bindWxOpenIdIfBlank(@Param("id") Long id, @Param("wxOpenId") String wxOpenId);

    /** 仅当数据库仍是本次认证读到的旧明文时升级口令，避免覆盖并发改密。 */
    public int upgradePasswordIfMatches(@Param("id") Long id,
            @Param("observedPassword") String observedPassword,
            @Param("upgradedPassword") String upgradedPassword);

    /**
     * 删除用户
     *
     * @param id 用户主键
     * @return 结果
     */
    public int deleteUmsMemberById(Long id);

    /**
     * 批量删除用户
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsMemberByIds(Long[] ids);

    /**
     * 扣除用户积分
     *
     * @param id 用户ID
     * @param point 扣除的积分数
     * @return 影响的行数
     */
    public int deductPoints(@Param("id") Long id, @Param("point") Integer point);

    /**
     * 增加用户积分
     *
     * @param id 用户ID
     * @param point 增加的积分数
     * @return 影响的行数
     */
    public int addPoints(@Param("id") Long id, @Param("point") Integer point);


    /**
     * 扣除用户余额
     *
     * @param id 用户ID
     * @param balance 扣除的余额
     * @return 影响的行数
     */
    public int deductBalance(@Param("id") Long id, @Param("balance") BigDecimal balance);

    /**
     * 扣余额,但必须给 {@code frozen} 留底 —— 提现专用。
     *
     * <p><b>为什么不能复用 {@link #deductBalance}</b>:它的护栏是 {@code balance >= amount},
     * 只认裸余额、不认冻结。而「先在 Java 里算 available = balance - frozen 再判」是 check-then-act:
     * 余额 50 / 冻结 30(可用 20)时,两个并发提现各 20 都会读到可用 20 而通过,
     * deductBalance 的护栏看裸余额(50≥20、30≥20)也都放行 ⇒ 余额剩 10 &lt; 冻结 30,
     * <b>¥20 冻结款被提走</b>,48h 冻结当场失效。
     *
     * <p>护栏必须和扣款在同一条 UPDATE 里才是原子的 —— 这也是本仓既有的做法。
     *
     * @return 影响行数;0 = 可用余额不够(含并发下被别人抢先扣掉的情况)
     */
    public int deductWithdrawableBalance(@Param("id") Long id,
                                         @Param("amount") BigDecimal amount,
                                         @Param("frozen") BigDecimal frozen);

    /**
     * 增加用户余额
     *
     * @param id 用户ID
     * @param balance 增加的余额
     * @return 影响的行数
     */
    public int addBalance(@Param("id") Long id, @Param("balance") BigDecimal balance);


    /**
     * 查询后台首页数据
     *
     * @return 用户首页数据
     */
    public HomeDataVO selectHomeDataList();

    /** 内容安全:清空用户资料图片(头像/案例图),违规图回调下架用 */
    public int clearProfileMedia(@Param("id") Long id);

    /**
     * 获取用户积分排名信息
     * @param memberId 用户ID
     * @return 排名信息
     */
    Map<String, Object> selectUserRankInfo(@Param("memberId") Long memberId);

    /**
     * 获取用户余额排名信息
     * @param memberId 用户ID
     * @return 排名信息
     */
    Map<String, Object> selectUserBalanceRankInfo(@Param("memberId") Long memberId);


    /**
     * 获取总用户数（排除已删除用户）
     * @return 总用户数
     */
    Integer selectTotalUserCount();

    /** 昵称按数据库唯一键同口径（trim + 大小写不敏感）检查；excludeMemberId 用于本人改资料。 */
    int countActiveNickname(@Param("nickname") String nickname,
                            @Param("excludeMemberId") Long excludeMemberId);
}

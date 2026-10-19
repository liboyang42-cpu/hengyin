package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import org.apache.ibatis.annotations.Param;
import java.util.Date;

/**
 * 用户提现申请Mapper接口
 * 
 * @author webxh
 * @date 2025-10-20
 */
public interface UmsMemberWithdrawalMapper 
{
    /**
     * 查询用户提现申请
     * 
     * @param id 用户提现申请主键
     * @return 用户提现申请
     */
    public UmsMemberWithdrawal selectUmsMemberWithdrawalById(Long id);

    /** 状态 claim winner 回读本行资金事实。 */
    public UmsMemberWithdrawal selectUmsMemberWithdrawalByIdForUpdate(Long id);

    /** 普通重放探测；同一会员的 requestId 唯一。 */
    public UmsMemberWithdrawal selectByMemberAndRequest(@Param("memberId") Long memberId,
                                                        @Param("requestId") String requestId);

    /** 唯一键冲突后用当前读回读 winner，避免 RR 快照看不到并发提交。 */
    public UmsMemberWithdrawal selectByMemberAndRequestForUpdate(@Param("memberId") Long memberId,
                                                                 @Param("requestId") String requestId);

    /** 唯一键冲突后按会员 current-read 单在途 winner，避免 RR 旧快照误报。 */
    public Long selectPendingIdByMemberForUpdate(@Param("memberId") Long memberId);

    /** 本人最近一笔未删除提现申请（只读回显）。 */
    public UmsMemberWithdrawal selectLatestByMember(@Param("memberId") Long memberId);

    /**
     * 查询用户提现申请列表
     * 
     * @param umsMemberWithdrawal 用户提现申请
     * @return 用户提现申请集合
     */
    public List<UmsMemberWithdrawal> selectUmsMemberWithdrawalList(UmsMemberWithdrawal umsMemberWithdrawal);

    /**
     * 新增用户提现申请
     * 
     * @param umsMemberWithdrawal 用户提现申请
     * @return 结果
     */
    public int insertUmsMemberWithdrawal(UmsMemberWithdrawal umsMemberWithdrawal);

    /**
     * 修改用户提现申请
     * 
     * @param umsMemberWithdrawal 用户提现申请
     * @return 结果
     */
    public int updateUmsMemberWithdrawal(UmsMemberWithdrawal umsMemberWithdrawal);

    /** 查询尚未完成加密迁移的历史提现资料。 */
    public List<UmsMemberWithdrawal> selectLegacySensitiveRows(@Param("limit") int limit);

    /** 写入密文和掩码后清除旧明文；只允许未迁移记录更新。 */
    public int migrateSensitiveData(UmsMemberWithdrawal umsMemberWithdrawal);

    /**
     * 删除用户提现申请
     * 
     * @param id 用户提现申请主键
     * @return 结果
     */
    public int deleteUmsMemberWithdrawalById(Long id);

    /**
     * 批量删除用户提现申请
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsMemberWithdrawalByIds(Long[] ids);

    /** 统计用户在途(status=0 待审核或 status=1 审核通过待打款)的银行提现单数。用于注销资金在途拦截。 */
    public int countPendingByMember(@Param("memberId") Long memberId);

    /**
     * 仅当提现单仍为待审核时更新审核状态。
     */
    public int updateWithdrawalStatusIfPending(@Param("id") Long id,
                                               @Param("status") Long status,
                                               @Param("auditBy") Long auditBy,
                                               @Param("auditTime") Date auditTime,
                                               @Param("remark") String remark);

    /** 仅当提现单已审核通过时，原子记录打款凭据并进入已打款态。 */
    public int updateWithdrawalPaidIfApproved(@Param("id") Long id,
                                              @Param("payoutReference") String payoutReference,
                                              @Param("handleBy") Long handleBy,
                                              @Param("handleTime") Date handleTime);

    /** 用户撤回：仅本人待审核单可从 del_flag=0 原子软删。 */
    public int softDeletePendingByMember(@Param("id") Long id,
                                         @Param("memberId") Long memberId,
                                         @Param("updateTime") Date updateTime);
}

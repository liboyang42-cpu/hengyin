package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.domain.dto.WithdrawalPayoutDTO;
import com.chengyinhub.business.domain.dto.WithdrawalReviewDTO;

/**
 * 用户提现申请Service接口
 * 
 * @author webxh
 * @date 2025-10-20
 */
public interface IUmsMemberWithdrawalService 
{
    /**
     * 查询用户提现申请
     * 
     * @param id 用户提现申请主键
     * @return 用户提现申请
     */
    public UmsMemberWithdrawal selectUmsMemberWithdrawalById(Long id);

    /**
     * 查询提现真实收款资料（解密），供财务打款受控使用。
     * 与 selectUmsMemberWithdrawalById 分离：后者对外一律脱敏，本方法解密明文，仅经受审计、需审核权限的接口调用。
     *
     * @param id 用户提现申请主键
     * @return 收款人姓名/银行/账号/电话为明文的提现申请
     */
    public UmsMemberWithdrawal selectWithdrawalPayoutInfo(Long id);

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

    /**
     * 批量删除用户提现申请
     * 
     * @param ids 需要删除的用户提现申请主键集合
     * @return 结果
     */
    public int deleteUmsMemberWithdrawalByIds(Long[] ids);

    /**
     * 删除用户提现申请信息
     * 
     * @param id 用户提现申请主键
     * @return 结果
     */
    public int deleteUmsMemberWithdrawalById(Long id);

    /** 用户撤回本人待审核提现；只有 CAS winner 返回 1。 */
    public int withdrawPendingByMember(Long id, Long memberId);


    /**
     * 用户提现申请
     * @param request WithdrawalDTO
     * @param memberId  用户ID
     * @param username 用户昵称
     * @return
     */
    public default Long withdrawalRequest(WithdrawalDTO request, Long memberId, String username) {
        return withdrawalRequest(request, memberId, username, null);
    }

    /**
     * 用户提现申请，并把建单时已校验的单独同意文本版本固定到申请上。
     * 幂等重放返回原申请，不改写原申请绑定的同意版本与收款资料。
     */
    public Long withdrawalRequest(WithdrawalDTO request, Long memberId, String username, String consentVersion);

    /**
     * 审核提现申请。仅允许待审核状态进入通过或驳回，驳回时退款一次。
     *
     * @param request 审核请求
     * @param operatorId 操作人
     * @return 影响行数
     */
    public int reviewWithdrawal(WithdrawalReviewDTO request, Long operatorId);

    /**
     * 将审核通过的提现单确认成已打款，并原子记录银行流水号/凭据。
     *
     * @param request 打款确认请求
     * @param operatorId 操作人
     * @return 影响行数；非审核通过态或重复确认返回 0
     */
    public int confirmPayout(WithdrawalPayoutDTO request, Long operatorId);

    /**
     * 分批迁移历史提现资料到密文列，并清除旧明文。
     */
    public int migrateLegacySensitiveData(int limit);

    /**
     * 是否存在在途银行提现(status=0 待审核或 status=1 审核通过待打款，钱已扣未打款)。
     * 用于注销资金在途拦截：此时收款人姓名/银行卡是打款依据，不可注销匿名化，否则款项卡死 + PII 被抹 = 资损。
     */
    public boolean hasPendingWithdrawal(Long memberId);
}

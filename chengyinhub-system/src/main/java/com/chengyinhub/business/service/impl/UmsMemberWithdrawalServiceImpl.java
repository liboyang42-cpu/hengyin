package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.domain.dto.WithdrawalPayoutDTO;
import com.chengyinhub.business.domain.dto.WithdrawalReviewDTO;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.security.WithdrawalPiiProtector;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户提现申请Service业务层处理
 * 
 * @author webxh
 * @date 2025-10-20
 */
@Service
public class UmsMemberWithdrawalServiceImpl implements IUmsMemberWithdrawalService 
{
    @Autowired
    private UmsMemberWithdrawalMapper umsMemberWithdrawalMapper;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private UmsMemberBalanceDetailMapper umsMemberBalanceDetailMapper;

    @Autowired
    private WithdrawalPiiProtector withdrawalPiiProtector;

    /** [赏金冻结] 提现前要刨掉仍在 48h 冻结期内的拓展人赏金,见 withdrawalRequest。 */
    @Autowired
    private WithdrawalFrozenAmountResolver frozenAmountResolver;

    /**
     * 查询用户提现申请
     * 
     * @param id 用户提现申请主键
     * @return 用户提现申请
     */
    @Override
    public UmsMemberWithdrawal selectUmsMemberWithdrawalById(Long id)
    {
        UmsMemberWithdrawal withdrawal = umsMemberWithdrawalMapper.selectUmsMemberWithdrawalById(id);
        maskSensitiveData(withdrawal);
        return withdrawal;
    }

    /**
     * 查询提现真实收款资料（解密），供财务打款受控使用。
     * 不脱敏：把密文列解密回明文姓名/银行/账号/电话。历史未迁移行保留其明文列。
     */
    @Override
    public UmsMemberWithdrawal selectWithdrawalPayoutInfo(Long id)
    {
        UmsMemberWithdrawal withdrawal = umsMemberWithdrawalMapper.selectUmsMemberWithdrawalById(id);
        decryptSensitiveData(withdrawal);
        return withdrawal;
    }

    /**
     * 查询用户提现申请列表
     * 
     * @param umsMemberWithdrawal 用户提现申请
     * @return 用户提现申请
     */
    @Override
    public List<UmsMemberWithdrawal> selectUmsMemberWithdrawalList(UmsMemberWithdrawal umsMemberWithdrawal)
    {
        List<UmsMemberWithdrawal> rows = umsMemberWithdrawalMapper.selectUmsMemberWithdrawalList(umsMemberWithdrawal);
        for (UmsMemberWithdrawal row : rows) {
            maskSensitiveData(row);
        }
        return rows;
    }

    /**
     * 新增用户提现申请
     * 
     * @param umsMemberWithdrawal 用户提现申请
     * @return 结果
     */
    @Override
    public int insertUmsMemberWithdrawal(UmsMemberWithdrawal umsMemberWithdrawal)
    {
        RestoreDispatchGuard.requireDispatchAllowed();
        protectSensitiveData(umsMemberWithdrawal);
        umsMemberWithdrawal.setCreateTime(DateUtils.getNowDate());
        return umsMemberWithdrawalMapper.insertUmsMemberWithdrawal(umsMemberWithdrawal);
    }

    @Override
    public boolean hasPendingWithdrawal(Long memberId) {
        if (memberId == null) return false;
        return umsMemberWithdrawalMapper.countPendingByMember(memberId) > 0;
    }

    /**
     * 修改用户提现申请
     * 
     * @param umsMemberWithdrawal 用户提现申请
     * @return 结果
     */
    @Override
    public int updateUmsMemberWithdrawal(UmsMemberWithdrawal umsMemberWithdrawal)
    {
        protectSensitiveData(umsMemberWithdrawal);
        umsMemberWithdrawal.setUpdateTime(DateUtils.getNowDate());
        return umsMemberWithdrawalMapper.updateUmsMemberWithdrawal(umsMemberWithdrawal);
    }

    /**
     * 批量删除用户提现申请
     * 
     * @param ids 需要删除的用户提现申请主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberWithdrawalByIds(Long[] ids)
    {
        return umsMemberWithdrawalMapper.deleteUmsMemberWithdrawalByIds(ids);
    }

    /**
     * 删除用户提现申请信息
     * 
     * @param id 用户提现申请主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberWithdrawalById(Long id)
    {
        return umsMemberWithdrawalMapper.deleteUmsMemberWithdrawalById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int withdrawPendingByMember(Long id, Long memberId) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (id == null || memberId == null) {
            return 0;
        }
        int rows = umsMemberWithdrawalMapper.softDeletePendingByMember(
                id, memberId, DateUtils.getNowDate());
        if (rows != 1) {
            return 0;
        }
        UmsMemberWithdrawal withdrawal = umsMemberWithdrawalMapper.selectUmsMemberWithdrawalByIdForUpdate(id);
        if (withdrawal == null || !memberId.equals(withdrawal.getMemberId())) {
            throw new IllegalStateException("提现撤回状态异常");
        }
        refundWithdrawalBalance(withdrawal, "提现撤回退款");
        return 1;
    }

    /**
     * 用户提现申请
     * @param request WithdrawalDTO
     * @param memberId  用户ID
     * @param username 用户昵称
     * @return
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService balanceAdmissionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long withdrawalRequest(WithdrawalDTO request, Long memberId, String username){
        RestoreDispatchGuard.requireDispatchAllowed();

        // 提现金额必须为正数：防止负数/零金额经 deductBalance(balance = balance - amount) 反向增加余额
        if (request == null || request.getWithdrawalAmount() == null
                || request.getWithdrawalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("提现金额不合法");
        }
        String requestId = request.getRequestId();
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("提现幂等键不能为空");
        }
        requestId = requestId.trim();
        String requestFingerprint = withdrawalFingerprint(memberId, request);
        UmsMemberWithdrawal replay = umsMemberWithdrawalMapper.selectByMemberAndRequest(memberId, requestId);
        if (replay != null) {
            return requireEquivalentReplay(replay, requestFingerprint);
        }

        //查询用户
        UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
        if(member == null){
            throw new RuntimeException("用户不存在");
        }
        BigDecimal balance = member.getBalance();

        // [冻结] 可用余额 = 余额 - 总冻结额(拓展人赏金 + 未到期的 PLAYER 投诉补偿冷静额)。
        //
        // 为什么在这刨:赏金入账后 48h 内可能因退款被追回(MerchantReferralBountyService.onRefund);
        // 投诉补偿同样有 48h 冷静期,期内提走就追不回来了。
        // 两个来源都到期即自然归零,无需任何清理任务。
        //
        // ★两条提现腿(银行卡 + 微信零钱)共用 WithdrawalFrozenAmountResolver 这一个解析点:
        //   只在一条腿刨冻结额的历史故障不许再发生一次。
        // ⚠️ 刻意不 try/catch:任一来源查询挂了就让提现整个失败(fail-closed)。
        // 吞掉异常返回 0 = 冻结静默失效、钱当场提走,正是它要防的那件事。
        WithdrawalFrozenAmountResolver.Frozen frozenParts = frozenAmountResolver.resolve(memberId);
        BigDecimal frozen = frozenParts.total();
        BigDecimal available = balance.subtract(frozen);
        if (available.compareTo(request.getWithdrawalAmount()) < 0) {
            // 命中冷静期时带 PLAYER_WITHDRAWAL_COOLDOWN 机器码：小程序/监控按码分流，不解析中文。
            throw new RuntimeException(frozenParts.rejectionMessage());
        }

        // 先插入提现记录领取业务意图；唯一键 winner 才能扣余额。
        UmsMemberWithdrawal withdrawal = new UmsMemberWithdrawal();
        withdrawal.setMemberId(memberId);
        withdrawal.setRequestId(requestId);
        withdrawal.setRequestFingerprint(requestFingerprint);
        withdrawal.setRealname(request.getRealname());
        withdrawal.setBankName(request.getBankName());
        withdrawal.setBankAccount(request.getBankAccount());
        withdrawal.setMobilephone(request.getMobilephone());
        protectSensitiveData(withdrawal);
        withdrawal.setWithdrawalAmount(request.getWithdrawalAmount());

        BigDecimal serviceFee = BigDecimal.ZERO;
        withdrawal.setServiceFee(serviceFee);
        withdrawal.setReceivedAmount(request.getWithdrawalAmount().subtract(serviceFee));
        withdrawal.setStatus(0L);
        withdrawal.setDelFlag(0L);
        withdrawal.setCreateBy(username);

        try {
            int inserted = umsMemberWithdrawalMapper.insertUmsMemberWithdrawal(withdrawal);
            if (inserted != 1) {
                throw new RuntimeException("插入提现记录失败");
            }
        } catch (DuplicateKeyException duplicate) {
            // current read 看并发 winner；普通 RR 快照可能仍看不到它。
            UmsMemberWithdrawal winner = umsMemberWithdrawalMapper
                    .selectByMemberAndRequestForUpdate(memberId, requestId);
            if (winner != null) {
                return requireEquivalentReplay(winner, requestFingerprint);
            }
            if (umsMemberWithdrawalMapper.selectPendingIdByMemberForUpdate(memberId) != null) {
                throw new IllegalStateException("已有提现正在处理中，请完成后再提交");
            }
            throw duplicate;
        }

        // 唯一键 winner 才扣余额；同事务后续失败会回滚 claim 与余额。
        com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService.Admission admission =
                balanceAdmissionService.debitBank(memberId, request.getWithdrawalAmount());
        if (!admission.deducted) {
            throw new RuntimeException(admission.frozen.rejectionMessage());
        }
        balance = admission.before;


        //插入扣除用户余额变动明细
        UmsMemberBalanceDetail detail = new UmsMemberBalanceDetail();
        detail.setMemberId(memberId);
        detail.setChangeBalance(request.getWithdrawalAmount());
        detail.setAfterBalance(balance.subtract(request.getWithdrawalAmount()));
        detail.setCurrentBalance(balance);
        detail.setChangeReason("提现");
        //事件类型：1、主题分润 2、活动分润  3、提现申请  4、提现驳回
        detail.setEventType(3L);
        //变化类型1：收入  2、支出
        detail.setChangeType(2L);
        detail.setEventId(withdrawal.getId());
        int rows3 = umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(detail);
        if(rows3<=0){
            throw new RuntimeException("插入用户余额变动明细失败");
        }
        return withdrawal.getId();
    }

    private Long requireEquivalentReplay(UmsMemberWithdrawal replay, String requestFingerprint) {
        if (replay.getRequestFingerprint() == null
                || !replay.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IllegalStateException("该提现幂等键已用于不同的提现内容，请重新提交");
        }
        return replay.getId();
    }

    private String withdrawalFingerprint(Long memberId, WithdrawalDTO request) {
        String canonical = fingerprintPart(memberId)
                + fingerprintPart(normalizeMoney(request.getWithdrawalAmount()))
                + fingerprintPart(request.getRealname())
                + fingerprintPart(request.getBankName())
                + fingerprintPart(request.getBankAccount())
                + fingerprintPart(request.getMobilephone());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String normalizeMoney(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }

    private String fingerprintPart(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).trim();
        return normalized.length() + ":" + normalized + "|";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reviewWithdrawal(WithdrawalReviewDTO request, Long operatorId) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (request == null || request.getId() == null) {
            throw new IllegalArgumentException("withdrawal id is required");
        }
        Long status = request.getStatus();
        if (!Long.valueOf(1L).equals(status) && !Long.valueOf(2L).equals(status)) {
            throw new IllegalArgumentException("withdrawal status must be approved or rejected");
        }

        UmsMemberWithdrawal withdrawal = umsMemberWithdrawalMapper.selectUmsMemberWithdrawalById(request.getId());
        if (withdrawal == null) {
            throw new IllegalArgumentException("withdrawal not found");
        }

        int rows = umsMemberWithdrawalMapper.updateWithdrawalStatusIfPending(
                request.getId(), status, operatorId, DateUtils.getNowDate(), request.getReviewReason());
        if (rows <= 0 || !Long.valueOf(2L).equals(status)) {
            return rows;
        }

        refundWithdrawalBalance(withdrawal, "提现驳回退款");
        return rows;
    }

    @Override
    public int confirmPayout(WithdrawalPayoutDTO request, Long operatorId) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (request == null || request.getId() == null) {
            throw new IllegalArgumentException("withdrawal id is required");
        }
        if (operatorId == null) {
            throw new IllegalArgumentException("payout operator is required");
        }
        String payoutReference = request.getPayoutReference();
        if (payoutReference == null || payoutReference.trim().isEmpty()) {
            throw new IllegalArgumentException("payout reference is required");
        }
        return umsMemberWithdrawalMapper.updateWithdrawalPaidIfApproved(
                request.getId(), payoutReference.trim(), operatorId, DateUtils.getNowDate());
    }

    private void refundWithdrawalBalance(UmsMemberWithdrawal withdrawal, String changeReason) {
        RestoreDispatchGuard.requireDispatchAllowed();
        BigDecimal amount = withdrawal.getWithdrawalAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("withdrawal amount is invalid");
        }
        BigDecimal currentBalance = umsMemberMapper.selectBalanceForUpdate(withdrawal.getMemberId());
        if (currentBalance == null) throw new RuntimeException("用户不存在");
        int refundRows = umsMemberMapper.addBalance(withdrawal.getMemberId(), amount);
        if (refundRows <= 0) {
            throw new RuntimeException("退回用户余额失败");
        }

        UmsMemberBalanceDetail detail = new UmsMemberBalanceDetail();
        detail.setMemberId(withdrawal.getMemberId());
        detail.setChangeBalance(amount);
        detail.setCurrentBalance(currentBalance);
        detail.setAfterBalance(currentBalance.add(amount));
        detail.setChangeReason(changeReason);
        detail.setEventType(4L);
        detail.setChangeType(1L);
        detail.setEventId(withdrawal.getId());
        int detailRows = umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(detail);
        if (detailRows <= 0) {
            throw new RuntimeException("插入用户余额变动明细失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int migrateLegacySensitiveData(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int migrated = 0;
        for (UmsMemberWithdrawal withdrawal : umsMemberWithdrawalMapper.selectLegacySensitiveRows(safeLimit)) {
            protectSensitiveData(withdrawal);
            withdrawal.setPiiMigratedAt(DateUtils.getNowDate());
            migrated += umsMemberWithdrawalMapper.migrateSensitiveData(withdrawal);
        }
        return migrated;
    }

    private void protectSensitiveData(UmsMemberWithdrawal withdrawal) {
        if (withdrawal == null || (withdrawal.getRealname() == null && withdrawal.getBankName() == null
                && withdrawal.getBankAccount() == null && withdrawal.getMobilephone() == null)) {
            return;
        }
        String realname = withdrawal.getRealname();
        String bankName = withdrawal.getBankName();
        String bankAccount = withdrawal.getBankAccount();
        String mobilephone = withdrawal.getMobilephone();
        withdrawal.setRealnameEncrypted(withdrawalPiiProtector.encrypt(realname));
        withdrawal.setBankNameEncrypted(withdrawalPiiProtector.encrypt(bankName));
        withdrawal.setBankAccountEncrypted(withdrawalPiiProtector.encrypt(bankAccount));
        withdrawal.setMobilephoneEncrypted(withdrawalPiiProtector.encrypt(mobilephone));
        withdrawal.setRealnameMask(WithdrawalPiiProtector.maskName(realname));
        withdrawal.setBankNameMask(maskBankName(bankName));
        withdrawal.setBankAccountMask(WithdrawalPiiProtector.maskBankAccount(bankAccount));
        withdrawal.setMobilephoneMask(WithdrawalPiiProtector.maskPhone(mobilephone));
        withdrawal.setPiiCipherVersion(1);
        withdrawal.setRealname(null);
        withdrawal.setBankName(null);
        withdrawal.setBankAccount(null);
        withdrawal.setMobilephone(null);
    }

    private void decryptSensitiveData(UmsMemberWithdrawal withdrawal) {
        if (withdrawal == null) {
            return;
        }
        if (withdrawal.getRealnameEncrypted() != null) {
            withdrawal.setRealname(withdrawalPiiProtector.decrypt(withdrawal.getRealnameEncrypted()));
        }
        if (withdrawal.getBankNameEncrypted() != null) {
            withdrawal.setBankName(withdrawalPiiProtector.decrypt(withdrawal.getBankNameEncrypted()));
        }
        if (withdrawal.getBankAccountEncrypted() != null) {
            withdrawal.setBankAccount(withdrawalPiiProtector.decrypt(withdrawal.getBankAccountEncrypted()));
        }
        if (withdrawal.getMobilephoneEncrypted() != null) {
            withdrawal.setMobilephone(withdrawalPiiProtector.decrypt(withdrawal.getMobilephoneEncrypted()));
        }
    }

    private void maskSensitiveData(UmsMemberWithdrawal withdrawal) {
        if (withdrawal == null) {
            return;
        }
        String realname = withdrawal.getRealname();
        String bankName = withdrawal.getBankName();
        String bankAccount = withdrawal.getBankAccount();
        String mobilephone = withdrawal.getMobilephone();
        withdrawal.setRealname(withdrawal.getRealnameMask() == null
                ? WithdrawalPiiProtector.maskName(realname) : withdrawal.getRealnameMask());
        withdrawal.setBankName(withdrawal.getBankNameMask() == null
                ? maskBankName(bankName) : withdrawal.getBankNameMask());
        withdrawal.setBankAccount(withdrawal.getBankAccountMask() == null
                ? WithdrawalPiiProtector.maskBankAccount(bankAccount) : withdrawal.getBankAccountMask());
        withdrawal.setMobilephone(withdrawal.getMobilephoneMask() == null
                ? WithdrawalPiiProtector.maskPhone(mobilephone) : withdrawal.getMobilephoneMask());
    }

    private String maskBankName(String bankName) {
        if (bankName == null || bankName.isEmpty()) {
            return "";
        }
        return bankName.charAt(0) + "***";
    }
}

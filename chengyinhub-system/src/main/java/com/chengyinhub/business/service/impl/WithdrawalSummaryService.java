package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提现页只读回显：当前渠道、服务端可提现额、同意版本与本人最近一笔申请。
 *
 * <p>只读本人数据（memberId 由调用方取自登录态），不写余额、不建单、不改同意记录。
 * 可提现额与建单扣减用同一个 {@link WithdrawalFrozenAmountResolver}，不另造一套余额账；
 * 冻结额解析失败直接抛出（fail-closed），不把总余额冒充可提现额。
 */
@Service
public class WithdrawalSummaryService {
    /** 2026-09-15 收款模型：小程序内提现一律由平台客服人工处理，旧银行卡表单不开放。 */
    static final String CHANNEL_CUSTOMER_SERVICE = "CUSTOMER_SERVICE";

    private final UmsMemberMapper memberMapper;
    private final UmsMemberWithdrawalMapper withdrawalMapper;
    private final WithdrawalFrozenAmountResolver frozenResolver;
    private final IConsentRecordService consentRecordService;
    private final ComplianceProperties complianceProperties;

    public WithdrawalSummaryService(UmsMemberMapper memberMapper,
                                    UmsMemberWithdrawalMapper withdrawalMapper,
                                    WithdrawalFrozenAmountResolver frozenResolver,
                                    IConsentRecordService consentRecordService,
                                    ComplianceProperties complianceProperties) {
        this.memberMapper = memberMapper;
        this.withdrawalMapper = withdrawalMapper;
        this.frozenResolver = frozenResolver;
        this.consentRecordService = consentRecordService;
        this.complianceProperties = complianceProperties;
    }

    public Map<String, Object> summaryFor(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        UmsMember member = memberMapper.selectUmsMemberById(memberId);
        if (member == null) {
            throw new IllegalStateException("用户不存在");
        }
        BigDecimal balance = member.getBalance() == null ? BigDecimal.ZERO : member.getBalance();
        WithdrawalFrozenAmountResolver.Frozen frozen = frozenResolver.resolve(memberId);
        BigDecimal available = balance.subtract(frozen.total());
        if (available.signum() < 0) {
            available = BigDecimal.ZERO;
        }
        String consentDoc = BankWithdrawalCommandServiceImpl.CONSENT_DOCUMENT;
        String consentVersion = complianceProperties.versionOf(consentDoc);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", CHANNEL_CUSTOMER_SERVICE);
        out.put("legacyFormOpen", Boolean.FALSE);
        out.put("balance", balance);
        out.put("frozen", frozen.total());
        out.put("available", available);
        out.put("frozenNote", frozen.isEmpty() ? "" : frozen.describe());
        out.put("consentDocument", consentDoc);
        out.put("consentVersion", consentVersion);
        out.put("consentAgreed", consentRecordService.hasCurrentAgreement(
                memberId, consentDoc, BankWithdrawalCommandServiceImpl.CONSENT_SCENE, consentVersion));
        out.put("latest", latestView(withdrawalMapper.selectLatestByMember(memberId), memberId));
        return out;
    }

    /** 只回显脱敏收款账号与建单时固定的同意版本；明文/密文收款资料一律不出。 */
    private Map<String, Object> latestView(UmsMemberWithdrawal row, Long memberId) {
        if (row == null || !memberId.equals(row.getMemberId())) {
            return null;
        }
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("id", row.getId());
        latest.put("status", row.getStatus());
        latest.put("withdrawalAmount", row.getWithdrawalAmount());
        latest.put("bankAccountMask", row.getBankAccountMask());
        latest.put("consentVersion", row.getConsentVersion());
        latest.put("createTime", row.getCreateTime() == null ? null
                : DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, row.getCreateTime()));
        return latest;
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.BankWithdrawalPreflightRequest;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.exception.FundActionPreflightException;
import com.chengyinhub.business.service.IBankWithdrawalCommandService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IFundActionPreflightService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.common.config.ComplianceProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankWithdrawalCommandServiceImpl implements IBankWithdrawalCommandService {
    static final String CONSENT_DOCUMENT = "bank_account_collection";
    static final String CONSENT_SCENE = "withdrawal";

    private final IFundActionPreflightService preflightService;
    private final IUmsMemberWithdrawalService withdrawalService;
    private final IConsentRecordService consentRecordService;
    private final ComplianceProperties complianceProperties;

    public BankWithdrawalCommandServiceImpl(IFundActionPreflightService preflightService,
                                            IUmsMemberWithdrawalService withdrawalService,
                                            IConsentRecordService consentRecordService,
                                            ComplianceProperties complianceProperties) {
        this.preflightService = preflightService;
        this.withdrawalService = withdrawalService;
        this.consentRecordService = consentRecordService;
        this.complianceProperties = complianceProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submit(WithdrawalDTO request, Long memberId, String username) {
        String currentVersion = complianceProperties.versionOf(CONSENT_DOCUMENT);
        if (!consentRecordService.hasCurrentAgreement(
                memberId, CONSENT_DOCUMENT, CONSENT_SCENE, currentVersion)) {
            throw new FundActionPreflightException(
                    "WITHDRAWAL_CONSENT_REQUIRED", "请先同意提供本次提现所需的银行卡信息");
        }
        preflightService.consumeBankWithdrawal(memberId, request.getChallengeId(), toPreflight(request));
        return withdrawalService.withdrawalRequest(request, memberId, username);
    }

    private BankWithdrawalPreflightRequest toPreflight(WithdrawalDTO request) {
        BankWithdrawalPreflightRequest preflight = new BankWithdrawalPreflightRequest();
        preflight.setRequestId(request.getRequestId());
        preflight.setAmount(request.getWithdrawalAmount());
        preflight.setRealname(request.getRealname());
        preflight.setBankName(request.getBankName());
        preflight.setBankAccount(request.getBankAccount());
        preflight.setMobilephone(request.getMobilephone());
        return preflight;
    }
}

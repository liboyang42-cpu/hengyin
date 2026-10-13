package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.service.ISettlementClaimService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class SettlementClaimServiceImpl implements ISettlementClaimService {
    public static final String LEDGER_CONFLICT = "LEDGER_CONFLICT";

    private final SettlementClaimMapper mapper;

    @Autowired
    public SettlementClaimServiceImpl(SettlementClaimMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SettlementClaim claim(SettlementClaim requested) {
        requireComplete(requested);
        requested.setStatus(SettlementClaim.STATUS_ACTIVE);
        requested.setCreateTime(new Date());
        try {
            if (mapper.insertClaim(requested) != 1 || requested.getId() == null) {
                throw new IllegalStateException("settlement_claim 写入行数异常 key=" + requested.economicKey());
            }
            return requested;
        } catch (DuplicateKeyException duplicate) {
            SettlementClaim current = mapper.selectByEconomicKeyForUpdate(requested);
            if (current == null) {
                throw new IllegalStateException(LEDGER_CONFLICT + ":唯一键冲突后无法读取 claim key="
                        + requested.economicKey(), duplicate);
            }
            if (sameText(current.getSettlementRoute(), requested.getSettlementRoute())
                    && sameText(current.getSourceContractType(), requested.getSourceContractType())
                    && sameNumber(current.getSourceContractId(), requested.getSourceContractId())) {
                return current;
            }
            throw new IllegalStateException(LEDGER_CONFLICT + ":economicClaimKey 已由 route="
                    + current.getSettlementRoute() + ",sourceContract=" + current.getSourceContractType()
                    + "#" + current.getSourceContractId() + " 占用;requested="
                    + requested.getSettlementRoute() + "," + requested.getSourceContractType()
                    + "#" + requested.getSourceContractId() + ",key=" + requested.economicKey());
        }
    }

    private static void requireComplete(SettlementClaim c) {
        if (c == null || blank(c.getCommercialContextType()) || c.getCommercialContextId() == null
                || blank(c.getFulfillmentType()) || c.getFulfillmentId() == null
                || blank(c.getBeneficiaryRole()) || c.getBeneficiaryId() == null
                || blank(c.getServiceUnit()) || blank(c.getSettlementRoute())
                || blank(c.getSourceContractType()) || c.getSourceContractId() == null) {
            throw new IllegalArgumentException("settlement_claim 全字段必须非空");
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean sameText(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static boolean sameNumber(Long a, Long b) { return a == null ? b == null : a.equals(b); }
}

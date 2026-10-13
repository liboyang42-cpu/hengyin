package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.service.impl.SettlementClaimServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementClaimServiceImplTest {

    @Test
    void sameEconomicClaimCannotChangeRoute() {
        InMemoryClaimMapper mapper = new InMemoryClaimMapper();
        SettlementClaimServiceImpl service = new SettlementClaimServiceImpl(mapper);

        SettlementClaim direct = registrationClaim("MERCHANT", 88L,
                "MERCHANT_PARTICIPATION", "REGISTRATION_SPLIT", "DIRECT_SPLIT", 501L);
        service.claim(direct);

        SettlementClaim coop = registrationClaim("MERCHANT", 88L,
                "MERCHANT_PARTICIPATION", "COOP_ORDER", "COOP_ORDER", 701L);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.claim(coop));

        assertTrue(error.getMessage().contains(SettlementClaimServiceImpl.LEDGER_CONFLICT),
                "跨轨必须命中统一闸文案 LEDGER_CONFLICT,实际:" + error.getMessage());
        assertEquals(1, mapper.size(), "跨轨失败不得写第二个 claim");
    }

    @Test
    void differentRoleAndServiceUnitMayCoexistOnOneRegistration() {
        InMemoryClaimMapper mapper = new InMemoryClaimMapper();
        SettlementClaimServiceImpl service = new SettlementClaimServiceImpl(mapper);

        SettlementClaim merchant = service.claim(registrationClaim("MERCHANT", 88L,
                "MERCHANT_PARTICIPATION", "COOP_ORDER", "COOP_ORDER", 701L));
        SettlementClaim club = service.claim(registrationClaim("CLUB", 99L,
                "ORGANIZER_LEAD", "COOP_ORDER", "COOP_ORDER", 702L));

        assertNotEquals(merchant.getId(), club.getId(), "不同 role/serviceUnit 是两个合法经济请求");
        assertEquals(2, mapper.size(), "错误地只按 registration 去重会吞掉其中一个角色");
    }

    @Test
    void sameRouteAndSourceRetryReturnsSameClaimId() {
        InMemoryClaimMapper mapper = new InMemoryClaimMapper();
        SettlementClaimServiceImpl service = new SettlementClaimServiceImpl(mapper);
        SettlementClaim request = registrationClaim("MERCHANT", 88L,
                "MERCHANT_PARTICIPATION", "COOP_ORDER", "COOP_ORDER", 701L);

        Long first = service.claim(request).getId();
        Long retry = service.claim(registrationClaim("MERCHANT", 88L,
                "MERCHANT_PARTICIPATION", "COOP_ORDER", "COOP_ORDER", 701L)).getId();

        assertEquals(first, retry, "同轨同合同重试必须返回同一 claimId");
        assertEquals(1, mapper.size(), "同轨重试不得双占 claim");
    }

    @Test
    void noneRouteStillOccupiesClaimAndBlocksLaterPaidRoute() {
        InMemoryClaimMapper mapper = new InMemoryClaimMapper();
        SettlementClaimServiceImpl service = new SettlementClaimServiceImpl(mapper);

        SettlementClaim none = chapterClaim("NONE", 801L);
        Long noneId = service.claim(none).getId();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.claim(chapterClaim("CHAPTER_OFFER", 801L)));

        assertTrue(noneId > 0, "NONE 零现金履约也必须占 claim");
        assertTrue(error.getMessage().contains(SettlementClaimServiceImpl.LEDGER_CONFLICT),
                "NONE 后换付费轨必须被唯一闸拒绝");
        assertEquals(1, mapper.size(), "零现金占位不生成第二条付费 claim");
    }

    private static SettlementClaim registrationClaim(String role, Long beneficiaryId, String serviceUnit,
            String route, String sourceType, Long sourceId) {
        return SettlementClaim.request("TOPIC_EDITION", 11L, "REGISTRATION", 1001L,
                role, beneficiaryId, serviceUnit, route, sourceType, sourceId);
    }

    private static SettlementClaim chapterClaim(String route, Long offerId) {
        return SettlementClaim.request("TOPIC_EDITION", 11L, "REDEMPTION", 2001L,
                "MERCHANT", 88L, "CHAPTER_SUPPLY", route, "MERCHANT_CHAPTER_OFFER", offerId);
    }

    private static final class InMemoryClaimMapper implements SettlementClaimMapper {
        private final Map<String, SettlementClaim> rows = new HashMap<>();
        private long nextId = 1L;

        @Override
        public int insertClaim(SettlementClaim claim) {
            String key = claim.economicKey();
            if (rows.containsKey(key)) {
                throw new DuplicateKeyException("uk_settlement_claim_key");
            }
            claim.setId(nextId++);
            rows.put(key, copy(claim));
            return 1;
        }

        @Override
        public SettlementClaim selectByEconomicKeyForUpdate(SettlementClaim key) {
            SettlementClaim row = rows.get(key.economicKey());
            return row == null ? null : copy(row);
        }

        int size() {
            return rows.size();
        }

        private SettlementClaim copy(SettlementClaim source) {
            SettlementClaim copy = SettlementClaim.request(source.getCommercialContextType(),
                    source.getCommercialContextId(), source.getFulfillmentType(), source.getFulfillmentId(),
                    source.getBeneficiaryRole(), source.getBeneficiaryId(), source.getServiceUnit(),
                    source.getSettlementRoute(), source.getSourceContractType(), source.getSourceContractId());
            copy.setId(source.getId());
            copy.setStatus(source.getStatus());
            return copy;
        }
    }
}

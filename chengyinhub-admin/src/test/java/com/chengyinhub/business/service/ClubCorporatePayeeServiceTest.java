package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import com.chengyinhub.business.mapper.ClubCorporatePayeeProfileMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.impl.ClubCorporatePayeeServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A4 企业收款人：九组最小资料、财务一次审核、版本失效、开售+FINAL 双闸，全部 fail-closed。 */
class ClubCorporatePayeeServiceTest {
    private static final long CLUB = 8L;
    private static final long TOPIC = 23L;
    private static final long CLUB_WRITER = 71L;
    private static final long FINANCE = 72L;

    private ClubCorporatePayeeProfileMapper mapper;
    private PiiCryptoService crypto;
    private IClubService clubService;
    private ClubCorporatePayeeServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubCorporatePayeeProfileMapper.class);
        crypto = mock(PiiCryptoService.class);
        when(crypto.isEnabled()).thenReturn(true);
        when(mapper.insertVersion(any())).thenReturn(1);
        when(mapper.insertSaleSnapshot(any())).thenReturn(1);
        clubService = mock(IClubService.class);
        when(clubService.selectClubById(CLUB)).thenReturn(new Club());
        service = new ClubCorporatePayeeServiceImpl(mapper, crypto, clubService);
    }

    /**
     * ★写 PII 之前先回读归属：clubId 只从路径来，但它必须真指向一个存在且未删除的俱乐部
     * （selectClubById 自带 del_flag = 0）。否则一个任意数字就能落一版对公收款人 PII，
     * 而没有任何主体认领它 —— AGENTS.md 的「新增写接口必须校验 owner/scope」正是这一条。
     */
    @Test
    void aProfileCannotBeWrittenForAClubThatDoesNotExist() {
        when(clubService.selectClubById(CLUB)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createVersion(CLUB, valid(), CLUB_WRITER));

        assertEquals("PAYEE_CLUB_UNKNOWN", error.getMessage());
        verify(mapper, never()).insertVersion(any());
    }

    // ── §5.4-1 九组最小字段逐一置空，每一项单独拒绝 ─────────────────────────
    @Test
    void eachMinimalFieldGroupIsRejectedOnItsOwn() {
        String[] groups = {"legalName", "unifiedSocialCreditCode", "licenseObjectKey", "licenseHash",
                "bankAccountName", "bankName", "corporateBankAccountNo", "invoiceType",
                "financeContactName", "financeContactPhone"};
        for (String group : groups) {
            ClubCorporatePayeeProfile input = valid();
            blank(input, group);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.createVersion(CLUB, input, CLUB_WRITER),
                    "字段 " + group + " 置空后仍被接受");
            assertTrue(error.getMessage().contains(group),
                    "错误必须点名缺失的那一组，实际:" + error.getMessage());
        }
        verify(mapper, never()).insertVersion(any());
    }

    /** 契约里唯一有默认值的一组：税号缺省等于统一社会信用代码，但落库仍必须非空。 */
    @Test
    void taxNoDefaultsToCreditCodeInsteadOfBeingRejected() {
        ClubCorporatePayeeProfile input = valid();
        input.setTaxNo("  ");

        ClubCorporatePayeeProfile saved = service.createVersion(CLUB, input, CLUB_WRITER);

        assertEquals("91310000MA1FL0AB2C", saved.getTaxNo());
    }

    // ── §5.4-2 银行账户名必须等于企业法定名称 ────────────────────────────
    @Test
    void bankAccountNameMustEqualLegalName() {
        ClubCorporatePayeeProfile input = valid();
        input.setBankAccountName("城瘾探索者俱乐部(上海)");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createVersion(CLUB, input, CLUB_WRITER));

        assertEquals("PAYEE_BANK_ACCOUNT_NAME_MISMATCH", error.getMessage());
        verify(mapper, never()).insertVersion(any());
    }

    // ── §5.4-5 缺 key 时六条路径分别 fail closed ───────────────────────────
    @Test
    void allSixPathsFailClosedWhenCryptoKeyIsMissing() {
        when(crypto.isEnabled()).thenReturn(false);
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(verified());
        when(mapper.selectById(9L)).thenReturn(verified());
        when(mapper.selectSaleSnapshot(TOPIC, CLUB)).thenReturn(snapshot());

        assertKeyMissing(() -> service.createVersion(CLUB, valid(), CLUB_WRITER));
        assertKeyMissing(() -> service.maskedView(CLUB));
        assertKeyMissing(() -> service.verify(CLUB, 9L, FINANCE, true, "LICENSE_HASH"));
        assertKeyMissing(() -> service.requireVerifiedForSale(CLUB));
        assertKeyMissing(() -> service.requireSnapshotForFinal(TOPIC, CLUB));
        assertKeyMissing(() -> service.payoutView(CLUB));

        verify(mapper, never()).insertVersion(any());
        verify(mapper, never()).verifyVersionCas(anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), anyString());
    }

    // ── §5.4-6 关键字段变化 ⇒ 新版本 + 既有审核失效 ────────────────────────
    @Test
    void changingKeyFieldCreatesNewPendingVersionAndInvalidatesTheVerifiedOne() {
        ClubCorporatePayeeProfile v1 = verified();
        when(mapper.selectCurrentForUpdate(CLUB)).thenReturn(v1);
        when(mapper.invalidateCurrent(CLUB)).thenReturn(1);
        ClubCorporatePayeeProfile input = valid();
        input.setCorporateBankAccountNo("6222020200099998888");

        ClubCorporatePayeeProfile v2 = service.createVersion(CLUB, input, CLUB_WRITER);

        assertEquals(2L, v2.getVersion());
        assertEquals(ClubCorporatePayeeProfile.VERIFY_PENDING, v2.getVerifyStatus());
        assertEquals(Integer.valueOf(1), v2.getCurrent());
        assertNull(v2.getVerifiedBy());
        verify(mapper).invalidateCurrent(CLUB);
    }

    @Test
    void newVersionIsRefusedWhenTheOldCurrentCannotBeInvalidated() {
        when(mapper.selectCurrentForUpdate(CLUB)).thenReturn(verified());
        when(mapper.invalidateCurrent(CLUB)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createVersion(CLUB, valid(), CLUB_WRITER));

        assertEquals("PAYEE_CURRENT_INVALIDATE_CONFLICT", error.getMessage());
        verify(mapper, never()).insertVersion(any());
    }

    // ── §5.4-7 审核越权 / 重复审核 ─────────────────────────────────────────

    /**
     * 契约字面是「一名当前登录财务人员、持 business:clubpayee:verify，做<b>一次</b>审核」，
     * 不是两人制。此前实现额外要求 verifier != createdBy —— 在「后台只用超管单账号」的既定运营
     * 模型下，createdBy 恒等于 financeUserId ⇒ 任何 profile 都过不了审 ⇒ requireVerifiedForSale
     * 恒抛 SALE_PAYEE_NOT_VERIFIED ⇒ 探店日主题永远进不了 SELLING。
     *
     * <p>被禁的是「俱乐部 owner 自审」——那条由「根本没有俱乐部侧 payee 写/审入口」保证，
     * 见 {@code ClubCorporatePayeeExternalSelfVerifyClosedTest}，不靠这条 createdBy 比较。
     */
    @Test
    void theSameBackOfficeAccountMayRecordAndVerifyBecauseTheContractIsOneFinanceReview() {
        ClubCorporatePayeeProfile pending = pending();
        when(mapper.selectById(9L)).thenReturn(pending);
        when(mapper.verifyVersionCas(eq(9L), eq(CLUB), eq(CLUB_WRITER), any(), anyString(), anyString(),
                anyString())).thenReturn(1);

        service.verify(CLUB, 9L, CLUB_WRITER, true, "LICENSE_HASH");

        verify(mapper).verifyVersionCas(eq(9L), eq(CLUB), eq(CLUB_WRITER), any(),
                eq("VERIFIED"), eq("PASS"), eq("LICENSE_HASH"));
    }

    @Test
    void secondConcurrentVerifyLosesTheCasInsteadOfDoubleVerifying() {
        when(mapper.selectById(9L)).thenReturn(pending());
        when(mapper.verifyVersionCas(eq(9L), eq(CLUB), eq(FINANCE), any(), anyString(), anyString(),
                anyString())).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.verify(CLUB, 9L, FINANCE, true, "LICENSE_HASH"));

        assertEquals("PAYEE_VERIFY_CONFLICT", error.getMessage());
    }

    @Test
    void alreadyVerifiedVersionIsNotVerifiedTwice() {
        when(mapper.selectById(9L)).thenReturn(verified());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.verify(CLUB, 9L, FINANCE, true, "LICENSE_HASH"));

        assertEquals("PAYEE_ALREADY_VERIFIED", error.getMessage());
        verify(mapper, never()).verifyVersionCas(anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    void crossClubProfileIdCannotBeVerified() {
        ClubCorporatePayeeProfile other = pending();
        other.setClubId(99L);
        when(mapper.selectById(9L)).thenReturn(other);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.verify(CLUB, 9L, FINANCE, true, "LICENSE_HASH"));

        assertEquals("PAYEE_PROFILE_NOT_FOUND", error.getMessage());
    }

    // ── §5.4-8 执照篡改：观测 hash 与登记 hash 不符 ⇒ 审核与开售均拒绝 ────────
    @Test
    void licenseHashMismatchBlocksVerification() {
        when(mapper.selectById(9L)).thenReturn(pending());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.verify(CLUB, 9L, FINANCE, true, "TAMPERED"));

        assertEquals("PAYEE_LICENSE_HASH_MISMATCH", error.getMessage());
        verify(mapper, never()).verifyVersionCas(anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    void factsHashDriftBlocksSale() {
        ClubCorporatePayeeProfile drifted = verified();
        drifted.setFactsHash("0000000000000000000000000000000000000000000000000000000000000000");
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(drifted);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.requireVerifiedForSale(CLUB));

        assertEquals("SALE_PAYEE_FACTS_TAMPERED", error.getMessage());
    }

    @Test
    void saleIsBlockedWhenNoVerifiedCurrentVersionExists() {
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.requireVerifiedForSale(CLUB));

        assertEquals("SALE_PAYEE_NOT_VERIFIED", error.getMessage());
    }

    // ── §5.4-9 开售快照不可被后来的 v2 覆盖 ────────────────────────────────
    @Test
    void saleSnapshotFreezesTheVersionThatWasVerifiedAtSaleTime() {
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(verified());
        Date frozenAt = new Date(1_700_000_000_000L);

        TopicSalePayeeSnapshot frozen = service.snapshotForSale(TOPIC, CLUB, frozenAt);

        assertEquals(9L, frozen.getProfileId());
        assertEquals(1L, frozen.getProfileVersion());
        assertEquals("LICENSE_HASH", frozen.getLicenseHash());
        assertEquals(frozenAt, frozen.getFrozenAt());
        verify(mapper).insertSaleSnapshot(any());
    }

    @Test
    void repeatedSaleFinalizationReturnsTheExistingSnapshotWithoutRewritingIt() {
        when(mapper.selectSaleSnapshot(TOPIC, CLUB)).thenReturn(snapshot());

        TopicSalePayeeSnapshot again = service.snapshotForSale(TOPIC, CLUB, new Date());

        assertEquals(1L, again.getProfileVersion());
        verify(mapper, never()).insertSaleSnapshot(any());
    }

    @Test
    void finalReadsTheFrozenSnapshotNotTheLatestProfile() {
        when(mapper.selectSaleSnapshot(TOPIC, CLUB)).thenReturn(snapshot());
        ClubCorporatePayeeProfile v2 = verified();
        v2.setId(10L);
        v2.setVersion(2L);
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(v2);

        TopicSalePayeeSnapshot atFinal = service.requireSnapshotForFinal(TOPIC, CLUB);

        assertEquals(9L, atFinal.getProfileId(), "FINAL 必须用开售冻结的 v1，不得被 v2 覆盖");
        assertEquals(1L, atFinal.getProfileVersion());
    }

    // ── §5.4-10 双闸：绕过开售闸的旧 topic 在 FINAL 仍被拒 ──────────────────
    @Test
    void finalIsBlockedWhenSaleGateWasNeverPassed() {
        when(mapper.selectSaleSnapshot(TOPIC, CLUB)).thenReturn(null);
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(verified());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.requireSnapshotForFinal(TOPIC, CLUB));

        assertEquals("FINAL_PAYEE_SNAPSHOT_MISSING", error.getMessage());
    }

    @Test
    void finalIsBlockedWhenThePayeeCanNoLongerBePaid() {
        when(mapper.selectSaleSnapshot(TOPIC, CLUB)).thenReturn(snapshot());
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.requireSnapshotForFinal(TOPIC, CLUB));

        assertEquals("FINAL_PAYEE_NOT_PAYABLE", error.getMessage());
    }

    // ── §5.4-11 默认视图只给掩码 ──────────────────────────────────────────
    @Test
    void defaultViewNeverLeaksPlaintextAccountOrPhone() {
        when(mapper.selectCurrent(CLUB)).thenReturn(verified());

        Map<String, Object> masked = service.maskedView(CLUB);

        assertFalse(String.valueOf(masked).contains("6222020200011112222"));
        assertFalse(String.valueOf(masked).contains("13800001234"));
        assertEquals("138****1234", masked.get("financeContactPhone"));
        assertEquals("62****22", masked.get("corporateBankAccountNo"));
    }

    @Test
    void serializedDefaultViewDisclosesCorporateNamesButNeverCredentials() throws Exception {
        when(mapper.selectCurrent(CLUB)).thenReturn(verified());

        String json = new ObjectMapper().writeValueAsString(service.maskedView(CLUB));
        JsonNode view = new ObjectMapper().readTree(json);

        assertEquals("上海城瘾探索者体育文化有限公司", view.get("legalName").asText());
        assertEquals("上海城瘾探索者体育文化有限公司", view.get("bankAccountName").asText());
        assertFalse(json.contains("6222020200011112222"), "默认 JSON 不得泄露完整卡号");
        assertFalse(json.contains("13800001234"), "默认 JSON 不得泄露完整手机号");
    }

    /**
     * ★掩码视图是纯读，绝不能发锁定读。
     *
     * <p>它跑在 {@code @Transactional(readOnly = true)} 里；Spring 会 setReadOnly(true)，
     * Connector/J 的 readOnlyPropagatesToServer 默认为真 ⇒ 会话进入 TRANSACTION READ ONLY ⇒
     * MySQL 对 SELECT … FOR UPDATE 回 ERROR 1792，查看/保存回显/审核回显三条路全 500。
     * 退一步说，查个掩码要拿行级排它锁本身也会和 createVersion 互相阻塞。
     * 写事务里的 selectCurrentForUpdate 保留不动 —— 那里加锁是对的。
     */
    @Test
    void theMaskedViewReadsWithoutTakingARowLock() {
        when(mapper.selectCurrent(CLUB)).thenReturn(verified());

        service.maskedView(CLUB);

        verify(mapper).selectCurrent(CLUB);
        verify(mapper, never()).selectCurrentForUpdate(any());
    }

    @Test
    void theWritePathStillTakesTheRowLockBeforeCreatingANewVersion() {
        when(mapper.selectCurrentForUpdate(CLUB)).thenReturn(verified());
        when(mapper.invalidateCurrent(CLUB)).thenReturn(1);

        service.createVersion(CLUB, valid(), CLUB_WRITER);

        verify(mapper).selectCurrentForUpdate(CLUB);
    }

    @Test
    void payoutViewReturnsPlaintextOnlyForTheVerifiedCurrentVersion() {
        when(mapper.selectVerifiedCurrent(CLUB)).thenReturn(verified());

        Map<String, Object> payout = service.payoutView(CLUB);

        assertEquals("6222020200011112222", payout.get("corporateBankAccountNo"));
        assertEquals("13800001234", payout.get("financeContactPhone"));
    }

    private void assertKeyMissing(org.junit.jupiter.api.function.Executable call) {
        IllegalStateException error = assertThrows(IllegalStateException.class, call);
        assertEquals("PAYEE_CRYPTO_KEY_UNAVAILABLE", error.getMessage());
    }

    private static ClubCorporatePayeeProfile valid() {
        ClubCorporatePayeeProfile p = new ClubCorporatePayeeProfile();
        p.setLegalName("上海城瘾探索者体育文化有限公司");
        p.setUnifiedSocialCreditCode("91310000MA1FL0AB2C");
        p.setLicenseObjectKey("private/club/8/license.pdf");
        p.setLicenseHash("LICENSE_HASH");
        p.setBankAccountName("上海城瘾探索者体育文化有限公司");
        p.setBankName("招商银行上海分行营业部");
        p.setCorporateBankAccountNo("6222020200011112222");
        p.setInvoiceType("SPECIAL_VAT");
        p.setTaxNo("91310000MA1FL0AB2C");
        p.setFinanceContactName("周财务");
        p.setFinanceContactPhone("13800001234");
        return p;
    }

    private ClubCorporatePayeeProfile pending() {
        ClubCorporatePayeeProfile p = valid();
        p.setId(9L);
        p.setClubId(CLUB);
        p.setVersion(1L);
        p.setCurrent(1);
        p.setVerifyStatus(ClubCorporatePayeeProfile.VERIFY_PENDING);
        p.setCreatedBy(CLUB_WRITER);
        p.setFactsHash(ClubCorporatePayeeServiceImpl.factsHash(p));
        return p;
    }

    private ClubCorporatePayeeProfile verified() {
        ClubCorporatePayeeProfile p = pending();
        p.setVerifyStatus(ClubCorporatePayeeProfile.VERIFY_VERIFIED);
        p.setVerifiedBy(FINANCE);
        p.setVerifiedAt(new Date(1_690_000_000_000L));
        p.setVerifyResult("PASS");
        return p;
    }

    private TopicSalePayeeSnapshot snapshot() {
        TopicSalePayeeSnapshot s = new TopicSalePayeeSnapshot();
        s.setTopicId(TOPIC);
        s.setClubId(CLUB);
        s.setProfileId(9L);
        s.setProfileVersion(1L);
        s.setFactsHash(ClubCorporatePayeeServiceImpl.factsHash(pending()));
        s.setLicenseHash("LICENSE_HASH");
        s.setFrozenAt(new Date(1_690_000_000_000L));
        return s;
    }

    private static void blank(ClubCorporatePayeeProfile p, String group) {
        if ("legalName".equals(group)) p.setLegalName("  ");
        else if ("unifiedSocialCreditCode".equals(group)) p.setUnifiedSocialCreditCode(null);
        else if ("licenseObjectKey".equals(group)) p.setLicenseObjectKey(null);
        else if ("licenseHash".equals(group)) p.setLicenseHash("");
        else if ("bankAccountName".equals(group)) p.setBankAccountName(null);
        else if ("bankName".equals(group)) p.setBankName(" ");
        else if ("corporateBankAccountNo".equals(group)) p.setCorporateBankAccountNo(null);
        else if ("invoiceType".equals(group)) p.setInvoiceType(null);
        else if ("financeContactName".equals(group)) p.setFinanceContactName(null);
        else if ("financeContactPhone".equals(group)) p.setFinanceContactPhone("");
        else throw new IllegalArgumentException("未知字段组 " + group);
    }
}

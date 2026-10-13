package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import com.chengyinhub.business.mapper.ClubCorporatePayeeProfileMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IClubCorporatePayeeService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A4 俱乐部对公收款人。写资料 / 财务审核 / 受控解密三条路各自鉴权，
 * 开售与 FINAL 是两道独立的闸，快照写下即不可变。
 *
 * <p>★ 全部 fail-closed：缺密钥、缺 current、未审核、执照 hash 不符、关键事实指纹漂移，
 * 六条路径一律抛错，绝不降级成明文 / 掩码 / 放行。
 */
@Service
public class ClubCorporatePayeeServiceImpl implements IClubCorporatePayeeService {

    private final ClubCorporatePayeeProfileMapper mapper;
    private final PiiCryptoService cryptoService;
    private final IClubService clubService;

    public ClubCorporatePayeeServiceImpl(ClubCorporatePayeeProfileMapper mapper,
                                         PiiCryptoService cryptoService,
                                         IClubService clubService) {
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.clubService = clubService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubCorporatePayeeProfile createVersion(Long clubId, ClubCorporatePayeeProfile input, Long operatorId) {
        if (clubId == null || input == null || operatorId == null) {
            throw new IllegalArgumentException("PAYEE_ARGUMENT_REQUIRED");
        }
        requireCryptoKey();
        // 归属回读：clubId 只从路径来（copy 刻意不搬 body 里的 clubId），但它必须真指向一个
        // 存在且未删除的俱乐部 —— selectClubById 自带 del_flag = 0。
        // 少了这一句，一个任意数字就能落一版对公收款人 PII，而没有任何主体认领它。
        if (clubService.selectClubById(clubId) == null) {
            throw new IllegalStateException("PAYEE_CLUB_UNKNOWN");
        }
        ClubCorporatePayeeProfile draft = copy(input);
        // 税号是九组里唯一有既定默认值的一组：缺省等于统一社会信用代码，仍要落库非空。
        if (isBlank(draft.getTaxNo())) draft.setTaxNo(trim(draft.getUnifiedSocialCreditCode()));
        requireMinimalFields(draft);
        if (!draft.getLegalName().equals(draft.getBankAccountName())) {
            throw new IllegalArgumentException("PAYEE_BANK_ACCOUNT_NAME_MISMATCH");
        }

        ClubCorporatePayeeProfile current = mapper.selectCurrentForUpdate(clubId);
        if (current != null && mapper.invalidateCurrent(clubId) < 1) {
            throw new IllegalStateException("PAYEE_CURRENT_INVALIDATE_CONFLICT");
        }
        draft.setId(null);
        draft.setClubId(clubId);
        draft.setVersion(current == null ? 1L : current.getVersion() + 1L);
        draft.setCurrent(1);
        // blind index 列拿明文进 mapper，由 PiiBlindIndexTypeHandler 做 HMAC；缺密钥时它自己也 fail-closed。
        draft.setCreditCodeIndex(draft.getUnifiedSocialCreditCode());
        draft.setFactsHash(factsHash(draft));
        draft.setVerifyStatus(ClubCorporatePayeeProfile.VERIFY_PENDING);
        draft.setVerifiedBy(null);
        draft.setVerifiedAt(null);
        draft.setVerifyResult(null);
        draft.setCreatedBy(operatorId);
        if (mapper.insertVersion(draft) != 1) throw new IllegalStateException("PAYEE_VERSION_WRITE_FAILED");
        return draft;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubCorporatePayeeProfile verify(Long clubId, Long profileId, Long financeUserId,
                                            boolean pass, String observedLicenseHash) {
        if (clubId == null || profileId == null || financeUserId == null) {
            throw new IllegalArgumentException("PAYEE_ARGUMENT_REQUIRED");
        }
        requireCryptoKey();
        ClubCorporatePayeeProfile profile = mapper.selectById(profileId);
        if (profile == null || !clubId.equals(profile.getClubId())) {
            throw new IllegalStateException("PAYEE_PROFILE_NOT_FOUND");
        }
        if (!Integer.valueOf(1).equals(profile.getCurrent())) {
            throw new IllegalStateException("PAYEE_VERSION_NOT_CURRENT");
        }
        if (!ClubCorporatePayeeProfile.VERIFY_PENDING.equals(profile.getVerifyStatus())) {
            throw new IllegalStateException("PAYEE_ALREADY_VERIFIED");
        }
        // ★这里刻意不做「录入人 ≠ 审核人」的两人制校验。
        //   契约字面是「一名当前登录财务人员、持 business:clubpayee:verify，做一次审核」；
        //   而运营模型已定为「后台只用超管单账号」⇒ createdBy 恒等于 financeUserId ⇒
        //   加这条比较就等于所有 profile 永远过不了审、探店日主题永远进不了 SELLING。
        //   被禁的是「俱乐部 owner 自审」，那由「压根没有俱乐部侧的 payee 写/审入口」保证
        //   （ClubCorporatePayeeExternalSelfVerifyClosedTest 钉住），不靠这条 createdBy 比较。
        //   ⚠️ 与批次的「确认人 ≠ 付款登记人」不是一回事 —— 那条是施工包 §1 不变量 5 的明令，保留。
        if (pass && (isBlank(observedLicenseHash) || !observedLicenseHash.equals(profile.getLicenseHash()))) {
            throw new IllegalStateException("PAYEE_LICENSE_HASH_MISMATCH");
        }
        String status = pass ? ClubCorporatePayeeProfile.VERIFY_VERIFIED
                : ClubCorporatePayeeProfile.VERIFY_REJECTED;
        // CAS 条件在 XML 里：id + club + is_current=1 + verify_status='PENDING'，两人并发只有一个改到行。
        if (mapper.verifyVersionCas(profileId, clubId, financeUserId, new Date(), status,
                pass ? "PASS" : "REJECT", profile.getLicenseHash()) != 1) {
            throw new IllegalStateException("PAYEE_VERIFY_CONFLICT");
        }
        return mapper.selectById(profileId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> maskedView(Long clubId) {
        if (clubId == null) throw new IllegalArgumentException("PAYEE_ARGUMENT_REQUIRED");
        requireCryptoKey();
        // 纯读用不带 for update 的语句：本方法是 readOnly 事务，锁定读会被 MySQL 拒（ERROR 1792），
        // 而且会与 createVersion 的锁定读互相阻塞。
        ClubCorporatePayeeProfile profile = mapper.selectCurrent(clubId);
        if (profile == null) throw new IllegalStateException("PAYEE_PROFILE_NOT_FOUND");
        Map<String, Object> view = head(profile);
        view.put("legalName", profile.getLegalName());
        view.put("unifiedSocialCreditCode", PiiCryptoService.maskMiddle(profile.getUnifiedSocialCreditCode()));
        // 对公账户名写入时强制等于企业法定名称；同一响应已明文下发 legalName，
        // 再只给 bankAccountName 做姓名脱敏是装饰性闸。公开企业名称非个人 PII，统一按明文口径供核对。
        view.put("bankAccountName", profile.getBankAccountName());
        view.put("bankName", profile.getBankName());
        view.put("corporateBankAccountNo", PiiCryptoService.maskMiddle(profile.getCorporateBankAccountNo()));
        view.put("invoiceType", profile.getInvoiceType());
        view.put("taxNo", PiiCryptoService.maskMiddle(profile.getTaxNo()));
        view.put("financeContactName", PiiCryptoService.maskName(profile.getFinanceContactName()));
        view.put("financeContactPhone", PiiCryptoService.maskPhone(profile.getFinanceContactPhone()));
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> payoutView(Long clubId) {
        ClubCorporatePayeeProfile profile = requireVerifiedForSale(clubId);
        Map<String, Object> view = head(profile);
        view.put("legalName", profile.getLegalName());
        view.put("unifiedSocialCreditCode", profile.getUnifiedSocialCreditCode());
        view.put("bankAccountName", profile.getBankAccountName());
        view.put("bankName", profile.getBankName());
        view.put("corporateBankAccountNo", profile.getCorporateBankAccountNo());
        view.put("invoiceType", profile.getInvoiceType());
        view.put("taxNo", profile.getTaxNo());
        view.put("financeContactName", profile.getFinanceContactName());
        view.put("financeContactPhone", profile.getFinanceContactPhone());
        view.put("licenseObjectKey", profile.getLicenseObjectKey());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public ClubCorporatePayeeProfile requireVerifiedForSale(Long clubId) {
        if (clubId == null) throw new IllegalArgumentException("PAYEE_ARGUMENT_REQUIRED");
        requireCryptoKey();
        ClubCorporatePayeeProfile profile = mapper.selectVerifiedCurrent(clubId);
        // 不止看 verified：current、九组齐全、账户名一致、指纹未漂移，缺一即阻断。
        if (profile == null || !Integer.valueOf(1).equals(profile.getCurrent())
                || !ClubCorporatePayeeProfile.VERIFY_VERIFIED.equals(profile.getVerifyStatus())) {
            throw new IllegalStateException("SALE_PAYEE_NOT_VERIFIED");
        }
        requireMinimalFields(profile);
        if (!profile.getLegalName().equals(profile.getBankAccountName())) {
            throw new IllegalStateException("SALE_PAYEE_FACTS_TAMPERED");
        }
        if (!factsHash(profile).equals(profile.getFactsHash())) {
            throw new IllegalStateException("SALE_PAYEE_FACTS_TAMPERED");
        }
        return profile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicSalePayeeSnapshot snapshotForSale(Long topicId, Long clubId, Date frozenAt) {
        if (topicId == null || clubId == null || frozenAt == null) {
            throw new IllegalArgumentException("PAYEE_ARGUMENT_REQUIRED");
        }
        TopicSalePayeeSnapshot existing = mapper.selectSaleSnapshot(topicId, clubId);
        // 幂等重试读回原快照，绝不覆盖：开售事实一旦冻结就不允许被后来的资料改写。
        if (existing != null) return existing;

        ClubCorporatePayeeProfile profile = requireVerifiedForSale(clubId);
        TopicSalePayeeSnapshot snapshot = new TopicSalePayeeSnapshot();
        snapshot.setTopicId(topicId);
        snapshot.setClubId(clubId);
        snapshot.setProfileId(profile.getId());
        snapshot.setProfileVersion(profile.getVersion());
        snapshot.setFactsHash(profile.getFactsHash());
        snapshot.setLicenseHash(profile.getLicenseHash());
        snapshot.setFrozenAt(frozenAt);
        if (mapper.insertSaleSnapshot(snapshot) != 1) {
            throw new IllegalStateException("SALE_PAYEE_SNAPSHOT_WRITE_FAILED");
        }
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public TopicSalePayeeSnapshot requireSnapshotForFinal(Long topicId, Long clubId) {
        if (topicId == null || clubId == null) throw new IllegalArgumentException("PAYEE_ARGUMENT_REQUIRED");
        requireCryptoKey();
        TopicSalePayeeSnapshot snapshot = mapper.selectSaleSnapshot(topicId, clubId);
        if (snapshot == null || isBlank(snapshot.getFactsHash()) || isBlank(snapshot.getLicenseHash())) {
            throw new IllegalStateException("FINAL_PAYEE_SNAPSHOT_MISSING");
        }
        // 当前 profile 只作「现在还付得出去吗」的付款能力闸；它的字段一个都不许覆盖上面的快照。
        if (mapper.selectVerifiedCurrent(clubId) == null) {
            throw new IllegalStateException("FINAL_PAYEE_NOT_PAYABLE");
        }
        return snapshot;
    }

    private Map<String, Object> head(ClubCorporatePayeeProfile profile) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", profile.getId());
        view.put("clubId", profile.getClubId());
        view.put("version", profile.getVersion());
        view.put("verifyStatus", profile.getVerifyStatus());
        return view;
    }

    private void requireCryptoKey() {
        if (cryptoService == null || !cryptoService.isEnabled()) {
            throw new IllegalStateException("PAYEE_CRYPTO_KEY_UNAVAILABLE");
        }
    }

    /** 九组最小资料逐组校验；错误必须点名缺的是哪一组，否则相邻闸会互相接住。 */
    private void requireMinimalFields(ClubCorporatePayeeProfile p) {
        require(p.getLegalName(), "legalName");
        require(p.getUnifiedSocialCreditCode(), "unifiedSocialCreditCode");
        require(p.getLicenseObjectKey(), "licenseObjectKey");
        require(p.getLicenseHash(), "licenseHash");
        require(p.getBankAccountName(), "bankAccountName");
        require(p.getBankName(), "bankName");
        require(p.getCorporateBankAccountNo(), "corporateBankAccountNo");
        require(p.getInvoiceType(), "invoiceType");
        require(p.getTaxNo(), "taxNo");
        require(p.getFinanceContactName(), "financeContactName");
        require(p.getFinanceContactPhone(), "financeContactPhone");
    }

    private void require(String value, String group) {
        if (isBlank(value)) throw new IllegalArgumentException("PAYEE_FIELD_REQUIRED:" + group);
    }

    private ClubCorporatePayeeProfile copy(ClubCorporatePayeeProfile input) {
        ClubCorporatePayeeProfile p = new ClubCorporatePayeeProfile();
        p.setLegalName(trim(input.getLegalName()));
        p.setUnifiedSocialCreditCode(trim(input.getUnifiedSocialCreditCode()));
        p.setLicenseObjectKey(trim(input.getLicenseObjectKey()));
        p.setLicenseHash(trim(input.getLicenseHash()));
        p.setBankAccountName(trim(input.getBankAccountName()));
        p.setBankName(trim(input.getBankName()));
        p.setCorporateBankAccountNo(trim(input.getCorporateBankAccountNo()));
        p.setInvoiceType(trim(input.getInvoiceType()));
        p.setTaxNo(trim(input.getTaxNo()));
        p.setFinanceContactName(trim(input.getFinanceContactName()));
        p.setFinanceContactPhone(trim(input.getFinanceContactPhone()));
        return p;
    }

    /**
     * 关键事实指纹：任一字段变化都必须换 hash，开售快照与 FINAL 靠它证明历史事实没被改写。
     * 用 US(0x1f) 分隔以免相邻字段拼接歧义（"AB"+"C" 与 "A"+"BC" 必须得到不同 hash）。
     */
    public static String factsHash(ClubCorporatePayeeProfile p) {
        String canonical = CanonicalDigest.join(
                nz(p.getLegalName()), nz(p.getUnifiedSocialCreditCode()), nz(p.getLicenseObjectKey()),
                nz(p.getLicenseHash()), nz(p.getBankAccountName()), nz(p.getBankName()),
                nz(p.getCorporateBankAccountNo()), nz(p.getInvoiceType()), nz(p.getTaxNo()),
                nz(p.getFinanceContactName()), nz(p.getFinanceContactPhone()));
        return CanonicalDigest.sha256Hex(canonical);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

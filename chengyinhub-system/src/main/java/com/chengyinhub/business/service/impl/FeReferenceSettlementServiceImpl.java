package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.FeReferenceSettlement;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.vo.FeReferenceSettlementView;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.FeReferenceSettlementMapper;
import com.chengyinhub.business.service.IFeReferenceSettlementService;
import com.chengyinhub.business.service.support.SessionPayeeResolver;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F11 自由探索店铺参考结算。见 {@link IFeReferenceSettlementService}。
 *
 * <p>本类只读写 fe_reference_settlement;不碰余额、提现、分账行或退款追回。
 */
@Service
public class FeReferenceSettlementServiceImpl implements IFeReferenceSettlementService {

    /** 原有店铺侧文案,保留不改(权益卡表单与核销记录同一句)。 */
    public static final String OFFLINE_NOTE_BASE = "票款由主办方收取；店铺如需结算，请联系主办方线下处理";

    static final int MAX_SETTLE_IDS = 200;
    static final int MAX_VOUCHER = 64;
    static final int MAX_NOTE = 200;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    @Autowired private FeReferenceSettlementMapper mapper;
    @Autowired private SessionPayeeResolver payeeResolver;
    @Autowired private CmsRegistrationMapper cmsRegistrationMapper;
    @Autowired private CmsActivityMapper cmsActivityMapper;
    @Autowired private CmsTopicMapper cmsTopicMapper;
    @Autowired private CmsTopicChapterMapper chapterMapper;
    @Autowired private CoopPerkMapper coopPerkMapper;

    // ------------------------------------------------------------------ 写:核销 / 撤销 / 退款

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordPerkRedemption(Redemption redemption, MerchantChapterOffer offer) {
        if (redemption == null || redemption.getId() == null || offer == null) {
            throw new IllegalStateException("参考结算缺少核销或供给");
        }
        // 只记权益档;计酬在场次结算时按人头记,引流不计(与原计费口径一致)。null 按 PERK(DDL 默认)。
        if (offer.getTermsMode() != null && !TermsMode.PERK.equals(offer.getTermsMode())) return;
        CmsTopic topic = redemption.getTopicId() == null ? null : cmsTopicMapper.selectCmsTopicById(redemption.getTopicId());
        if (topic == null || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) return;
        String key = "fe:perk:" + redemption.getId();
        if (mapper.selectByIdempotencyKey(key) != null) return;

        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(redemption.getRegistrationId());
        if (registration == null) {
            throw new IllegalStateException("参考结算找不到报名 registrationId=" + redemption.getRegistrationId());
        }
        CmsActivity activity = null;
        if (Long.valueOf(2L).equals(registration.getOwnerType()) && registration.getOwnerId() != null) {
            activity = cmsActivityMapper.selectCmsActivityById(registration.getOwnerId());
            if (activity == null) {
                throw new IllegalStateException("参考结算找不到场次 activityId=" + registration.getOwnerId());
            }
        }
        SessionPayeeResolver.Payer payer = activity != null
                ? payeeResolver.forSession(activity, topic) : payeeResolver.forTopicPurchase(topic);
        requirePayer(payer, activity == null ? null : activity.getId());

        FeReferenceSettlement row = new FeReferenceSettlement();
        row.setTopicId(topic.getId());
        row.setActivityId(activity == null ? null : activity.getId());
        row.setChapterId(redemption.getChapterId());
        row.setOfferId(offer.getId());
        row.setMerchantId(redemption.getMerchantId());
        row.setPayerKind(payer.getKind());
        row.setPayerId(payer.getId());
        row.setBasis(FeReferenceSettlement.BASIS_PERK_REDEEMED);
        row.setQuantity(1);
        row.setRegistrationId(redemption.getRegistrationId());
        row.setRedemptionId(redemption.getId());
        row.setIdempotencyKey(key);
        if (offer.isAuthorSupplied()) {
            // F10 作者自己承接:作者自己给自己供货,没有要结的钱。
            row.setSupplierKind(MerchantChapterOffer.SUPPLIER_AUTHOR);
            row.setUnitAmount(ZERO);
            row.setAmount(ZERO);
            row.setStatus(FeReferenceSettlement.STATUS_SETTLED_OFFLINE);
            row.setSettledAt(new Date());
            row.setSettledBy(redemption.getMerchantId());
            row.setNote(FeReferenceSettlement.NOTE_AUTHOR_SELF_SUPPLY);
        } else {
            BigDecimal unit = perkReferenceValue(offer);
            row.setSupplierKind(MerchantChapterOffer.SUPPLIER_MERCHANT);
            row.setUnitAmount(unit);
            row.setAmount(unit);
            row.setStatus(FeReferenceSettlement.STATUS_OPEN);
        }
        insert(row);
    }

    /** 权益卡的结算参考价 = 单人成本价(coop_perk.unit_cost);没有填 0(界面显示「按约定」)。 */
    private BigDecimal perkReferenceValue(MerchantChapterOffer offer) {
        if (offer.getPerkId() == null) return ZERO;
        CoopPerk perk = coopPerkMapper.selectById(offer.getPerkId());
        BigDecimal cost = perk == null ? null : perk.getUnitCost();
        if (cost == null || cost.signum() <= 0) return ZERO;
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int voidForReversedRedemption(Long redemptionId) {
        if (redemptionId == null) return 0;
        return mapper.voidByRedemption(redemptionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int voidUnredeemedOnRefund(Long registrationId) {
        if (registrationId == null) return 0;
        return mapper.voidUnredeemedByRegistration(registrationId);
    }

    // ------------------------------------------------------------------ 写:场次结算

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordRevshareHeads(CmsActivity activity, CmsTopic topic, SessionPayeeResolver.Payer payer,
                                    List<RevshareLine> lines, Long registrationId) {
        if (activity == null || activity.getId() == null || topic == null || lines == null) {
            throw new IllegalStateException("计酬参考账缺少场次或主题");
        }
        requirePayer(payer, activity.getId());
        for (RevshareLine line : lines) {
            if (line == null || line.getHeads() <= 0) continue;
            if (line.getMerchantId() == null || line.getMerchantId() <= 0 || line.getContractId() == null
                    || line.getUnitAmount() == null || line.getUnitAmount().signum() < 0
                    || line.getAmount() == null || line.getAmount().signum() < 0) {
                throw new IllegalStateException("计酬参考账条目不完整 activityId=" + activity.getId());
            }
            boolean offer = "MERCHANT_CHAPTER_OFFER".equals(line.getContractType());
            String key = "fe:rv:" + activity.getId() + ":" + (offer ? "O" : "C") + ":" + line.getContractId()
                    + ":" + line.getMerchantId() + (registrationId == null ? "" : ":r:" + registrationId);
            if (mapper.selectByIdempotencyKey(key) != null) continue;
            FeReferenceSettlement row = new FeReferenceSettlement();
            row.setTopicId(topic.getId());
            row.setActivityId(activity.getId());
            row.setChapterId(line.getChapterId());
            row.setOfferId(offer ? line.getContractId() : null);
            row.setSupplierKind(MerchantChapterOffer.SUPPLIER_MERCHANT);
            row.setMerchantId(line.getMerchantId());
            row.setPayerKind(payer.getKind());
            row.setPayerId(payer.getId());
            row.setBasis(FeReferenceSettlement.BASIS_REVSHARE_HEAD);
            row.setQuantity(line.getHeads());
            row.setUnitAmount(line.getUnitAmount().setScale(2, RoundingMode.HALF_UP));
            row.setAmount(line.getAmount().setScale(2, RoundingMode.HALF_UP));
            row.setStatus(FeReferenceSettlement.STATUS_OPEN);
            row.setIdempotencyKey(key);
            insert(row);
        }
    }

    private void insert(FeReferenceSettlement row) {
        if (mapper.insertReference(row) != 1) {
            throw new IllegalStateException("参考结算记账失败 key=" + row.getIdempotencyKey());
        }
    }

    private static void requirePayer(SessionPayeeResolver.Payer payer, Long activityId) {
        if (payer == null || payer.getId() == null || payer.getId() <= 0 || payer.getKind() == null) {
            throw new IllegalStateException("场次收款人不明，参考结算无法记账 activityId=" + activityId);
        }
    }

    // ------------------------------------------------------------------ 读

    @Override
    public FeReferenceSettlementView listForMerchant(Long merchantMemberId, String status) {
        if (merchantMemberId == null || merchantMemberId <= 0) throw new ServiceException("缺少商家身份");
        String filter = status == null || status.trim().isEmpty() ? null : status.trim();
        if (filter != null && !Set.of(FeReferenceSettlement.STATUS_OPEN, FeReferenceSettlement.STATUS_SETTLED_OFFLINE,
                FeReferenceSettlement.STATUS_DISPUTED, FeReferenceSettlement.STATUS_VOID).contains(filter)) {
            throw new ServiceException("状态筛选无效");
        }
        return view(mapper.selectForMerchant(merchantMemberId, filter));
    }

    @Override
    public FeReferenceSettlementView listForPayer(Long memberId, Long activityId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录");
        if (activityId != null) {
            CmsActivity activity = cmsActivityMapper.selectCmsActivityById(activityId);
            CmsTopic topic = activity == null || activity.getTopicId() == null
                    ? null : cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
            SessionPayeeResolver.Payer payer = activity == null ? null : payeeResolver.peek(activity, topic);
            if (payer == null || !memberId.equals(payer.getMemberId())) {
                throw new ServiceException("无该场次结算查看范围");
            }
        }
        return view(mapper.selectForPayer(memberId, activityId));
    }

    private FeReferenceSettlementView view(List<FeReferenceSettlement> rows) {
        FeReferenceSettlementView view = new FeReferenceSettlementView();
        Map<Long, FeReferenceSettlementView.Group> groups = new LinkedHashMap<>();
        Map<String, String> payerNames = new HashMap<>();
        BigDecimal open = ZERO;
        BigDecimal settled = ZERO;
        for (FeReferenceSettlement r : rows == null ? List.<FeReferenceSettlement>of() : rows) {
            if (r == null) continue;
            FeReferenceSettlementView.Group group = groups.get(r.getActivityId());
            if (group == null) {
                group = new FeReferenceSettlementView.Group();
                group.setActivityId(r.getActivityId());
                group.setActivityTitle(r.getActivityTitle());
                String payerKey = r.getPayerKind() + ":" + r.getPayerId();
                if (!payerNames.containsKey(payerKey)) {
                    payerNames.put(payerKey, payeeResolver.payerName(r.getPayerKind(), r.getPayerId()));
                }
                group.setPayerName(payerNames.get(payerKey));
                groups.put(r.getActivityId(), group);
            }
            BigDecimal amount = r.getAmount() == null ? ZERO : r.getAmount().setScale(2, RoundingMode.HALF_UP);
            if (isOpen(r.getStatus())) {
                group.setOpenAmount(group.getOpenAmount().add(amount));
                open = open.add(amount);
            } else if (FeReferenceSettlement.STATUS_SETTLED_OFFLINE.equals(r.getStatus())) {
                group.setSettledAmount(group.getSettledAmount().add(amount));
                settled = settled.add(amount);
            }
            FeReferenceSettlementView.Row row = new FeReferenceSettlementView.Row();
            row.setId(r.getId());
            row.setChapterName(r.getChapterName());
            row.setMerchantName(r.getMerchantName());
            row.setSupplierKind(r.getSupplierKind());
            row.setBasis(r.getBasis());
            row.setQuantity(r.getQuantity());
            row.setUnitAmount(r.getUnitAmount() == null ? ZERO : r.getUnitAmount().setScale(2, RoundingMode.HALF_UP));
            row.setAmount(amount);
            row.setStatus(r.getStatus());
            row.setStatusText(statusText(r));
            row.setVoucherNo(r.getVoucherNo());
            row.setSettledAt(r.getSettledAt());
            group.getRows().add(row);
        }
        view.setGroups(new ArrayList<>(groups.values()));
        view.setTotalOpenAmount(open);
        view.setTotalSettledAmount(settled);
        return view;
    }

    private static boolean isOpen(String status) {
        return FeReferenceSettlement.STATUS_OPEN.equals(status) || FeReferenceSettlement.STATUS_DISPUTED.equals(status);
    }

    static String statusText(FeReferenceSettlement r) {
        if (MerchantChapterOffer.SUPPLIER_AUTHOR.equals(r.getSupplierKind())
                && FeReferenceSettlement.STATUS_SETTLED_OFFLINE.equals(r.getStatus())) {
            return FeReferenceSettlement.NOTE_AUTHOR_SELF_SUPPLY;
        }
        String status = r.getStatus();
        if (FeReferenceSettlement.STATUS_OPEN.equals(status)) return "待线下结款";
        if (FeReferenceSettlement.STATUS_SETTLED_OFFLINE.equals(status)) return "已线下结清";
        if (FeReferenceSettlement.STATUS_DISPUTED.equals(status)) return "有异议";
        if (FeReferenceSettlement.STATUS_VOID.equals(status)) return "已作废";
        return "状态待确认";
    }

    // ------------------------------------------------------------------ 写:线下结清 / 异议

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settle(Long memberId, List<Long> ids, String voucherNo, String note) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录");
        if (ids == null || ids.isEmpty()) throw new ServiceException("请选择要标记的账目");
        if (ids.size() > MAX_SETTLE_IDS) throw new ServiceException("一次最多标记 " + MAX_SETTLE_IDS + " 条");
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0 || !unique.add(id)) throw new ServiceException("账目编号无效或重复");
        }
        String voucher = trimToNull(voucherNo);
        if (voucher != null && voucher.length() > MAX_VOUCHER) throw new ServiceException("凭证号最长 " + MAX_VOUCHER + " 字");
        String remark = trimToNull(note);
        if (remark != null && remark.length() > MAX_NOTE) throw new ServiceException("备注最长 " + MAX_NOTE + " 字");
        List<Long> sorted = new ArrayList<>(unique);
        sorted.sort(Long::compare);
        List<FeReferenceSettlement> owned = mapper.selectOwnedByPayerForUpdate(memberId, sorted);
        if (owned == null || owned.size() != sorted.size()) {
            throw new ServiceException("部分账目不存在或不归你结算");
        }
        for (FeReferenceSettlement row : owned) {
            if (!isOpen(row.getStatus())) throw new ServiceException("已结清或已作废的账目不能再标记");
        }
        int changed = 0;
        for (FeReferenceSettlement row : owned) {
            if (mapper.markSettledOffline(row.getId(), memberId, voucher, remark) != 1) {
                throw new ServiceException("账目状态已变化，请刷新后重试");
            }
            changed += 1;
        }
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispute(Long merchantMemberId, Long id, String note) {
        if (merchantMemberId == null || merchantMemberId <= 0) throw new ServiceException("缺少商家身份");
        if (id == null || id <= 0) throw new ServiceException("账目编号无效");
        String remark = trimToNull(note);
        if (remark == null) throw new ServiceException("请写明异议原因");
        if (remark.length() > MAX_NOTE) throw new ServiceException("异议原因最长 " + MAX_NOTE + " 字");
        FeReferenceSettlement row = mapper.selectOwnedByMerchantForUpdate(id, merchantMemberId);
        if (row == null) throw new ServiceException("账目不存在或不属于本店");
        if (MerchantChapterOffer.SUPPLIER_AUTHOR.equals(row.getSupplierKind())) {
            throw new ServiceException("作者自营的账目无需结算");
        }
        if (!FeReferenceSettlement.STATUS_OPEN.equals(row.getStatus())
                && !FeReferenceSettlement.STATUS_SETTLED_OFFLINE.equals(row.getStatus())) {
            throw new ServiceException("该账目当前不能提出异议");
        }
        if (mapper.markDisputed(id, merchantMemberId, remark) != 1) {
            throw new ServiceException("账目状态已变化，请刷新后重试");
        }
    }

    // ------------------------------------------------------------------ 店铺侧文案

    @Override
    public String offlineSettlementNoteForRedemption(Redemption redemption) {
        if (redemption == null) return OFFLINE_NOTE_BASE;
        CmsTopic topic = redemption.getTopicId() == null ? null : cmsTopicMapper.selectCmsTopicById(redemption.getTopicId());
        CmsRegistration registration = redemption.getRegistrationId() == null
                ? null : cmsRegistrationMapper.selectCmsRegistrationById(redemption.getRegistrationId());
        CmsActivity activity = registration != null && Long.valueOf(2L).equals(registration.getOwnerType())
                && registration.getOwnerId() != null
                ? cmsActivityMapper.selectCmsActivityById(registration.getOwnerId()) : null;
        return withOrganizer(activity == null && topic == null ? null : payeeResolver.peek(activity, topic));
    }

    @Override
    public String offlineSettlementNoteForChapter(Long chapterId) {
        if (chapterId == null || chapterId <= 0) return null;
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(chapterId);
        CmsTopic topic = chapter == null || chapter.getTopicId() == null ? null : cmsTopicMapper.selectCmsTopicById(chapter.getTopicId());
        if (topic == null || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) return null;
        return withOrganizer(payeeResolver.peek(null, topic));
    }

    private String withOrganizer(SessionPayeeResolver.Payer payer) {
        String name = payer == null ? null : payeeResolver.payerName(payer.getKind(), payer.getId());
        return name == null ? OFFLINE_NOTE_BASE : OFFLINE_NOTE_BASE + " · 主办方：" + name;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

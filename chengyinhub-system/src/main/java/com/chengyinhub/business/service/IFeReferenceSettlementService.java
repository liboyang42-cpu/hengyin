package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.vo.FeReferenceSettlementView;
import com.chengyinhub.business.service.support.SessionPayeeResolver;

import java.math.BigDecimal;
import java.util.List;

/**
 * F11 自由探索店铺参考结算(FE-G16=B 保留计酬但只作参考 / FE-G22 店铺线下结款)。
 *
 * <p>线上把每家店应分多少算完整、给店铺和场次收款人看,直到「已线下结清」;钱不经过平台划给店铺。
 * 写入都在调用方事务里(核销 / 撤销 / 退款 / 场次结算),读写都按归属校验。
 */
public interface IFeReferenceSettlementService {

    /** 计酬人头参考行的一条输入(一家店一份合同在本场 / 本单的人头)。 */
    final class RevshareLine {
        private final Long merchantId;
        private final String contractType;
        private final Long contractId;
        private final Long chapterId;
        private final int heads;
        private final BigDecimal unitAmount;
        private final BigDecimal amount;

        public RevshareLine(Long merchantId, String contractType, Long contractId, Long chapterId,
                            int heads, BigDecimal unitAmount, BigDecimal amount) {
            this.merchantId = merchantId;
            this.contractType = contractType;
            this.contractId = contractId;
            this.chapterId = chapterId;
            this.heads = heads;
            this.unitAmount = unitAmount;
            this.amount = amount;
        }

        public Long getMerchantId() { return merchantId; }
        public String getContractType() { return contractType; }
        public Long getContractId() { return contractId; }
        public Long getChapterId() { return chapterId; }
        public int getHeads() { return heads; }
        public BigDecimal getUnitAmount() { return unitAmount; }
        public BigDecimal getAmount() { return amount; }
    }

    /**
     * 权益核销成功后(同一事务)写一行 PERK_REDEEMED。只对自由探索主题的权益档供给生效;
     * AUTHOR 供给 = 金额 0、直接已线下结清、备注「作者自营」。幂等键 = 核销 id。
     */
    void recordPerkRedemption(Redemption redemption, MerchantChapterOffer offer);

    /** 撤销核销(同一事务):该核销的未结清参考行置 VOID。 */
    int voidForReversedRedemption(Long redemptionId);

    /** 整单退款(同一事务):这一单还 OPEN 且未核销的参考行置 VOID;已核销的行保持 OPEN。 */
    int voidUnredeemedOnRefund(Long registrationId);

    /**
     * 场次结算(同一事务):原来线上付给店铺的计酬改写成 REVSHARE_HEAD 参考行(人头 × 冻结单价)。
     * registrationId 非空 = 客诉结案补结的那一单。
     */
    void recordRevshareHeads(CmsActivity activity, CmsTopic topic, SessionPayeeResolver.Payer payer,
                             List<RevshareLine> lines, Long registrationId);

    /** 店铺视角(merchantMemberId 由商家访问服务解析)。status 为空 = 除作废外全部。 */
    FeReferenceSettlementView listForMerchant(Long merchantMemberId, String status);

    /** 场次收款人视角;activityId 非空时只看这一场(须是该场收款人)。 */
    FeReferenceSettlementView listForPayer(Long memberId, Long activityId);

    /** 收款人标记已线下结清;ids 必须全部归属本人且未作废。返回结清行数。 */
    int settle(Long memberId, List<Long> ids, String voucherNo, String note);

    /** 店铺对一行提出异议。 */
    void dispute(Long merchantMemberId, Long id, String note);

    /**
     * 店铺看到的线下结算说明:「票款由主办方收取；店铺如需结算，请联系主办方线下处理 · 主办方：X」。
     * 主办方 = 该核销所属场次的收款人(取不到名字时只给前半句)。
     */
    String offlineSettlementNoteForRedemption(Redemption redemption);

    /** 同上,按章节(权益卡表单用;只对自由探索主题返回,否则 null)。 */
    String offlineSettlementNoteForChapter(Long chapterId);
}

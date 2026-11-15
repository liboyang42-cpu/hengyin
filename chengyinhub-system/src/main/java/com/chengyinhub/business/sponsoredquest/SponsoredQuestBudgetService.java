package com.chengyinhub.business.sponsoredquest;

import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.sponsoredquest.mapper.SponsoredQuestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S3b sponsored quests: budget accounting and sponsor charges. Runs ONLY while the money switch area
 * {@code merchantMoney} is open (server ceiling AND stored flag; both off by default and in production). With the
 * switch closed nothing is written: no ledger row, no spent amount, and the campaign's budget stays a plan.
 *
 * <p>A charge here is an internal ledger entry against the sponsor's agreed budget. It never calls a payment provider.
 */
@Service
public class SponsoredQuestBudgetService {
    public static final String MONEY_OFF = "资金开关未开启:赞助任务只展示,不计费";
    static final Pattern REF = Pattern.compile("[A-Za-z0-9:_\\-]{4,80}");
    public static final int CHARGE_ROWS = 200;

    private final SponsoredQuestMapper mapper;
    private final MoneySwitchService moneySwitch;
    private final OpsConsoleAudit audit;

    @Autowired
    public SponsoredQuestBudgetService(SponsoredQuestMapper mapper, MoneySwitchService moneySwitch, OpsConsoleAudit audit) {
        this.mapper = mapper;
        this.moneySwitch = moneySwitch;
        this.audit = audit;
    }

    /** True only when the server-side money switch has the merchant money area open. Any error reads closed. */
    public boolean chargingOpen() {
        try {
            Map<String, Boolean> snapshot = moneySwitch.snapshot();
            return snapshot != null && Boolean.TRUE.equals(snapshot.get(MoneyArea.MERCHANT_MONEY.key()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chargingOpen", chargingOpen());
        out.put("label", SponsoredQuestLabel.ZH_HANS);
        out.put("labelEn", SponsoredQuestLabel.EN);
        return out;
    }

    /**
     * Records one charge against a campaign's budget. Idempotent on {@code refKey}: a retry returns the first result.
     *
     * @throws IllegalStateException    the money switch is closed (nothing written)
     * @throws IllegalArgumentException bad input, unknown campaign, or the charge would exceed the budget
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> charge(Long campaignId, String refKey, Long amountCent, String reason,
                                      String operator, Long sysUserId) {
        if (!chargingOpen()) throw new IllegalStateException(MONEY_OFF);
        if (campaignId == null || campaignId <= 0) throw new IllegalArgumentException("请选择赞助任务");
        String ref = refKey == null ? "" : refKey.trim();
        if (!REF.matcher(ref).matches()) throw new IllegalArgumentException("计费单号需为 4-80 位字母、数字、冒号、下划线或短横线");
        if (amountCent == null || amountCent <= 0 || amountCent > SponsoredQuestAdminService.MAX_BUDGET_CENT) {
            throw new IllegalArgumentException("计费金额无效");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 255) throw new IllegalArgumentException("请填写计费说明(最多 255 字)");
        Map<String, Object> campaign = mapper.selectForUpdate(campaignId);
        if (campaign == null) throw new IllegalArgumentException("赞助任务不存在");
        if (mapper.insertChargeIgnore(campaignId, ref, amountCent, why, operator == null ? "" : operator) == 0) {
            Long owner = mapper.selectChargeCampaign(ref);
            if (owner == null || !owner.equals(campaignId)) throw new IllegalArgumentException("计费单号已用于其他赞助任务");
            return receipt(campaignId, ref, amountCent, true);
        }
        if (mapper.addSpentWithinBudget(campaignId, amountCent) != 1) {
            throw new IllegalArgumentException("超出该赞助任务的剩余预算");
        }
        audit.record(sysUserId, "SPONSORED_QUEST_CHARGE", "SPONSORED_QUEST", campaignId,
                Map.of("spentCent", campaign.getOrDefault("spentCent", 0)),
                Map.of("refKey", ref, "amountCent", amountCent), why);
        return receipt(campaignId, ref, amountCent, false);
    }

    public List<Map<String, Object>> charges(Long campaignId) {
        if (campaignId == null || campaignId <= 0) throw new IllegalArgumentException("请选择赞助任务");
        List<Map<String, Object>> rows = mapper.selectCharges(campaignId, CHARGE_ROWS);
        return rows == null ? Collections.emptyList() : rows;
    }

    private static Map<String, Object> receipt(Long campaignId, String ref, long amount, boolean duplicate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", campaignId);
        out.put("refKey", ref);
        out.put("amountCent", amount);
        out.put("duplicate", duplicate);
        return out;
    }
}

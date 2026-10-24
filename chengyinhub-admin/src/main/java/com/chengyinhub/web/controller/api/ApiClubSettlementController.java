package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 俱乐部分润页（Figma G1/G2）的后端。
 *
 * <p><b>为什么金额是「已格式化的文本」而不是数字</b>：前端 normalizeSummary 对每个字段
 * 做严格校验，任一字段缺失就整块拒收、显示错误态，<b>绝不把缺失渲染成 0</b>。
 * 钱的显示口径（几位小数、符号、千分位）只能有一处真源，放在后端，前端不做二次拼装。
 *
 * <p><b>提现不在这里实现</b>：转发到已有的 {@link ICashWithdrawService}（与 ApiWalletController
 * 同一条链路，带 withdrawal.fast-withdraw.enabled 总开关）。动钱的路径只该有一条 ——
 * 再造一条就是两套账，对不上时谁也说不清哪边是真的。
 */
@RestController
@RequestMapping("/api/club/settlement")
public class ApiClubSettlementController extends BaseController {

    /** coop_settlement.beneficiary_role 里俱乐部这一档的取值（与 MERCHANT 同表不同角色）。 */
    private static final String ROLE_CLUB = "CLUB";

    /** coop_settlement.status：0 待打款、1 已打款、2 作废；已入账金额另核唯一流水。 */
    private static final int STATUS_SETTLED = 1;

    private final CoopSettlementMapper coopSettlementMapper;
    private final IClubService clubService;
    private final IClubAccessService clubAccessService;
    private final ICashWithdrawService cashWithdrawService;

    @Value("${withdrawal.fast-withdraw.enabled:false}")
    private boolean fastWithdrawEnabled;

    public ApiClubSettlementController(CoopSettlementMapper coopSettlementMapper,
                                       IClubService clubService,
                                       IClubAccessService clubAccessService,
                                       ICashWithdrawService cashWithdrawService) {
        this.coopSettlementMapper = coopSettlementMapper;
        this.clubService = clubService;
        this.clubAccessService = clubAccessService;
        this.cashWithdrawService = cashWithdrawService;
    }

    @PostMapping("/summary")
    public AjaxResult summary(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long clubId = body == null ? null : Convert.toLong(body.get("clubId"));
        if (clubId == null || clubId <= 0L) return error("缺少俱乐部信息");
        if (!belongsToClub(clubId, memberId)) return error("仅主理人与联席主理人可查看分润");

        List<ClubSettlementSummaryRow> rows = coopSettlementMapper.selectSummaryByBeneficiary(ROLE_CLUB, clubId);
        if (rows == null) return error("结算数据暂不可用");
        BigDecimal settled = BigDecimal.ZERO;
        int unverified = 0;
        List<Map<String, Object>> topics = new ArrayList<>();
        for (ClubSettlementSummaryRow row : rows) {
            if (row == null || row.getId() == null || row.getAmount() == null
                    || row.getExecutedAdjustmentAmount() == null || row.getStatus() == null
                    || row.getStatus() < 0 || row.getStatus() > 2) return error("结算金额或状态缺失，待核验");
            BigDecimal original = row.getAmount();
            BigDecimal adjustment = row.getExecutedAdjustmentAmount();
            BigDecimal net = original.add(adjustment);
            boolean isSettled = row.getStatus() == STATUS_SETTLED;
            boolean isVoid = row.getStatus() == 2;
            BigDecimal paid = row.getActualPaidAmount();
            boolean amountVerified = !isSettled || (paid != null && paid.signum() >= 0);
            if (isSettled) {
                if (amountVerified) settled = settled.add(paid);
                else unverified++;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", row.getId());
            // 明细页(/pages/coop/settlement-detail?source=finance)按主题 id 匹配 finance 行,
            // 不是按本结算行 id —— 只回 id 会让「俱乐部分润 → 结算详情」永远落 missing-record。
            t.put("topicId", row.getTopicId());
            t.put("name", topicName(row));
            t.put("originalAmountText", money(original));
            t.put("executedAdjustmentText", money(adjustment));
            t.put("netAmountText", money(net));
            t.put("amountText", amountVerified ? money(isSettled ? paid : net) : null);
            t.put("amountStatus", amountVerified ? "verified" : "unverified");
            t.put("arrivedText", isVoid ? "结算已作废" : isSettled
                    ? (amountVerified ? arrived(row) : "入账记录待核验") : expected(row));
            t.put("paidText", isVoid ? "已作废，不再打款" : isSettled
                    ? (amountVerified ? "已入账" : "入账金额未核验") : "待入账");
            t.put("status", isVoid ? "void" : isSettled ? "settled" : "pending");
            topics.add(t);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settledAmountText", unverified == 0 ? money(settled) : null);
        data.put("settledAmountStatus", unverified == 0 ? "verified" : "unverified");
        data.put("unverifiedSettledCount", unverified);
        // pendingAdjustment 恒 null 占位已删(拍板9 前端调整卡已下线,无读方)。
        data.put("topics", topics);
        // 2026-09-17 总控裁定:余额三段只给俱乐部主理人本人看,联席主理人不显示。
        Club club = clubService.selectClubById(clubId);
        data.put("isOwner", club != null && memberId.equals(club.getMemberId()));
        return AjaxResult.success(data);
    }

    @PostMapping("/withdraw")
    public AjaxResult withdraw(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long clubId = body == null ? null : Convert.toLong(body.get("clubId"));
        if (clubId == null || clubId <= 0L) return error("缺少俱乐部信息");
        // 提现是取钱，只有主理人本人可以发起——管理员能看账，不能取钱。
        Club club = clubService.selectClubById(clubId);
        if (club == null || !memberId.equals(club.getMemberId())) {
            return error("仅俱乐部主理人可发起提现");
        }
        if (!fastWithdrawEnabled) {
            return error("提现通道暂未开放，请联系平台安排打款");
        }

        // 与 /summary 同一取数：已打款行按核验过的实付金额累加；任何一行缺数据或实付未核验，
        // 整笔拒绝（fail closed）——不能让页面显示「待核验」的同时按原始应结金额把钱提走。
        List<ClubSettlementSummaryRow> rows = coopSettlementMapper.selectSummaryByBeneficiary(ROLE_CLUB, clubId);
        if (rows == null) return error("结算数据暂不可用");
        BigDecimal settled = verifiedSettledTotal(rows);
        if (settled == null) return error("存在入账金额未核验的结算，暂不能提现，请联系平台核对");
        if (settled.compareTo(BigDecimal.ZERO) <= 0) return error("暂无可提现的已结算分润");

        try {
            // 走既有的会员提现链路：它自己带幂等、状态机与对账，这里不重复实现一套。
            return AjaxResult.success("提现申请已受理", cashWithdrawService.withdraw(memberId, settled));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 已打款行的核验实付合计；有行缺字段/状态越界或实付未核验时返回 null（调用方 fail closed）。
     * 与 {@link #summary} 的 settledAmountText 同一口径。
     */
    static BigDecimal verifiedSettledTotal(List<ClubSettlementSummaryRow> rows) {
        BigDecimal settled = BigDecimal.ZERO;
        for (ClubSettlementSummaryRow row : rows) {
            if (row == null || row.getId() == null || row.getAmount() == null
                    || row.getExecutedAdjustmentAmount() == null || row.getStatus() == null
                    || row.getStatus() < 0 || row.getStatus() > 2) return null;
            if (row.getStatus() != STATUS_SETTLED) continue;
            BigDecimal paid = row.getActualPaidAmount();
            if (paid == null || paid.signum() < 0) return null;
            settled = settled.add(paid);
        }
        return settled;
    }

    /**
     * 5-08：与页面闸 need="club:finance:read" 同一口径 —— 主理人(CLUB_OWNER,策略表全权限)
     * 与联席主理人(CLUB_CO_OWNER)。不再认 club_member.role=1 的旧管理员:策略表里它没有
     * FINANCE_READ,旧口径会让前端进不来的人从后端白看分润。
     */
    private boolean belongsToClub(Long clubId, Long memberId) {
        if (clubId == null || memberId == null) return false;
        try {
            ClubAccessContext access = clubAccessService.resolve(memberId, clubId, null);
            return access != null && memberId.equals(access.getPrincipalMemberId())
                    && clubId.equals(access.getClubId())
                    && access.hasPermission(ClubPermission.FINANCE_READ);
        } catch (ServiceException e) {
            return false;
        }
    }

    /** 钱的显示口径只有这一处真源，前端不做二次拼装。 */
    private static String money(BigDecimal v) {
        if (v == null) throw new IllegalArgumentException("金额未核验");
        BigDecimal x = v;
        return "¥ " + x.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
    }

    private static String topicName(ClubSettlementSummaryRow row) {
        // CU-C-40:名字由 summarySelectFrom 左连 cms_topic 一次带出(不再每条再查一次);
        // 主题被删或名字为空时才退回可辨认的编号 —— 空串会让前端整块拒收,本来能看的几行也跟着没了。
        String name = row.getTopicName();
        if (name != null && !name.trim().isEmpty()) return name;
        return row.getTopicId() == null ? "未关联主题" : ("主题 #" + row.getTopicId());
    }

    private static String arrived(ClubSettlementSummaryRow row) {
        Date date = row.getActualPayoutTime();
        return date == null ? "入账时间待确认" : day(date) + "入账";
    }

    private static String expected(CoopSettlement row) {
        Date d = row.getPayableTime();
        return d == null ? "打款时间待确认" : ("预计 " + day(d));
    }

    private static String day(Date d) {
        return new SimpleDateFormat("M月d日").format(d);
    }
}

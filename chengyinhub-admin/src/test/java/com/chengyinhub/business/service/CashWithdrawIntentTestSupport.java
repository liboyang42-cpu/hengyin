package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.mapper.CashWithdrawTransferIntentMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** In-memory evidence for existing focused tests only; real atomicity is tested by CashWithdrawTransferIntentMySqlIT. */
public final class CashWithdrawIntentTestSupport {
    public final CashWithdrawTransferIntentMapper mapper = mock(CashWithdrawTransferIntentMapper.class);
    private final Map<Long, CashWithdrawTransferIntent> rows = new HashMap<>();

    public CashWithdrawIntentTestSupport() {
        when(mapper.selectByWithdrawId(anyLong())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(mapper.insert(any())).thenAnswer(call -> {
            CashWithdrawTransferIntent intent = call.getArgument(0);
            if (rows.putIfAbsent(intent.withdrawId(), intent) != null) return 0;
            return 1;
        });
        when(mapper.markSubmitted(anyLong(), anyString())).thenAnswer(call -> {
            CashWithdrawTransferIntent intent = rows.get(call.getArgument(0));
            if (intent == null || intent.submitted() || !intent.requestHash().equals(call.getArgument(1))) return 0;
            rows.put(intent.withdrawId(), flags(intent, true, false));
            return 1;
        });
        when(mapper.markLocallyRejected(anyLong(), anyString())).thenAnswer(call -> {
            CashWithdrawTransferIntent intent = rows.get(call.getArgument(0));
            if (intent == null || !intent.submitted() || intent.locallyRejected() || !intent.requestHash().equals(call.getArgument(1))) return 0;
            rows.put(intent.withdrawId(), flags(intent, true, true));
            return 1;
        });
    }

    public void forget(Long id) { rows.remove(id); }
    public CashWithdrawTransferIntent get(Long id) { return rows.get(id); }

    public CashWithdrawTransferIntent seed(MemberCashWithdraw row, boolean submitted) {
        CashWithdrawTransferIntent intent = CashWithdrawTransferIntent.create(row, "merchant", "wx-mp-appid",
                "城瘾分润提现", new BigDecimal("100.00"), new BigDecimal("100.00").subtract(row.getAmount()));
        intent = flags(intent, submitted, false);
        rows.put(row.getId(), intent);
        return intent;
    }

    public static CashWithdrawTransferIntent flags(CashWithdrawTransferIntent intent, boolean submitted, boolean rejected) {
        return new CashWithdrawTransferIntent(intent.withdrawId(), intent.memberId(), intent.mchid(), intent.appid(),
                intent.outBatchNo(), intent.outDetailNo(), intent.amountFen(), intent.openid(), intent.remark(),
                intent.requestVersion(), intent.requestHash(), intent.balanceBefore(), intent.balanceAfter(), submitted, rejected);
    }

    public static Map<String, String> facts(CashWithdrawTransferIntent intent, String state) {
        Map<String, String> facts = new HashMap<>();
        facts.put("mchid", intent.mchid()); facts.put("appid", intent.appid());
        facts.put("outBatchNo", intent.outBatchNo()); facts.put("outDetailNo", intent.outDetailNo());
        facts.put("openid", intent.openid()); facts.put("transferAmount", Long.toString(intent.amountFen()));
        facts.put("batchId", "original-batch"); facts.put("detailId", "original-detail");
        facts.put("batchStatus", "FINISHED"); facts.put("detailStatus", state); facts.put("result", state);
        return facts;
    }
}

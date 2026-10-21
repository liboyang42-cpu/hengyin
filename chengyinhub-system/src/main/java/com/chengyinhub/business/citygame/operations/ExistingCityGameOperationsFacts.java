package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.business.cityboard.CityBoardReleaseRow;
import com.chengyinhub.business.cityboard.mapper.CityBoardMapper;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读复用已发布规则、商家、积分明细和漫游跑步会话。
 * 不调用积分发放，不写商家，不编新鲜度窗口。
 */
@Component
public class ExistingCityGameOperationsFacts implements CityGameOperationsFactPort {

    private final CityBoardMapper rules;
    private final IMmsMerchantService merchants;
    private final IUmsMemberPointsDetailService points;
    private final RoamMapper runners;
    private final CityGameOperationsExecutionPolicy policy;

    public ExistingCityGameOperationsFacts(CityBoardMapper rules, IMmsMerchantService merchants,
            IUmsMemberPointsDetailService points, RoamMapper runners, CityGameOperationsExecutionPolicy policy) {
        this.rules = rules;
        this.merchants = merchants;
        this.points = points;
        this.runners = runners;
        this.policy = policy;
    }

    @Override
    public Slot read(String slot, String actionId, String regionId) {
        try {
            return switch (slot) {
                case "rule" -> rule(regionId);
                case "placeFacts" -> place(actionId);
                case "ledger" -> ledger(actionId);
                case "timeline" -> runner(actionId);
                case "evidence" -> Slot.missing("NO_EVIDENCE_SOURCE");
                case "task" -> Slot.missing("NO_TASK_SOURCE");
                case "dice" -> Slot.missing("NO_DICE_SOURCE");
                case "quote" -> Slot.missing("NO_QUOTE_SOURCE");
                case "ownership" -> Slot.missing("NO_OWNERSHIP_SOURCE");
                default -> Slot.missing("MISSING");
            };
        } catch (RuntimeException ex) {
            return Slot.missing("SOURCE_READ_FAILED");
        }
    }

    private Slot rule(String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return Slot.missing("NO_RULE_SOURCE");
        }
        CityBoardReleaseRow release = rules.selectCurrentRelease(regionId);
        if (release == null) {
            return Slot.missing("NO_CURRENT_RULE");
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("rulesReleaseId", release.getRulesReleaseId());
        value.put("rulesHash", release.getContentHash());
        value.put("lifecycle", release.getLifecycle());
        value.put("title", release.getTitle());
        return Slot.present(value);
    }

    private Slot place(String actionId) {
        Long id = numeric(actionId);
        if (id == null) {
            return Slot.missing("NO_MERCHANT_KEY");
        }
        MmsMerchant merchant = merchants.selectMmsMerchantById(id);
        if (merchant == null || merchant.getId() == null) {
            return Slot.missing("MERCHANT_NOT_FOUND");
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("merchantId", Long.toString(merchant.getId()));
        if (merchant.getName() != null && !merchant.getName().isBlank()) {
            value.put("label", merchant.getName());
        }
        return Slot.present(value);
    }

    private Slot ledger(String actionId) {
        Long memberId = numeric(actionId);
        if (memberId == null) {
            return Slot.missing("NO_LEDGER_KEY");
        }
        UmsMemberPointsDetail query = new UmsMemberPointsDetail();
        query.setMemberId(memberId);
        List<UmsMemberPointsDetail> rows = points.selectUmsMemberPointsDetailList(query);
        if (rows == null) {
            return Slot.missing("LEDGER_READ_FAILED");
        }
        if (rows.isEmpty()) {
            return Slot.missing("NO_LEDGER_ROWS");
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("memberId", memberId);
        value.put("detailRows", (long) rows.size());
        return Slot.present(value);
    }

    private Slot runner(String actionId) {
        Long window = policy.runnerFreshMillis();
        if (window == null || window <= 0) {
            return Slot.missing("RUNNER_WINDOW_UNCONFIGURED");
        }
        Long memberId = numeric(actionId);
        if (memberId == null) {
            return Slot.missing("NO_RUNNER_KEY");
        }
        RoamSession session = runners.selectActivePresence(memberId, new Date(System.currentTimeMillis() - window));
        if (session == null || session.getId() == null) {
            return Slot.missing("NO_ACTIVE_RUN");
        }
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventId", "run-" + session.getId());
        event.put("kind", "presence");
        if (session.getLastReportAt() != null) {
            event.put("at", session.getLastReportAt().toInstant().toString());
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("events", List.of(event));
        return Slot.present(value);
    }

    private static Long numeric(String actionId) {
        if (actionId == null || !actionId.matches("\\A[1-9][0-9]{0,17}\\z")) {
            return null;
        }
        try {
            return Long.valueOf(actionId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

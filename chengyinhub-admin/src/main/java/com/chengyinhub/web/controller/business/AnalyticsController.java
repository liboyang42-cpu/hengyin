package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.BizFunnelDaily;
import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * P3 运营分析后台入口。
 */
@RestController
@RequestMapping("/business/analytics")
public class AnalyticsController extends BaseController {

    /** owner 2026-10-23 D3(P0-48):漏斗查询窗口上限(含首尾,天)与缺省窗口。 */
    static final int FUNNEL_MAX_WINDOW_DAYS = 90;
    static final int FUNNEL_DEFAULT_WINDOW_DAYS = 30;

    @Autowired
    private BizFunnelDailyMapper bizFunnelDailyMapper;

    /** 「今天」按运营所在时区取;单测替换为固定时钟。 */
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    @PreAuthorize("@ss.hasPermi('business:analytics:list')")
    @GetMapping("/funnel")
    public AjaxResult funnel(String startDate, String endDate) {
        final LocalDate start;
        final LocalDate end;
        try {
            start = StringUtils.isNotBlank(startDate) ? LocalDate.parse(startDate) : null;
            end = StringUtils.isNotBlank(endDate) ? LocalDate.parse(endDate) : null;
        } catch (DateTimeParseException e) {
            return error("日期格式必须为 yyyy-MM-dd");
        }
        // LocalDate 还接受零年和扩展年份，这里保留正四位年份的日期合同。
        if ((start != null && (startDate.length() != 10 || start.getYear() < 1))
                || (end != null && (endDate.length() != 10 || end.getYear() < 1))) {
            return error("日期格式必须为 yyyy-MM-dd");
        }
        if (start != null && end != null && start.isAfter(end)) {
            return error("开始日期不能晚于结束日期");
        }
        // D3:窗口必须有界。都不传 = 最近 30 天(含今天);只传一端 = 从该端起算 30 天。
        LocalDate effectiveStart;
        LocalDate effectiveEnd;
        if (start == null && end == null) {
            effectiveEnd = LocalDate.now(clock);
            effectiveStart = effectiveEnd.minusDays(FUNNEL_DEFAULT_WINDOW_DAYS - 1);
        } else if (start == null) {
            effectiveEnd = end;
            effectiveStart = end.minusDays(FUNNEL_DEFAULT_WINDOW_DAYS - 1);
        } else if (end == null) {
            effectiveStart = start;
            effectiveEnd = start.plusDays(FUNNEL_DEFAULT_WINDOW_DAYS - 1);
        } else {
            effectiveStart = start;
            effectiveEnd = end;
        }
        if (ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1 > FUNNEL_MAX_WINDOW_DAYS) {
            return error("查询窗口不能超过" + FUNNEL_MAX_WINDOW_DAYS + "天");
        }
        if (effectiveStart.getYear() < 1 || effectiveEnd.getYear() > 9999) {
            return error("日期超出可查询范围");
        }
        BizFunnelDaily query = new BizFunnelDaily();
        query.getParams().put("beginStatDate", effectiveStart.toString());
        query.getParams().put("endStatDate", effectiveEnd.toString());
        List<BizFunnelDaily> rows = bizFunnelDailyMapper.selectBizFunnelDailyList(query);
        Map<String, Object> data = new HashMap<>();
        data.put("startDate", effectiveStart.toString());
        data.put("endDate", effectiveEnd.toString());
        data.put("rows", rows);
        return success(data);
    }

    @PreAuthorize("@ss.hasPermi('business:analytics:list')")
    @GetMapping("/community-qualified-pilot")
    public AjaxResult communityQualifiedPilot(String startDate, String endDate) {
        if (StringUtils.isBlank(startDate) || StringUtils.isBlank(endDate)) {
            return error("开始和结束日期不能为空");
        }
        final LocalDate start;
        final LocalDate end;
        try {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } catch (DateTimeParseException e) {
            return error("日期格式必须为 yyyy-MM-dd");
        }
        if (startDate.length() != 10 || endDate.length() != 10
                || start.getYear() < 1 || end.getYear() < 1) {
            return error("日期格式必须为 yyyy-MM-dd");
        }
        long inclusiveDays = ChronoUnit.DAYS.between(start, end) + 1;
        if (inclusiveDays < 1 || inclusiveDays > 62) {
            return error("查询窗口必须在1至62天内");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("startDate", startDate);
        data.put("endDate", endDate);
        data.put("deduplication", "DISTINCT_USER_POST_ACROSS_WINDOW");
        data.put("authorCoverageSemantics", "CURRENT_NON_ERASED_POST_AUTHOR");
        data.put("metric",
                bizFunnelDailyMapper.selectCommunityQualifiedPilotMetric(startDate, endDate));
        return success(data);
    }
}

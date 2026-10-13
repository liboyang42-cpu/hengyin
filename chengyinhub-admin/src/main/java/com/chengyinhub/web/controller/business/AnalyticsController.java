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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * P3 运营分析后台入口。
 */
@RestController
@RequestMapping("/business/analytics")
public class AnalyticsController extends BaseController {

    @Autowired
    private BizFunnelDailyMapper bizFunnelDailyMapper;

    @PreAuthorize("@ss.hasPermi('business:analytics:list')")
    @GetMapping("/funnel")
    public AjaxResult funnel(String startDate, String endDate) {
        BizFunnelDaily query = new BizFunnelDaily();
        if (StringUtils.isNotBlank(startDate)) {
            query.getParams().put("beginStatDate", startDate);
        }
        if (StringUtils.isNotBlank(endDate)) {
            query.getParams().put("endStatDate", endDate);
        }
        List<BizFunnelDaily> rows = bizFunnelDailyMapper.selectBizFunnelDailyList(query);
        Map<String, Object> data = new HashMap<>();
        data.put("startDate", startDate);
        data.put("endDate", endDate);
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

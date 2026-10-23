package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** 无第三方依赖的受控排期物化器；CUSTOM_RRULE 留给获批后的 adapter。 */
@Component
public class ClubEventScheduleMaterializer {
    static final int MAX_OCCURRENCES = 64;
    static final int MAX_FUTURE_DAYS = 90;

    public List<LocalDate> materialize(ClubEventSeriesRequest request, LocalDate todayShanghai) {
        if (request == null || todayShanghai == null) throw badRequest("排期参数不完整");
        String type = normalizeType(request.getRecurrenceType());
        if ("CUSTOM_RRULE".equals(type)) {
            throw unavailable("CUSTOM_RRULE_UNAVAILABLE: RRULE adapter 尚未启用");
        }
        LocalDate horizon = todayShanghai.plusDays(MAX_FUTURE_DAYS);
        TreeSet<LocalDate> dates = new TreeSet<>();
        if ("ONCE".equals(type) || "CUSTOM_DATES".equals(type)) {
            dates.addAll(normalizeFixedDatesForReplay(request));
        } else if ("WEEKLY".equals(type)) {
            LocalDate cursor = parseDate(request.getStartDate());
            int requested = request.getOccurrenceCount() == null ? 1 : request.getOccurrenceCount();
            if (requested <= 0 || requested > MAX_OCCURRENCES) {
                throw badRequest("每周场次数必须为 1 至 64");
            }
            // 超出 90 天窗口的期数直接拒绝并说明上限，不静默少排（请求 64 期只落 13 期）。
            if (!cursor.isBefore(todayShanghai) && !cursor.isAfter(horizon)) {
                long maxWeekly = ChronoUnit.WEEKS.between(cursor, horizon) + 1;
                if (requested > maxWeekly) {
                    throw badRequest("首次日期起未来 90 天内最多可排 " + maxWeekly + " 期，请减少场次数");
                }
            }
            for (int i = 0; i < requested; i++, cursor = cursor.plusWeeks(1)) {
                dates.add(cursor);
            }
        } else {
            throw badRequest("排期类型仅支持 ONCE/WEEKLY/CUSTOM_DATES");
        }
        if (dates.isEmpty() || dates.size() > MAX_OCCURRENCES) throw badRequest("场次数必须为 1 至 64");
        for (LocalDate date : dates) {
            if (date.isBefore(todayShanghai)) throw badRequest("场次日期不能早于今天");
            if (date.isAfter(horizon)) throw badRequest("场次日期不能超过未来 90 天");
        }
        return new ArrayList<>(dates);
    }

    /**
     * 固定日期的规范内容不依赖重发时的今天；归一结果不能替代新建场次的日期窗口校验。
     * 已有回执路径仅用它比较完整指纹，不允许据此新建场次。
     * WEEKLY 原本按当时90天窗口裁剪，不能在这里猜测原集合。
     */
    public List<LocalDate> normalizeFixedDatesForReplay(ClubEventSeriesRequest request) {
        if (request == null) throw badRequest("排期参数不完整");
        String type = normalizeType(request.getRecurrenceType());
        TreeSet<LocalDate> dates = new TreeSet<>();
        if ("ONCE".equals(type)) {
            dates.add(parseDate(request.getStartDate()));
        } else if ("CUSTOM_DATES".equals(type)) {
            if (request.getCustomDates() == null || request.getCustomDates().isEmpty()) {
                throw badRequest("请选择自定义日期");
            }
            for (String value : request.getCustomDates()) dates.add(parseDate(value));
        } else {
            throw badRequest("仅固定日期系列支持无时钟回执归一");
        }
        if (dates.isEmpty() || dates.size() > MAX_OCCURRENCES) throw badRequest("场次数必须为 1 至 64");
        return new ArrayList<>(dates);
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value == null ? "" : value.trim());
        } catch (DateTimeException invalid) {
            throw badRequest("日期格式必须为 yyyy-MM-dd");
        }
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    private ServiceException unavailable(String message) {
        return new ServiceException(message, HttpStatus.NOT_IMPLEMENTED);
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.MerchantVerifyMetric;
import com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper;
import com.chengyinhub.business.service.IMerchantMetricsService;
import com.chengyinhub.business.service.support.MerchantMetricPeriod;
import com.chengyinhub.common.utils.SafeRatio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * D-93:营销首页与店铺参谋共用的指标服务。
 *
 * <p>核销口径取营销首页原定义(与「核销与退款」台账同源):本商家 ACTIVE 的 redemption 行
 * + 本商家自有活动的验票(cms_registration.verification_status=1),按事件时间落窗,逐条计数。
 * 店铺参谋原先另按权益事实取 min(redemption.create_time),漏掉活动验票、主题报名的核销与
 * 下架供给的历史核销,时区也跟随 JVM —— 该口径已删除。
 */
@Service
public class MerchantMetricsServiceImpl implements IMerchantMetricsService {

    private static final int[] SUPPORTED = { 7, 30, 90 };

    private final MerchantMarketingAnalyticsMapper analyticsMapper;
    private final Clock clock;

    @Autowired
    public MerchantMetricsServiceImpl(MerchantMarketingAnalyticsMapper analyticsMapper) {
        this(analyticsMapper, Clock.system(MerchantMetricPeriod.ZONE));
    }

    MerchantMetricsServiceImpl(MerchantMarketingAnalyticsMapper analyticsMapper, Clock clock) {
        this.analyticsMapper = analyticsMapper;
        this.clock = clock;
    }

    @Override
    public MerchantMetricPeriod window(int windowDays) {
        return windows(windowDays).get(0);
    }

    @Override
    public List<MerchantMetricPeriod> windows(int... windowDays) {
        Clock at = Clock.fixed(clock.instant(), MerchantMetricPeriod.ZONE);
        List<MerchantMetricPeriod> out = new ArrayList<>();
        for (int days : windowDays) {
            if (!supported(days)) throw new IllegalArgumentException("windowDays仅支持7、30或90");
            out.add(MerchantMetricPeriod.resolve(days, at));
        }
        return out;
    }

    @Override
    public List<MerchantVerifyMetric> verify(Long ownerMemberId, List<MerchantMetricPeriod> windows) {
        if (ownerMemberId == null || ownerMemberId < 1) throw new IllegalArgumentException("ownerMemberId缺失");
        if (windows == null || windows.isEmpty()) return Collections.emptyList();
        Date since = windows.get(0).getPreviousStart();
        Date endExclusive = windows.get(0).getEndExclusive();
        for (MerchantMetricPeriod w : windows) {
            if (w.getPreviousStart().before(since)) since = w.getPreviousStart();
            if (w.getEndExclusive().after(endExclusive)) endExclusive = w.getEndExclusive();
        }
        List<Date> events = analyticsMapper.selectVerifyEventTimes(ownerMemberId, since, endExclusive);
        List<MerchantVerifyMetric> out = new ArrayList<>(windows.size());
        for (MerchantMetricPeriod w : windows) {
            long current = 0L;
            long previous = 0L;
            long[] daily = new long[w.getDays()];
            if (events != null) {
                for (Date at : events) {
                    int index = w.currentDayIndex(at);
                    if (index >= 0) {
                        current++;
                        daily[index]++;
                    } else if (w.inPrevious(at)) {
                        previous++;
                    }
                }
            }
            out.add(new MerchantVerifyMetric(w.getDays(), current, previous,
                    SafeRatio.change(current, previous, 4), daily));
        }
        return out;
    }

    private static boolean supported(int days) {
        for (int s : SUPPORTED) if (s == days) return true;
        return false;
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.TopicOnsiteTicketFact;
import com.chengyinhub.business.domain.dto.TopicRepeatPaymentFact;
import com.chengyinhub.business.domain.dto.TopicWaitFact;
import com.chengyinhub.business.domain.vo.TopicChapterWaitVO;
import com.chengyinhub.business.domain.vo.TopicOnsiteMetricsVO;
import com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper;
import com.chengyinhub.business.service.ITopicOnsiteMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

@Service
public class TopicOnsiteMetricsServiceImpl implements ITopicOnsiteMetricsService {
    private static final long DAY_MILLIS = 86_400_000L;
    @Autowired
    private TopicOnsiteMetricsMapper mapper;
    private Clock clock = Clock.systemDefaultZone();

    @Override
    public TopicOnsiteMetricsVO get(Long topicId) {
        TopicOnsiteMetricsVO result = new TopicOnsiteMetricsVO();
        List<TopicOnsiteTicketFact> all = safe(mapper.selectTicketFacts(topicId));
        Map<Long, List<Date>> repeatByMember = repeatByMember(safe(mapper.selectRepeatPayments(topicId)));
        Date now = Date.from(clock.instant());

        for (TopicOnsiteTicketFact fact : all) {
            if (!inServiceDenominator(fact, now)) continue;
            result.setNService(result.getNService() + 1);
            if (fact.getRefundTime() != null) result.setRefunded(result.getRefunded() + 1);

            boolean arrived = fact.isArrived() || fact.getRedeemedCount() > 0;
            if (arrived) result.setArrived(result.getArrived() + 1);
            if (fact.getRedeemedCount() >= 1) result.setS1(result.getS1() + 1);
            int required = Math.max(1, fact.getRequiredCount());
            if (fact.getRedeemedCount() >= Math.max(1, required - 1)) result.setS3(result.getS3() + 1);
            boolean completed = fact.getRedeemedCount() >= required;
            if (completed) result.setCompleted(result.getCompleted() + 1);
            if (arrived && fact.isJoinedWithinSevenDays()) result.setJoined(result.getJoined() + 1);

            if (arrived) {
                Date anchor = completed ? fact.getLastRedemptionAt() : fact.getLastSessionEnd();
                if (anchor == null || now.before(new Date(anchor.getTime() + 90L * DAY_MILLIS))) {
                    result.setR90Observing(result.getR90Observing() + 1);
                } else {
                    result.setR90Eligible(result.getR90Eligible() + 1);
                    if (hasRepeatWithin90Days(repeatByMember.get(fact.getMemberId()), anchor)) {
                        result.setR90Completed(result.getR90Completed() + 1);
                    }
                }
            }
        }

        int denominator = result.getNService();
        result.setArrivalRate(rate(result.getArrived(), denominator));
        result.setCompletionRate(rate(result.getCompleted(), result.getArrived()));
        result.setRefundRate(rate(result.getRefunded(), denominator));
        result.setComplaintUsers(mapper.countEffectiveComplaintUsers(topicId));
        result.setComplaintUserRate(rate(result.getComplaintUsers(), result.getArrived()));
        computeWaitGate(result, safe(mapper.selectWaitFacts(topicId)));
        computeG2(result);
        return result;
    }

    @Override
    public List<TopicChapterWaitVO> chapterWaits(Long topicId) {
        TopicOnsiteMetricsVO waitOnly = new TopicOnsiteMetricsVO();
        computeWaitGate(waitOnly, safe(mapper.selectWaitFacts(topicId)));
        return waitOnly.getChapterWaits();
    }

    private boolean inServiceDenominator(TopicOnsiteTicketFact fact, Date now) {
        boolean started = fact.getTicketStart() != null && !now.before(fact.getTicketStart());
        if (!started) return false;
        boolean paid = Integer.valueOf(2).equals(fact.getPaymentStatus())
                && Integer.valueOf(2).equals(fact.getRegistrationStatus());
        boolean refundRequestedAfterStart = fact.getRefundRequestedAt() != null
                && !fact.getRefundRequestedAt().before(fact.getTicketStart());
        return paid || refundRequestedAfterStart;
    }

    private void computeWaitGate(TopicOnsiteMetricsVO result, List<TopicWaitFact> facts) {
        int population = facts.size();
        int valid = 0;
        int negative = 0;
        Map<Long, List<Double>> validByChapter = new LinkedHashMap<>();
        Map<Long, Integer> populationByChapter = new LinkedHashMap<>();
        for (TopicWaitFact fact : facts) {
            populationByChapter.merge(fact.getChapterId(), 1, Integer::sum);
            if (fact.getQueueJoinedAt() == null || fact.getServiceStartedAt() == null) continue;
            double minutes = (fact.getServiceStartedAt().getTime() - fact.getQueueJoinedAt().getTime()) / 60_000d;
            if (minutes < 0) {
                negative++;
                continue;
            }
            valid++;
            validByChapter.computeIfAbsent(fact.getChapterId(), ignored -> new ArrayList<>()).add(minutes);
        }
        result.setWaitCoverage(population == 0 ? 0d : valid / (double) population);
        result.setNegativeWaitSamples(negative);
        result.setNegativeWaitWarning(population > 0 && negative / (double) population > 0.05d);

        boolean passed = population > 0 && result.getWaitCoverage() >= 0.95d;
        List<TopicChapterWaitVO> chapterViews = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : populationByChapter.entrySet()) {
            List<Double> samples = validByChapter.getOrDefault(entry.getKey(), Collections.emptyList());
            Collections.sort(samples);
            TopicChapterWaitVO view = new TopicChapterWaitVO();
            view.setChapterId(entry.getKey());
            view.setPopulation(entry.getValue());
            view.setSampleSize(samples.size());
            if (!samples.isEmpty()) {
                int index = Math.max(0, (int) Math.ceil(samples.size() * 0.9d) - 1);
                view.setP90Minutes(samples.get(index));
            }
            view.setPassed(samples.size() >= 5 && view.getP90Minutes() != null && view.getP90Minutes() <= 20d);
            if (!view.isPassed()) passed = false;
            chapterViews.add(view);
        }
        result.setChapterWaits(chapterViews);
        result.setWaitGatePassed(passed);
    }

    private void computeG2(TopicOnsiteMetricsVO result) {
        List<String> reasons = new ArrayList<>();
        if (result.getNService() == 0) reasons.add("N_service=0");
        if (result.getArrived() == 0) reasons.add("A=0");
        if (result.getArrivalRate() < 0.80d) reasons.add("到店率<80%");
        if (result.getCompletionRate() < 0.60d) reasons.add("到店完成率<60%");
        if (result.getRefundRate() > 0.05d) reasons.add("退款票率>5%");
        if (result.getComplaintUserRate() > 0.05d) reasons.add("有效服务投诉用户率>5%");
        if (!result.isWaitGatePassed()) reasons.add("分章节等待P90门槛未通过");
        result.setG2Reasons(reasons);
        result.setG2Passed(reasons.isEmpty());
    }

    private boolean hasRepeatWithin90Days(List<Date> payments, Date anchor) {
        if (payments == null) return false;
        long end = anchor.getTime() + 90L * DAY_MILLIS;
        for (Date payment : payments) {
            if (payment != null && payment.after(anchor) && payment.getTime() <= end) return true;
        }
        return false;
    }

    private Map<Long, List<Date>> repeatByMember(List<TopicRepeatPaymentFact> facts) {
        Map<Long, List<Date>> result = new HashMap<>();
        for (TopicRepeatPaymentFact fact : facts) {
            result.computeIfAbsent(fact.getMemberId(), ignored -> new ArrayList<>()).add(fact.getPaymentTime());
        }
        return result;
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0d : numerator / (double) denominator;
    }

    private <T> List<T> safe(List<T> source) {
        return source == null ? Collections.emptyList() : source;
    }
}

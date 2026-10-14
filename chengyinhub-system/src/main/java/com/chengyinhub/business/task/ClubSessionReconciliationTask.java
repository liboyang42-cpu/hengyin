package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.vo.ClubSessionGapVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** 生效俱乐部合作单与带队场次事实的只读巡检；只报警，不自动补场次。 */
@Component("clubSessionReconciliationTask")
public class ClubSessionReconciliationTask {

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private AlarmService alarmService;

    public void run() {
        List<ClubSessionGapVO> gaps = cmsActivityMapper.selectClubOrderTopicsWithoutClubSession();
        if (gaps == null || gaps.isEmpty()) {
            return;
        }
        // 逐条告警会在每次巡检时把同一批缺口重复刷屏：合并为一条按固定事件限频的告警，带前 20 个缺口定位。
        String samples = gaps.stream().limit(20)
                .map(gap -> "topicId=" + gap.getTopicId() + "/clubId=" + gap.getClubId())
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        alarmService.alarmThrottled("monitor.club_session.missing", "俱乐部合作主题缺少带队场次",
                "count=" + gaps.size() + " gaps=" + samples + " hasEffectiveClubOrder=true hasClubSession=false");
    }
}

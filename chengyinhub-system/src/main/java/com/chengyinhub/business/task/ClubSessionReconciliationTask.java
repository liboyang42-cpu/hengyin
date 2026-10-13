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
        for (ClubSessionGapVO gap : gaps) {
            alarmService.alarm("俱乐部合作主题缺少带队场次",
                    "topicId=" + gap.getTopicId() + " clubId=" + gap.getClubId()
                            + " hasEffectiveClubOrder=true hasClubSession=false");
        }
    }
}

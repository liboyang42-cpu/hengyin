package com.chengyinhub.web.task;

import com.chengyinhub.business.service.IPlayTeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/** 场次开始后逐队结束组队并关闭群聊，并清理过期的公开入队申请；不读取订单，也不触发退款。 */
@Component("playTeamExpireTask")
public class PlayTeamExpireTask {

    private static final Logger log = LoggerFactory.getLogger(PlayTeamExpireTask.class);

    @Autowired
    private IPlayTeamService playTeamService;

    public void run() {
        Date now = new Date();
        List<Long> teamIds = playTeamService.selectExpiredTeamIds(now, 200);
        int ended = 0;
        if (teamIds != null) {
            for (Long teamId : teamIds) {
                try {
                    ended += playTeamService.endExpiredTeam(teamId, now);
                } catch (Exception e) {
                    // 单队失败不阻断其它队；下一轮仍会由条件更新重试该队。
                    log.error("玩家队伍超时结束失败 teamId={}", teamId, e);
                }
            }
        }
        // 公开申请 24h / 场次开始失效的兜底清理;同意时另有读时判定,不依赖本任务是否按时跑。
        int expiredApplications = playTeamService.expireStaleApplications(now);
        log.info("玩家队伍超时扫描完成 candidateCount={} endedCount={} expiredApplications={}",
                teamIds == null ? 0 : teamIds.size(), ended, expiredApplications);
    }
}

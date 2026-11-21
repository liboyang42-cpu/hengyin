package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * P7(GRO-06)今日任务板:每条任务的目标、今天的真实进度、是否已记完成、配置里的经验奖励。
 * 进度只读事实表,不读客户端上报;奖励只用 growth_mission.exp_reward 现有配置。
 */
public class DailyMissionBoardVO
{
    /** 服务端日期 yyyy-MM-dd(进度与完成都按这一天算) */
    private String date;
    private List<Item> missions = new ArrayList<>();
    private int completedCount;
    private boolean allDone;

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public List<Item> getMissions() { return missions; }
    public void setMissions(List<Item> missions) { this.missions = missions; }
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int completedCount) { this.completedCount = completedCount; }
    public boolean isAllDone() { return allDone; }
    public void setAllDone(boolean allDone) { this.allDone = allDone; }

    public static class Item
    {
        private String missionCode;
        private String missionName;
        private String missionDesc;
        /** daily_walk / daily_reveal / daily_play / daily_shop */
        private String event;
        private int target;
        /** 今天的进度,封顶到 target */
        private int progress;
        private boolean completed;
        private int expReward;

        public String getMissionCode() { return missionCode; }
        public void setMissionCode(String missionCode) { this.missionCode = missionCode; }
        public String getMissionName() { return missionName; }
        public void setMissionName(String missionName) { this.missionName = missionName; }
        public String getMissionDesc() { return missionDesc; }
        public void setMissionDesc(String missionDesc) { this.missionDesc = missionDesc; }
        public String getEvent() { return event; }
        public void setEvent(String event) { this.event = event; }
        public int getTarget() { return target; }
        public void setTarget(int target) { this.target = target; }
        public int getProgress() { return progress; }
        public void setProgress(int progress) { this.progress = progress; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
        public int getExpReward() { return expReward; }
        public void setExpReward(int expReward) { this.expReward = expReward; }
    }
}

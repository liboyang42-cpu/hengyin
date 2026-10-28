package com.chengyinhub.business.domain.dto;

import java.util.List;

/** PUT /api/topic/play-setup 请求体:可玩时段 + 开局前准备,整体覆盖。 */
public class TopicPlaySetupDTO {

    private Long topicId;

    /** 可选 MERCHANT:商家 Owner 代管。 */
    private String scope;

    private PlayWindow playWindow;

    private List<String> prepItems;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public PlayWindow getPlayWindow() { return playWindow; }
    public void setPlayWindow(PlayWindow playWindow) { this.playWindow = playWindow; }

    public List<String> getPrepItems() { return prepItems; }
    public void setPrepItems(List<String> prepItems) { this.prepItems = prepItems; }

    /** mode:any / day / night / custom;start / end 只在 custom 时用(HH:mm)。 */
    public static class PlayWindow {
        private String mode;
        private String start;
        private String end;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getStart() { return start; }
        public void setStart(String start) { this.start = start; }
        public String getEnd() { return end; }
        public void setEnd(String end) { this.end = end; }
    }
}

package com.chengyinhub.business.domain.route;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 玩家端只读的主题路线权威状态合同。 */
public class TopicRouteState {
    public static final String LINEAR = "LINEAR";
    public static final String BRANCH_GRAPH = "BRANCH_GRAPH";
    public static final String HIDDEN = "HIDDEN";
    public static final String DISCOVERED_LOCKED = "DISCOVERED_LOCKED";
    public static final String PLAYABLE = "PLAYABLE";
    public static final String COMPLETED = "COMPLETED";

    private List<Map<String, Object>> attributes = new ArrayList<Map<String, Object>>();
    public List<Map<String, Object>> getAttributes() { return attributes; }
    public void setAttributes(List<Map<String, Object>> attributes) { this.attributes = attributes; }

    private String routeMode;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> opening;
    /** 思维阁:玩家**已经得到**的念头及其进度。没得到的不下发 —— 提前露出来就是剧透。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Map<String, Object>> thoughts;
    public List<Map<String, Object>> getThoughts() { return thoughts; }
    public void setThoughts(List<Map<String, Object>> thoughts) { this.thoughts = thoughts; }
    /** 这一局配了几个伏笔(不论得没得到)。客户端据此在开局请求微信运动授权;不露名字,不算剧透。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int thoughtCount;
    public int getThoughtCount() { return thoughtCount; }
    public void setThoughtCount(int thoughtCount) { this.thoughtCount = thoughtCount; }
    public Map<String, Object> getOpening() { return opening; }
    public void setOpening(Map<String, Object> opening) { this.opening = opening; }
    private Long sessionId;
    private String status;
    private Long currentNodeId;
    private Long recommendedNodeId;
    private Integer version;
    private Map<Long, String> nodeStates = new LinkedHashMap<Long, String>();
    private List<Map<String, Object>> decisionLog = new ArrayList<Map<String, Object>>();
    /**
     * M1 回执自带新世界:推进完成后下一拍的 Encounter 视图(与 /api/play/encounter 同形状)。
     * 只读的 state() 不组装它;推进回执成功或冲突重同步时才有值。
     */
    private Map<String, Object> encounter;
    /**
     * R22-A7:会改 hp / luck 的推进回执顶层带当前值(与 ask / call / 检定 / advanced action 同口径)。
     * 只读的 state() 与老回执没有这两位,保持 null 不下发(NON_NULL)。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer hp;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer luck;

    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(Long currentNodeId) { this.currentNodeId = currentNodeId; }
    public Long getRecommendedNodeId() { return recommendedNodeId; }
    public void setRecommendedNodeId(Long recommendedNodeId) { this.recommendedNodeId = recommendedNodeId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Map<Long, String> getNodeStates() { return nodeStates; }
    public void setNodeStates(Map<Long, String> nodeStates) {
        this.nodeStates = nodeStates == null ? new LinkedHashMap<Long, String>() : nodeStates;
    }
    public List<Map<String, Object>> getDecisionLog() { return decisionLog; }
    public void setDecisionLog(List<Map<String, Object>> decisionLog) {
        this.decisionLog = decisionLog == null ? new ArrayList<Map<String, Object>>() : decisionLog;
    }
    public Map<String, Object> getEncounter() { return encounter; }
    public void setEncounter(Map<String, Object> encounter) { this.encounter = encounter; }
    public Integer getHp() { return hp; }
    public void setHp(Integer hp) { this.hp = hp; }
    public Integer getLuck() { return luck; }
    public void setLuck(Integer luck) { this.luck = luck; }
}

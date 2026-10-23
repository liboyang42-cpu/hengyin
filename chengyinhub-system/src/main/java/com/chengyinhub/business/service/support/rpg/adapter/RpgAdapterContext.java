package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * C02 adapter 一次检定的工作上下文。客户端天气 / 客户端时钟字段只为证明被忽略而存在,
 * 任何 adapter 都不得用它们刷新环境或判定失效。
 */
public class RpgAdapterContext {
    private CmsTopic topic;
    private JourneyRules rules;
    private TopicRouteEngine.State state;
    private JsonNode check;
    private RpgAdapterSpec spec = RpgAdapterSpec.inactive();
    /** 原请求 id(与 TopicRouteAction.actionId / receiptId 同一条账,不另造)。 */
    private String originalRequestId;
    private Long nodeId;
    private Long memberId;
    private PlayerActivityRole actorRole;
    private List<PlayerActivityRole> partyRoles = new ArrayList<PlayerActivityRole>();
    /** 同 requestId 的原动作;重试读回执,不新开请求。 */
    private TopicRouteAction originalAction;
    /** 已有 {@code ThemeCharacterService.card} 投影;缺省则只从本局 state 拼只读卡。 */
    private Map<String, Object> liveCharacterCard;
    private IntSupplier dice;
    private boolean rerolled;
    private int bargainBonus;
    /** rest / exhaust-rest 才走归零恢复;检定路径不改 hp。 */
    private String recoveryKind;
    private boolean writeResources;
    private String clientWeather;
    private Long clientNowMillis;
    private PlayCheckReceipt receipt;
    private final Map<String, Object> facts = new LinkedHashMap<String, Object>();

    public CmsTopic getTopic() { return topic; }
    public void setTopic(CmsTopic topic) { this.topic = topic; }
    public JourneyRules getRules() { return rules; }
    public void setRules(JourneyRules rules) { this.rules = rules; }
    public TopicRouteEngine.State getState() { return state; }
    public void setState(TopicRouteEngine.State state) { this.state = state; }
    public JsonNode getCheck() { return check; }
    public void setCheck(JsonNode check) { this.check = check; }
    public RpgAdapterSpec getSpec() { return spec == null ? RpgAdapterSpec.inactive() : spec; }
    public void setSpec(RpgAdapterSpec spec) { this.spec = spec == null ? RpgAdapterSpec.inactive() : spec; }
    public String getOriginalRequestId() { return originalRequestId; }
    public void setOriginalRequestId(String originalRequestId) {
        this.originalRequestId = RpgAdapterSupport.blankToNull(originalRequestId);
    }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public PlayerActivityRole getActorRole() { return actorRole; }
    public void setActorRole(PlayerActivityRole actorRole) { this.actorRole = actorRole; }
    public List<PlayerActivityRole> getPartyRoles() { return partyRoles; }
    public void setPartyRoles(List<PlayerActivityRole> partyRoles) {
        this.partyRoles = partyRoles == null ? new ArrayList<PlayerActivityRole>() : partyRoles;
    }
    public TopicRouteAction getOriginalAction() { return originalAction; }
    public void setOriginalAction(TopicRouteAction originalAction) { this.originalAction = originalAction; }
    public Map<String, Object> getLiveCharacterCard() { return liveCharacterCard; }
    public void setLiveCharacterCard(Map<String, Object> liveCharacterCard) { this.liveCharacterCard = liveCharacterCard; }
    public IntSupplier getDice() { return dice; }
    public void setDice(IntSupplier dice) { this.dice = dice; }
    public boolean isRerolled() { return rerolled; }
    public void setRerolled(boolean rerolled) { this.rerolled = rerolled; }
    public int getBargainBonus() { return bargainBonus; }
    public void setBargainBonus(int bargainBonus) { this.bargainBonus = bargainBonus; }
    public String getRecoveryKind() { return recoveryKind; }
    public void setRecoveryKind(String recoveryKind) { this.recoveryKind = recoveryKind; }
    public boolean isWriteResources() { return writeResources; }
    public void setWriteResources(boolean writeResources) { this.writeResources = writeResources; }
    public String getClientWeather() { return clientWeather; }
    public void setClientWeather(String clientWeather) { this.clientWeather = clientWeather; }
    public Long getClientNowMillis() { return clientNowMillis; }
    public void setClientNowMillis(Long clientNowMillis) { this.clientNowMillis = clientNowMillis; }
    public PlayCheckReceipt getReceipt() { return receipt; }
    public void setReceipt(PlayCheckReceipt receipt) { this.receipt = receipt; }
    public Map<String, Object> getFacts() { return facts; }
}

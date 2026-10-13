package com.chengyinhub.business.domain.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅程检定与力竭的一次性回执(B5)。
 *
 * <p>它是 topic_route_action.response_json 的正文,同一个动作 id 重放时原样取回。
 * <p>结算前绝不携带 successText / failText / successEffects / failEffects / failCostLabel ——
 * 文案与效果只在 {@code settled=true} 的那一份回执里出现。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayCheckReceipt {
    public static final String NAT_CRIT = "CRIT";
    public static final String NAT_FUMBLE = "FUMBLE";

    private String checkId;
    private String tier;
    private Integer dc;
    private List<Mod> mods = new ArrayList<Mod>();
    private boolean advantage;
    private boolean disadvantage;
    private List<Integer> dice = new ArrayList<Integer>();
    private Integer kept;
    private Integer total;
    private Boolean success;
    private String nat;
    private boolean rerolled;
    private boolean settled;
    private String text;
    private String failCostLabel;
    private Integer hp;
    private Integer luck;
    private boolean exhausted;
    private Integer stateVersion;
    /**
     * M1 回执自带新世界:本次动作结算后的这一站 Encounter 视图(与 /api/play/encounter 同一份形状),
     * 客户端提交完直接渲染下一拍,不必再拉一次。解析不可用时缺省为 null,不影响动作本身成立。
     */
    private Map<String, Object> encounter;

    public String getCheckId() { return checkId; }
    public void setCheckId(String checkId) { this.checkId = checkId; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public Integer getDc() { return dc; }
    public void setDc(Integer dc) { this.dc = dc; }
    public List<Mod> getMods() { return mods; }
    public void setMods(List<Mod> mods) { this.mods = mods == null ? new ArrayList<Mod>() : mods; }
    public boolean isAdvantage() { return advantage; }
    public void setAdvantage(boolean advantage) { this.advantage = advantage; }
    public boolean isDisadvantage() { return disadvantage; }
    public void setDisadvantage(boolean disadvantage) { this.disadvantage = disadvantage; }
    public List<Integer> getDice() { return dice; }
    public void setDice(List<Integer> dice) { this.dice = dice == null ? new ArrayList<Integer>() : dice; }
    public Integer getKept() { return kept; }
    public void setKept(Integer kept) { this.kept = kept; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getNat() { return nat; }
    public void setNat(String nat) { this.nat = nat; }
    public boolean isRerolled() { return rerolled; }
    public void setRerolled(boolean rerolled) { this.rerolled = rerolled; }
    public boolean isSettled() { return settled; }
    public void setSettled(boolean settled) { this.settled = settled; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getFailCostLabel() { return failCostLabel; }
    public void setFailCostLabel(String failCostLabel) { this.failCostLabel = failCostLabel; }
    public Integer getHp() { return hp; }
    public void setHp(Integer hp) { this.hp = hp; }
    public Integer getLuck() { return luck; }
    public void setLuck(Integer luck) { this.luck = luck; }
    public boolean isExhausted() { return exhausted; }
    public void setExhausted(boolean exhausted) { this.exhausted = exhausted; }
    public Integer getStateVersion() { return stateVersion; }
    public void setStateVersion(Integer stateVersion) { this.stateVersion = stateVersion; }
    public Map<String, Object> getEncounter() { return encounter; }
    public void setEncounter(Map<String, Object> encounter) { this.encounter = encounter; }

    /** 一条条件修正:{@code when} 通过则 {@code applied=true},通过的 value 计入总修正(封顶 modCap)。 */
    public static class Mod {
        private String label;
        private int value;
        private boolean applied;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
        public boolean isApplied() { return applied; }
        public void setApplied(boolean applied) { this.applied = applied; }
    }
}

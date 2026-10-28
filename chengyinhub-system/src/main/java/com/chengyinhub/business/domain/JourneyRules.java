package com.chengyinhub.business.domain;

import com.chengyinhub.business.service.support.JourneyRulesValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 主题数值规则 journeyRules(R2 旅程体验模组化第二轮,主题级一份)。
 *
 * <p>形状见共享契约:{@code schemaVersion/checkEnabled/hp/luck/tiers/recovery/fumbleExtraDamage/modCap}。
 * 主题没配 = {@code checkEnabled=false},老主题行为完全不变。
 *
 * <p>它只描述「检定怎么算」的数值,不含任何检定逻辑(B5 另派),也不含运行期状态。
 */
public class JourneyRules {

    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private int schemaVersion = SCHEMA_VERSION;
    private boolean checkEnabled;
    private boolean stateEnabled;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private com.fasterxml.jackson.databind.node.ObjectNode attributeBinding;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private com.fasterxml.jackson.databind.node.ObjectNode attributeDefinition;
    public com.fasterxml.jackson.databind.node.ObjectNode getAttributeBinding() { return attributeBinding; }
    public void setAttributeBinding(com.fasterxml.jackson.databind.node.ObjectNode value) { attributeBinding = value; }
    public com.fasterxml.jackson.databind.node.ObjectNode getAttributeDefinition() { return attributeDefinition; }
    public void setAttributeDefinition(com.fasterxml.jackson.databind.node.ObjectNode value) { attributeDefinition = value; }

    private com.fasterxml.jackson.databind.node.ArrayNode attributes = MAPPER.createArrayNode();
    private com.fasterxml.jackson.databind.node.ArrayNode statLinks = MAPPER.createArrayNode();

    public com.fasterxml.jackson.databind.node.ArrayNode getStatLinks() { return statLinks; }
    public void setStatLinks(com.fasterxml.jackson.databind.node.ArrayNode statLinks) {
        this.statLinks = statLinks == null ? MAPPER.createArrayNode() : statLinks;
    }

    public boolean isStateEnabled() { return stateEnabled; }
    public void setStateEnabled(boolean stateEnabled) { this.stateEnabled = stateEnabled; }
    public com.fasterxml.jackson.databind.node.ArrayNode getAttributes() { return attributes; }
    public void setAttributes(com.fasterxml.jackson.databind.node.ArrayNode attributes) { this.attributes = attributes; }

    /** 思维阁的念头清单(2026-09-22):{key,name,desc?,need?,when?,doneWhen?}(步数与选项条件至少一种想通方式)。没配就是空数组。
     *  ⚠️ 这个字段必须存在 —— 本类没开 @JsonIgnoreProperties,validator 输出里有而
     *  POJO 没有的话,配了思维阁的主题一 parse 就抛「旅程数值规则无法解析」。 */
    private com.fasterxml.jackson.databind.node.ArrayNode thoughts = MAPPER.createArrayNode();

    public com.fasterxml.jackson.databind.node.ArrayNode getThoughts() { return thoughts; }
    public void setThoughts(com.fasterxml.jackson.databind.node.ArrayNode thoughts) {
        this.thoughts = thoughts == null ? MAPPER.createArrayNode() : thoughts;
    }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasRuntimeState() { return checkEnabled || stateEnabled || !thoughts.isEmpty(); }


    private Hp hp = new Hp();
    private Luck luck = new Luck();
    private Tiers tiers = new Tiers();
    private Recovery recovery = new Recovery();

    private int fumbleExtraDamage = 1;
    private int modCap = 6;

    /**
     * F2 T04:自然 20 必成 / 自然 1 必败开关。配置里没有这个字段 = 老主题,按原口径开启
     * (见 {@link JourneyRulesValidator}:只在作者显式配置时才写进规范化 JSON);
     * 显式 false = 关闭,自然骰只按 total 与 dc 比,也不再产生 CRIT / FUMBLE。
     */
    private boolean natAuto = true;

    /** 文档二 E1 规则难度档 easy / standard / hard;null = 老主题没选过(规范化不输出)。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String difficulty;

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    /**
     * F6 RPG P1 灰度块(环境键 / 进度钟 / 魔鬼交易 / 动量 / 特质),形状见 {@code JourneyRpg}。
     * 没配 = null = 全部关闭;序列化时 null 不输出,老主题冻结快照一字不变。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private com.fasterxml.jackson.databind.node.ObjectNode rpg;

    /**
     * 归一化解析:空/缺省返回 {@code checkEnabled=false} 的默认对象;非法配置抛
     * {@link IllegalArgumentException}(中文人话,与保存端同一套校验)。
     */
    public static JourneyRules parse(String rawJson) {
        String normalized = new JourneyRulesValidator().validateAndNormalize(rawJson);
        if (normalized == null) {
            return new JourneyRules();
        }
        try {
            return MAPPER.readValue(normalized, JourneyRules.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("旅程数值规则无法解析");
        }
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public boolean isCheckEnabled() { return checkEnabled; }
    public void setCheckEnabled(boolean checkEnabled) { this.checkEnabled = checkEnabled; }

    public Hp getHp() { return hp; }
    public void setHp(Hp hp) { this.hp = hp; }

    public Luck getLuck() { return luck; }
    public void setLuck(Luck luck) { this.luck = luck; }

    public Tiers getTiers() { return tiers; }
    public void setTiers(Tiers tiers) { this.tiers = tiers; }

    public Recovery getRecovery() { return recovery; }
    public void setRecovery(Recovery recovery) { this.recovery = recovery; }

    public int getFumbleExtraDamage() { return fumbleExtraDamage; }
    public void setFumbleExtraDamage(int fumbleExtraDamage) { this.fumbleExtraDamage = fumbleExtraDamage; }

    public int getModCap() { return modCap; }
    public void setModCap(int modCap) { this.modCap = modCap; }

    public boolean isNatAuto() { return natAuto; }
    public void setNatAuto(boolean natAuto) { this.natAuto = natAuto; }

    public com.fasterxml.jackson.databind.node.ObjectNode getRpg() { return rpg; }
    public void setRpg(com.fasterxml.jackson.databind.node.ObjectNode rpg) { this.rpg = rpg; }

    /** 生命值规则;{@code enabled=false} 时检定失败不扣血。 */
    public static class Hp {
        private boolean enabled = true;
        private int max = 10;
        private int init = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }
        public int getInit() { return init; }
        public void setInit(int init) { this.init = init; }
    }

    /** 幸运值规则;{@code enabled=false} 时不能重掷。 */
    public static class Luck {
        private boolean enabled = true;
        private int max = 3;
        private int init = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }
        public int getInit() { return init; }
        public void setInit(int init) { this.init = init; }
    }

    /** 三档难度:只允许 easy/medium/hard,禁止任意 DC 数字。 */
    public static class Tiers {
        private Tier easy = new Tier(10, 1);
        private Tier medium = new Tier(13, 2);
        private Tier hard = new Tier(16, 3);

        public Tier getEasy() { return easy; }
        public void setEasy(Tier easy) { this.easy = easy; }
        public Tier getMedium() { return medium; }
        public void setMedium(Tier medium) { this.medium = medium; }
        public Tier getHard() { return hard; }
        public void setHard(Tier hard) { this.hard = hard; }
    }

    /** 单档:{@code dc}=难度值,{@code damage}=失败伤害。 */
    public static class Tier {
        private int dc;
        private int damage;

        public Tier() {
        }

        public Tier(int dc, int damage) {
            this.dc = dc;
            this.damage = damage;
        }

        public int getDc() { return dc; }
        public void setDc(int dc) { this.dc = dc; }
        public int getDamage() { return damage; }
        public void setDamage(int damage) { this.damage = damage; }
    }

    /** 恢复规则;{@code exhaustTag} 可空,配了就必须是已注册的 tag.* 状态键。 */
    public static class Recovery {
        private int stationEndIfHurt = 2;
        private int stageRestHpFloor = 6;
        private int stageRestLuckFloor = 1;
        private int exhaustRestHp = 5;
        private String exhaustTag;

        public int getStationEndIfHurt() { return stationEndIfHurt; }
        public void setStationEndIfHurt(int stationEndIfHurt) { this.stationEndIfHurt = stationEndIfHurt; }
        public int getStageRestHpFloor() { return stageRestHpFloor; }
        public void setStageRestHpFloor(int stageRestHpFloor) { this.stageRestHpFloor = stageRestHpFloor; }
        public int getStageRestLuckFloor() { return stageRestLuckFloor; }
        public void setStageRestLuckFloor(int stageRestLuckFloor) { this.stageRestLuckFloor = stageRestLuckFloor; }
        public int getExhaustRestHp() { return exhaustRestHp; }
        public void setExhaustRestHp(int exhaustRestHp) { this.exhaustRestHp = exhaustRestHp; }
        public String getExhaustTag() { return exhaustTag; }
        public void setExhaustTag(String exhaustTag) { this.exhaustTag = exhaustTag; }
    }
}

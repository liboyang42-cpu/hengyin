package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 文档二 E1 规则难度三档。作者只挑一档({@code journeyRules.difficulty}),
 * 系统按档写进已有字段(三档难度线、情境加值上限、动量),作者看不到数字。
 *
 * <p>轻松 8/11/14、+8、动量 8/3/+2/4;标准 10/13/16、+6、6/2/+1/3;硬核 12/15/18、+4、4/0/+1/2。
 * 魔鬼交易加值与进度钟默认格数只是新建时的默认值,由前端带上,这里不改已存的值。
 */
public final class JourneyDifficulty {

    public static final String EASY = "easy";
    public static final String STANDARD = "standard";
    public static final String HARD = "hard";

    public static final JourneyDifficulty EASY_PRESET = new JourneyDifficulty(EASY, 8, 11, 14, 8, 8, 3, 2, 4);
    public static final JourneyDifficulty STANDARD_PRESET = new JourneyDifficulty(STANDARD, 10, 13, 16, 6, 6, 2, 1, 3);
    public static final JourneyDifficulty HARD_PRESET = new JourneyDifficulty(HARD, 12, 15, 18, 4, 4, 0, 1, 2);

    final String value;
    final int easyDc;
    final int mediumDc;
    final int hardDc;
    final int modCap;
    final int momentumMax;
    final int momentumInit;
    final int momentumGainOnFail;
    final int momentumMaxSpend;

    private JourneyDifficulty(String value, int easyDc, int mediumDc, int hardDc, int modCap,
                              int momentumMax, int momentumInit, int momentumGainOnFail, int momentumMaxSpend) {
        this.value = value;
        this.easyDc = easyDc;
        this.mediumDc = mediumDc;
        this.hardDc = hardDc;
        this.modCap = modCap;
        this.momentumMax = momentumMax;
        this.momentumInit = momentumInit;
        this.momentumGainOnFail = momentumGainOnFail;
        this.momentumMaxSpend = momentumMaxSpend;
    }

    public String getValue() { return value; }

    /** 缺省 / null 返回 null(老主题);非法值抛中文人话。 */
    public static JourneyDifficulty parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        JourneyDifficulty preset = node.isTextual() ? of(node.textValue()) : null;
        if (preset == null) throw new IllegalArgumentException("规则难度只能是轻松、标准或硬核");
        return preset;
    }

    public static JourneyDifficulty of(String value) {
        if (EASY.equals(value)) return EASY_PRESET;
        if (STANDARD.equals(value)) return STANDARD_PRESET;
        if (HARD.equals(value)) return HARD_PRESET;
        return null;
    }

    /**
     * 编辑器显示用:选过就是选的那一档;老主题按难度线推断,三个数都对上某一档才算那一档,否则显示标准。
     */
    public static String resolve(JourneyRules rules) {
        if (rules == null) return STANDARD;
        if (of(rules.getDifficulty()) != null) return rules.getDifficulty();
        JourneyRules.Tiers tiers = rules.getTiers();
        if (tiers != null && tiers.getEasy() != null && tiers.getMedium() != null && tiers.getHard() != null) {
            for (JourneyDifficulty preset : new JourneyDifficulty[] {EASY_PRESET, STANDARD_PRESET, HARD_PRESET}) {
                if (tiers.getEasy().getDc() == preset.easyDc && tiers.getMedium().getDc() == preset.mediumDc
                        && tiers.getHard().getDc() == preset.hardDc) {
                    return preset.value;
                }
            }
        }
        return STANDARD;
    }

    /** 已规范化的 rpg 里有动量块时按档覆盖四个数;开关本身照作者的。 */
    void applyMomentum(ObjectNode rpg) {
        JsonNode momentum = rpg.get("momentum");
        if (!(momentum instanceof ObjectNode)) return;
        ObjectNode target = (ObjectNode) momentum;
        target.put("max", momentumMax);
        target.put("init", momentumInit);
        target.put("gainOnFail", momentumGainOnFail);
        target.put("maxSpend", momentumMaxSpend);
    }
}

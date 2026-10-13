package com.chengyinhub.business.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

/** 圈层轻互动的唯一规则入口；页面只负责展示，服务端决定答案与完成是否成立。 */
public final class CircleInteractionPolicy {
    private static final Set<String> SHANGHAI_KEYWORDS = set("味道", "穿搭", "声音", "夜色", "人情");

    private CircleInteractionPolicy() { }

    public static void assertAnswer(String themeCode, String stage, String value,
                                    Set<String> enabledCandidateCodes) {
        if (blank(themeCode) || !allowedStages(themeCode).contains(stage)) {
            throw new IllegalStateException("该轻互动阶段不适用于本主题");
        }
        if (blank(value) || value.trim().length() > 300) {
            throw new IllegalStateException("答案不能为空且不能超过300字");
        }
        String clean = value.trim();
        if (isCandidateChoice(themeCode, stage)
                && (enabledCandidateCodes == null || !enabledCandidateCodes.contains(clean))) {
            throw new IllegalStateException("所选商家不在当前城市实例中");
        }
        Set<String> choices = allowedValues(themeCode, stage);
        if (!choices.isEmpty() && !choices.contains(clean)) {
            throw new IllegalStateException("答案不在该主题的可选项中");
        }
        if ("SHANGHAI".equals(themeCode) && "CITY_KEYWORDS".equals(stage)) {
            String[] tokens = clean.split("[,，]");
            if (tokens.length < 1 || tokens.length > 4) {
                throw new IllegalStateException("请为1—4家已记录商家选择城市关键词");
            }
            Set<String> candidates = new HashSet<>();
            for (String token : tokens) {
                String[] pair = token.trim().split(":", 2);
                if (pair.length != 2 || enabledCandidateCodes == null
                        || !enabledCandidateCodes.contains(pair[0])
                        || !SHANGHAI_KEYWORDS.contains(pair[1])
                        || !candidates.add(pair[0])) {
                    throw new IllegalStateException("答案不在该主题的可选项中");
                }
            }
        }
    }

    public static boolean isSharedStage(String themeCode, String stage) {
        return set("FITNESS", "MIDLIFE", "DATE", "FRIENDS", "SHANGHAI").contains(themeCode)
                && allowedStages(themeCode).contains(stage);
    }

    public static Set<String> shanghaiCandidateCodes(String value) {
        if (blank(value)) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String token : value.trim().split("[,，]")) {
            String[] pair = token.trim().split(":", 2);
            if (pair.length == 2) result.add(pair[0]);
        }
        return result;
    }

    public static boolean isComplete(String themeCode, int memberCount, ToIntFunction<String> answerCount) {
        int members = Math.max(1, memberCount);
        if ("FITNESS".equals(themeCode)) {
            return answerCount.applyAsInt("PRE_CHOICE") >= members
                    && answerCount.applyAsInt("POST_CHOICE") >= members;
        }
        if ("MIDLIFE".equals(themeCode)) return answerCount.applyAsInt("SELF_MOMENT") >= members;
        if ("DATE".equals(themeCode)) return answerCount.applyAsInt("QUESTION_CARD") >= 1;
        if ("FRIENDS".equals(themeCode)) {
            return answerCount.applyAsInt("PRE_WISH") >= members
                    && answerCount.applyAsInt("NEXT_PICK") >= 1;
        }
        if ("SHANGHAI".equals(themeCode)) return answerCount.applyAsInt("CITY_KEYWORDS") >= 1;
        if ("AFTER_WORK".equals(themeCode)) {
            return answerCount.applyAsInt("PRE_STATE") >= members
                    && answerCount.applyAsInt("POST_STATE") >= members;
        }
        if ("SOLO".equals(themeCode)) return answerCount.applyAsInt("DECISION") >= 1;
        if ("PARENT_CHILD".equals(themeCode)) {
            return answerCount.applyAsInt("ADULT_FAVORITE") >= 1
                    && answerCount.applyAsInt("CHILD_FAVORITE") >= 1;
        }
        return ("PHOTO".equals(themeCode) || "NIGHT".equals(themeCode))
                && answerCount.applyAsInt("KEYWORD") >= 1;
    }

    public static boolean hasRequiredMembers(String themeCode, int memberCount) {
        if ("DATE".equals(themeCode) || "FRIENDS".equals(themeCode)) return memberCount >= 2;
        return memberCount >= 1;
    }

    private static Set<String> allowedStages(String themeCode) {
        if ("FITNESS".equals(themeCode)) return set("PRE_CHOICE", "POST_CHOICE");
        if ("MIDLIFE".equals(themeCode)) return set("SELF_MOMENT");
        if ("DATE".equals(themeCode)) return set("QUESTION_CARD");
        if ("FRIENDS".equals(themeCode)) return set("PRE_WISH", "NEXT_PICK");
        if ("SHANGHAI".equals(themeCode)) return set("CITY_KEYWORDS");
        if ("AFTER_WORK".equals(themeCode)) return set("PRE_STATE", "POST_STATE");
        if ("SOLO".equals(themeCode)) return set("DECISION");
        if ("PARENT_CHILD".equals(themeCode)) return set("ADULT_FAVORITE", "CHILD_FAVORITE");
        if ("PHOTO".equals(themeCode) || "NIGHT".equals(themeCode)) return set("KEYWORD");
        return Collections.emptySet();
    }

    private static Set<String> allowedValues(String themeCode, String stage) {
        if ("DATE".equals(themeCode) && "QUESTION_CARD".equals(stage)) return set("Q1", "Q2", "Q3");
        if ("AFTER_WORK".equals(themeCode)
                && ("PRE_STATE".equals(stage) || "POST_STATE".equals(stage))) {
            return set("脑子很满", "身体很累", "不想说话", "想见人");
        }
        if ("NIGHT".equals(themeCode) && "KEYWORD".equals(stage)) {
            return set("夜宵", "音乐", "聊天", "游戏", "夜色");
        }
        return Collections.emptySet();
    }

    private static boolean isCandidateChoice(String themeCode, String stage) {
        return ("FITNESS".equals(themeCode)
                && ("PRE_CHOICE".equals(stage) || "POST_CHOICE".equals(stage)))
                || ("FRIENDS".equals(themeCode)
                && ("PRE_WISH".equals(stage) || "NEXT_PICK".equals(stage)));
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

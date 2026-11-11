package com.chengyinhub.business.service.support;

/**
 * 生成式 AI 内容显式标识的唯一判据(《人工智能生成合成内容标识办法》,P0-14)。
 *
 * <p>主题经「快速配置」AI 起草创建时 cms_topic.publish_mode 落为 {@code ai_simple},编辑器复制/恢复同一口径
 * (fabu {@code aiSimple = publishMode === 'ai_simple'})。玩家与作者看到该主题的故事内容时显示「AI 生成」。
 * 小程序 {@code utils/aigc-label.js} 是同一判据的镜像。
 */
public final class AigcLabel {

    public static final String AI_PUBLISH_MODE = "ai_simple";

    private AigcLabel() {}

    public static boolean isAiGenerated(String publishMode) {
        return publishMode != null && AI_PUBLISH_MODE.equalsIgnoreCase(publishMode.trim());
    }
}

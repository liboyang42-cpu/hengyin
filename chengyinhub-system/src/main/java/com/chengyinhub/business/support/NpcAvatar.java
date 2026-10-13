package com.chengyinhub.business.support;

import java.util.regex.Pattern;

/**
 * NPC 形象地址的合法形态。门店分身(scope_type=2)与节点 NPC(scope_type=4)两条上传路径共用一份规则 ——
 * 分头写两份的话，先加预设的人只会改一边，另一边的商家会突然「形象格式不支持」。
 *
 * <p>合法形态:
 * <ul>
 *   <li>{@code px1:xxx} —— 小程序内置的预设像素形象，存编码不存图（见 utils/pixel-avatar-data.js）；</li>
 *   <li>{@code /profile/...} —— 老上传通道返回的站内相对路径；</li>
 *   <li>{@code http(s)://...} —— OSS 预签名 URL。</li>
 * </ul>
 * 其余一律拒:这个字段最终要进 img src，不校验就会变成任意字符串的垃圾场。
 */
public final class NpcAvatar {

    private static final Pattern PRESET = Pattern.compile("px1:(p01|p04|p07|p10|p13|p17|p18|p23|p25|p27|p31|p36)");

    private NpcAvatar() { }

    /** 是不是内置预设像素形象（是的话没有上传动作，也没有送检的意义）。 */
    public static boolean isPreset(String avatar) {
        return avatar != null && PRESET.matcher(avatar).matches();
    }

    /** 预设编码 / 站内相对路径 / http(s) URL 三种形态之一。 */
    public static boolean isAllowed(String avatar) {
        if (avatar == null || avatar.isEmpty()) {
            return false;
        }
        return isPreset(avatar) || avatar.startsWith("/")
                || avatar.startsWith("https://") || avatar.startsWith("http://");
    }
}

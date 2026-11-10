package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 自玩通行证局（{@code activity_id=0}）的帮助原局身份。
 *
 * <p>自玩局的 {@code topic_route_session.play_instance_id} 必须保持 {@code "default"}：
 * 首次奖励、偏好幂等键与旧进度行都按 {@code default} 认"这是本人的正式一局"。所以这里不改
 * 进度实例，只在**建局同一次 insert** 里往冻结快照写一个服务端生成的
 * {@code helpRunInstance = "selfplay:<uuid>"}：
 * <ul>
 *   <li>一局一个，建局时生成；重进拿到的是同一行，所以天然幂等；</li>
 *   <li>与该局的冻结内容/阶梯/整局 N 同在一份快照里，开局后作者改配置不影响；</li>
 *   <li>帮助额度、层级事实、积分流水与成绩明细都按它分局，审计流水里记的也是它。</li>
 * </ul>
 *
 * <p>没有这个键的旧自玩局、以及活动场次的 {@code default} 局，仍然拿不到帮助身份
 * （调用方照旧拒绝「原局身份不可用」）。客户端没有任何途径写入或指定它。
 */
public final class SelfPlayHelpRun {

    /** 冻结快照根层字段名。 */
    public static final String FIELD = "helpRunInstance";
    public static final String PREFIX = "selfplay:";
    private static final String DEFAULT_INSTANCE = "default";
    private static final Pattern IDENTITY = Pattern.compile(
            "selfplay:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private SelfPlayHelpRun() {
    }

    /** 新身份；只在建局时调用一次。 */
    public static String newIdentity() {
        return PREFIX + UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    }

    /** 自玩局 = 没有活动场次。 */
    public static boolean selfPlay(Long activityId) {
        return activityId == null || activityId <= 0L;
    }

    /**
     * 建局时把身份写进冻结快照。只对自玩局写；已有合法身份不覆盖（重放同一快照仍是同一局）。
     *
     * @return 该快照最终携带的身份；非自玩局返回 {@code null}
     */
    public static String stamp(ObjectNode frozenRoot, Long activityId, String identity) {
        if (frozenRoot == null || !selfPlay(activityId)) return null;
        String existing = read(frozenRoot);
        if (existing != null) return existing;
        if (identity == null || !IDENTITY.matcher(identity).matches()) {
            throw new IllegalArgumentException("自玩局帮助身份不合法");
        }
        frozenRoot.put(FIELD, identity);
        return identity;
    }

    /** 从冻结快照读身份；缺失或形状不对一律 {@code null}，不猜。 */
    public static String read(JsonNode frozenRoot) {
        if (frozenRoot == null || !frozenRoot.isObject()) return null;
        JsonNode value = frozenRoot.get(FIELD);
        if (value == null || !value.isTextual()) return null;
        String identity = value.asText();
        return IDENTITY.matcher(identity).matches() ? identity : null;
    }

    /**
     * 本局的帮助实例：
     * <ol>
     *   <li>进度实例不是 {@code default}（重玩 {@code replay:<id>} 等）—— 原样沿用，行为不变；</li>
     *   <li>{@code default} 且是自玩局 —— 用建局时冻结的 {@code selfplay:} 身份；</li>
     *   <li>其它（旧自玩局无身份、活动场次 {@code default} 局、空实例）—— {@code null}。</li>
     * </ol>
     */
    public static String helpInstance(TopicRouteSession session, JsonNode frozenRoot) {
        if (session == null) return null;
        String instance = session.getPlayInstanceId() == null ? "" : session.getPlayInstanceId().trim();
        if (instance.isEmpty()) return null;
        if (!DEFAULT_INSTANCE.equalsIgnoreCase(instance)) return instance;
        if (!selfPlay(session.getActivityId())) return null;
        return read(frozenRoot);
    }

    /** 作用域实例是否是自玩局帮助身份（决定额度归属按哪条事实核验）。 */
    public static boolean isIdentity(String playInstance) {
        return playInstance != null && IDENTITY.matcher(playInstance).matches();
    }

    /** 进度行与奖励仍按这个实例找自玩局本身。 */
    public static String progressInstance() {
        return DEFAULT_INSTANCE;
    }
}

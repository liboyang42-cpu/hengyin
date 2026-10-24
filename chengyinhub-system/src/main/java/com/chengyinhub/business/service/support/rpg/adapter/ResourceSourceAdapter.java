package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 原资源写入:局内效果只走 {@link TopicRouteEngine#applyEffects} 已有 ADD_ITEM / GRANT_XP / GRANT_BADGE /
 * SET / INC,幂等键仍是 themeRuntime.sources 与 storyGrants。不另造钱包。
 * 持久原请求走 {@link OriginalResourcePersist}（Runtime/dot）；缺端口 WAITING_SOURCE，
 * 进程内 applyEffects 不是重启恢复证明。
 * 原动作回读走既有 {@link TopicRouteSessionMapper#selectActionByAction}；
 * 不复制 rpgv2.RpgRequestReceipts，不发明 rpg2:sha256。
 */
public class ResourceSourceAdapter {
    public static final String RUNTIME_PERSIST = "topicRouteRuntime.persistOriginal";
    public static final String RPG_REQUEST_RECEIPTS = "rpgv2.RpgRequestReceipts";

    private final TopicRouteEngine engine = new TopicRouteEngine();
    private final OriginalResourcePersist persist;
    private final OriginalRouteActionSource routeActions;

    public ResourceSourceAdapter() {
        this(null, null);
    }

    public ResourceSourceAdapter(OriginalResourcePersist persist) {
        this(persist, null);
    }

    public ResourceSourceAdapter(OriginalResourcePersist persist, OriginalRouteActionSource routeActions) {
        this.persist = persist;
        this.routeActions = routeActions;
    }

    public Map<String, Object> writeOriginal(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        if (RpgAdapterSupport.inactive(context) || context.getSpec().getResources() == null
                || context.getReceipt() == null || !context.isWriteResources()) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        String requestId = RpgAdapterSupport.blankToNull(context.getOriginalRequestId());
        fact.put("requestId", requestId);
        if (requestId == null) {
            fact.put("applied", Boolean.FALSE);
            fact.put("status", "WAITING_SOURCE");
            context.getFacts().put("resources", fact);
            return fact;
        }
        JsonNode effects = Boolean.TRUE.equals(context.getReceipt().getSuccess())
                ? context.getSpec().getResources().getSuccessEffects()
                : context.getSpec().getResources().getFailEffects();
        if (effects == null || !effects.isArray() || effects.isEmpty()) {
            fact.put("applied", Boolean.FALSE);
            fact.put("emptyEffects", Boolean.TRUE);
            context.getFacts().put("resources", fact);
            return fact;
        }
        int sourcesBefore = context.getState().getThemeRuntime().path("sources").size();
        int grantsBefore = context.getState().getStoryGrants().size();
        engine.applyEffects(effects, context.getState());
        fact.put("sourcesDelta", Integer.valueOf(context.getState().getThemeRuntime().path("sources").size() - sourcesBefore));
        fact.put("grantsDelta", Integer.valueOf(context.getState().getStoryGrants().size() - grantsBefore));
        fact.put("applied", Boolean.TRUE);
        if (persist == null) {
            fact.put("persisted", Boolean.FALSE);
            fact.put("waitingCapability", RUNTIME_PERSIST);
        } else {
            OriginalReceipt receipt = persist.persist(requestId, context.getState());
            fact.put("persisted", Boolean.TRUE);
            fact.put("persistReplay", Boolean.valueOf(receipt != null && receipt.replay));
        }
        context.getFacts().put("resources", fact);
        return fact;
    }

    /**
     * 把已 apply 的局状态交给既有 Runtime 持久原请求。缺端口为 WAITING_SOURCE，不另造钱包。
     */
    public OriginalReceipt persistOriginal(RpgAdapterContext context) {
        if (RpgAdapterSupport.inactive(context)) {
            throw new WaitingSourceException(RUNTIME_PERSIST);
        }
        String requestId = RpgAdapterSupport.blankToNull(context.getOriginalRequestId());
        if (requestId == null) {
            throw new WaitingSourceException(RUNTIME_PERSIST);
        }
        if (persist == null) {
            throw new WaitingSourceException(RUNTIME_PERSIST);
        }
        return persist.persist(requestId, context.getState());
    }

    /**
     * 新实例从 Runtime 原请求账回读。缺端口 WAITING_SOURCE；未见过的键返回 {@code null}，不占进程 sources。
     * 缺/空 requestId 保持 Java {@code null}。
     */
    public OriginalReceipt recoverFromPersist(String requestId) {
        String id = RpgAdapterSupport.blankToNull(requestId);
        if (id == null) {
            return null;
        }
        if (persist == null) {
            throw new WaitingSourceException(RUNTIME_PERSIST);
        }
        return persist.recover(id);
    }

    /**
     * 新实例从既有 {@code selectActionByAction} 回读。调用方传入已解析的 actionId，
     * 本适配器不发明 {@code rpg2:} SHA-256。缺端口 WAITING_SOURCE {@link #RPG_REQUEST_RECEIPTS}；
     * 缺/空 session、member、actionId 返回 {@code null}；未见过的键返回 {@code null}，不占进程 sources。
     */
    public OriginalReceipt recoverFromRouteAction(Long sessionId, Long memberId, String actionId) {
        String id = RpgAdapterSupport.blankToNull(actionId);
        if (sessionId == null || memberId == null || id == null) {
            return null;
        }
        if (routeActions == null) {
            throw new WaitingSourceException(RPG_REQUEST_RECEIPTS);
        }
        TopicRouteAction row = routeActions.selectActionByAction(sessionId, memberId, id);
        if (row == null) {
            return null;
        }
        return new OriginalReceipt(row.getActionId() == null ? id : row.getActionId(), true);
    }

    /** Runtime/dot 持有的原请求持久化；本适配器不实现第二套账。 */
    public interface OriginalResourcePersist {
        OriginalReceipt persist(String requestId, TopicRouteEngine.State snapshot);

        OriginalReceipt recover(String requestId);
    }

    /** 只读既有动作表；不 insertAction。 */
    public interface OriginalRouteActionSource {
        TopicRouteAction selectActionByAction(Long sessionId, Long memberId, String actionId);
    }

    public static final class MapperOriginalRouteActionSource implements OriginalRouteActionSource {
        private final TopicRouteSessionMapper mapper;

        public MapperOriginalRouteActionSource(TopicRouteSessionMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "topicRouteSessionMapper");
        }

        @Override
        public TopicRouteAction selectActionByAction(Long sessionId, Long memberId, String actionId) {
            return mapper.selectActionByAction(sessionId, memberId, actionId);
        }
    }

    public static final class OriginalReceipt {
        public final String requestId;
        public final boolean replay;

        public OriginalReceipt(String requestId, boolean replay) {
            this.requestId = requestId;
            this.replay = replay;
        }
    }

    public static final class WaitingSourceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String waitingCapability;

        public WaitingSourceException(String waitingCapability) {
            super("WAITING_SOURCE " + waitingCapability);
            this.waitingCapability = waitingCapability;
        }

        public String getWaitingCapability() {
            return waitingCapability;
        }
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        if (RpgAdapterSupport.inactive(context)) return;
        Object fact = context.getFacts().get("resources");
        if (fact != null) RpgAdapterSupport.putExtra(receipt, "resources", fact);
        String requestId = context == null ? null : RpgAdapterSupport.blankToNull(context.getOriginalRequestId());
        if (requestId != null) {
            RpgAdapterSupport.putExtra(receipt, "requestId", requestId);
        }
    }
}

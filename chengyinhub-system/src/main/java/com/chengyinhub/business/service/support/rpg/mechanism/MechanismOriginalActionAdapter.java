package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;

import java.util.Objects;

/**
 * 原机制请求回读:只读既有 {@link TopicRouteSessionMapper#selectActionByAction}。
 * 不 insertAction,不另造钱包或第二套 request 账。
 * 缺端口 WAITING_SOURCE {@link #SELECT_ACTION_BY_ACTION};
 * 未见过的 actionId 返回 {@code null},不 {@code bind} 进内存账。
 */
public final class MechanismOriginalActionAdapter {
    public static final String SELECT_ACTION_BY_ACTION = "topicRouteSessionMapper.selectActionByAction";

    private final MechanismRequestIdBinder binder;
    private final OriginalActionSource actions;

    public MechanismOriginalActionAdapter(MechanismRequestIdBinder binder) {
        this(binder, null);
    }

    public MechanismOriginalActionAdapter(MechanismRequestIdBinder binder, OriginalActionSource actions) {
        this.binder = binder == null ? new MechanismRequestIdBinder() : binder;
        this.actions = actions;
    }

    public MechanismRequestIdBinder ledger() {
        return binder;
    }

    /**
     * 新实例从既有动作表回读原 action。缺/空 session、member、actionId 返回 {@code null};
     * 缺端口抛 {@link WaitingSourceException};未见过的键返回 {@code null} 且不占内存账。
     */
    public TopicRouteAction recoverFromRouteAction(Long sessionId, Long memberId, String actionId) {
        String id = blankToNull(actionId);
        if (sessionId == null || memberId == null || id == null) {
            return null;
        }
        if (actions == null) {
            throw new WaitingSourceException(SELECT_ACTION_BY_ACTION);
        }
        TopicRouteAction row = actions.selectActionByAction(sessionId, memberId, id);
        if (row == null || row.getActionId() == null || !id.equals(row.getActionId())
                || row.getSessionId() == null || !sessionId.equals(row.getSessionId())
                || row.getMemberId() == null || !memberId.equals(row.getMemberId())) {
            return null;
        }
        return row;
    }

    /** 只读既有动作表;不 insertAction。 */
    public interface OriginalActionSource {
        TopicRouteAction selectActionByAction(Long sessionId, Long memberId, String actionId);
    }

    public static final class MapperOriginalActionSource implements OriginalActionSource {
        private final TopicRouteSessionMapper mapper;

        public MapperOriginalActionSource(TopicRouteSessionMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "topicRouteSessionMapper");
        }

        @Override
        public TopicRouteAction selectActionByAction(Long sessionId, Long memberId, String actionId) {
            return mapper.selectActionByAction(sessionId, memberId, actionId);
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

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}

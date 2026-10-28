package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientSessionClock;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.OrientSessionMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Route-owned orchestration; scoring ledger/rewards remain owned by IOrientScoringService. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrientRouteSettlement {
    private final IOrientScoringService scoring;
    private final TopicRouteSessionMapper routes;
    private final OrientSessionMapper clocks;

    public OrientRouteSettlement(IOrientScoringService scoring, TopicRouteSessionMapper routes, OrientSessionMapper clocks) {
        this.scoring = scoring; this.routes = routes; this.clocks = clocks;
    }

    public boolean active(CmsTopic topic) { return scoring.isActiveFor(topic); }

    /** Call before action/task writes. Reload the row after acquiring the same lock as scoring. */
    public TopicRouteSession lock(TopicRouteSession original) {
        TopicRouteSession locked = routes.selectAccessibleByIdForUpdate(original.getId(), original.getMemberId());
        if (locked == null) throw new ServiceException("本局不存在或无权操作", 404);
        return locked;
    }

    /** Check before terminal CAS while the original session lock is held. Abandon is not an award. */
    public void beforePersist(TopicRouteSession session, TopicRouteEngine.State state) {
        String reason = state.getThemeRuntime().path("result").path("reason").asText();
        if (!"ABANDONED".equals(reason) && (Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(session.getStatus())
                || Integer.valueOf(TopicRouteSession.STATUS_ENDED).equals(session.getStatus()) && "COMPLETED".equals(reason))) {
            scoring.assertCompletionAllowed(session.getId());
        }
    }

    /** Called after the terminal CAS, still inside its transaction; all failures roll back that CAS. */
    public void complete(TopicRouteSession session, TopicRouteEngine.State state) {
        boolean completed = Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(session.getStatus());
        boolean ended = Integer.valueOf(TopicRouteSession.STATUS_ENDED).equals(session.getStatus());
        if (!completed && !ended) return;
        if (session.getCompletedAt() == null) throw new IllegalStateException("终局缺少服务端结束时间");
        OrientSessionClock clock = clocks.selectClock(session.getId(), session.getMemberId());
        if (clock != null && clock.getEndedAt() == null) {
            long elapsed = Math.max(0L, session.getCompletedAt().getTime() - clock.getStartedAt().getTime());
            if (clocks.closeClock(session.getId(), session.getMemberId(), session.getCompletedAt(), elapsed) != 1) {
                throw new IllegalStateException("终局计时冻结失败");
            }
        }
        String reason = state.getThemeRuntime().path("result").path("reason").asText();
        // END is explicitly ABANDONED. Death/time-limit policies are not authorization for ordinary rewards.
        if (!"ABANDONED".equals(reason) && (completed || "COMPLETED".equals(reason))) {
            scoring.settleBySystem(session.getId(), "lifecycle:settle:" + session.getId());
        }
    }
}

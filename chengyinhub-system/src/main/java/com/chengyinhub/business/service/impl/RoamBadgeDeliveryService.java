package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamBadgeDeliveryMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Finish freezes earned rights; a badge failure must not reopen finish or award its XP again. */
@Service
public class RoamBadgeDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(RoamBadgeDeliveryService.class);
    private final RoamBadgeDeliveryMapper mapper;
    private final PlayerBadgeMapper badges;
    private final RoamMapper sessions;
    private final TransactionTemplate independent;
    public RoamBadgeDeliveryService(RoamBadgeDeliveryMapper mapper, PlayerBadgeMapper badges,
            RoamMapper sessions, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.badges = badges; this.sessions = sessions;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void prepare(Long sessionId, PlayerBadge badge) {
        if (sessionId == null || sessionId <= 0 || badge == null || badge.getMemberId() == null
                || badge.getBadgeCode() == null || badge.getSourceId() == null || badge.getBadgeName() == null)
            throw new ServiceException("原徽章交付快照无效");
        mapper.prepare(sessionId, badge);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            final Long memberId = badge.getMemberId(), sourceId = badge.getSourceId();
            final String code = badge.getBadgeCode();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { deliver(memberId, sessionId, code, sourceId); }
                    catch (RuntimeException ex) { log.warn("原漫游徽章待补交付 sessionId={}, badgeCode={}, reason={}", sessionId, code, ex.getClass().getSimpleName()); }
                }
            });
        }
    }

    public boolean deliver(Long memberId, Long sessionId, String code, Long sourceId) {
        return Boolean.TRUE.equals(independent.execute(status -> {
            sessions.lockClaimOwner(memberId); // Same member-first order as finish.
            Map<String, Object> intent = mapper.selectForUpdate(memberId, sessionId, code, sourceId);
            if (intent == null) throw new ServiceException("原徽章交付意图不存在");
            if ("DELIVERED".equals(intent.get("state"))) return true;
            if (!"DUE".equals(intent.get("state"))) throw new ServiceException("原徽章交付状态无效");
            PlayerBadge existing = badges.selectOne(memberId, code, sourceId);
            if (existing == null) {
                PlayerBadge badge = new PlayerBadge(); badge.setMemberId(memberId); badge.setBadgeCode(code);
                badge.setSourceId(sourceId); badge.setSourceType(((Number) intent.get("sourceType")).intValue());
                badge.setBadgeName((String) intent.get("badgeName")); badge.setIconUrl((String) intent.get("iconUrl"));
                Date obtained = dbDate(intent.get("obtainTime"));
                if (obtained == null) throw new ServiceException("原徽章获得时间缺失");
                badge.setObtainTime(obtained); badge.setCreateTime(obtained); badge.setDelFlag(0);
                if (badges.insertPlayerBadge(badge) != 1) throw new ServiceException("原徽章发放未确认");
            }
            if (mapper.complete(((Number) intent.get("id")).longValue(), memberId) != 1)
                throw new ServiceException("原徽章交付收据保存失败");
            return true;
        }));
    }

    public List<Map<String, Object>> readCommittedStatus(Long memberId, Long sessionId) {
        return independent.execute(status -> mapper.readStatus(memberId, sessionId));
    }

    public List<Map<String, Object>> readStatus(Long memberId, Long sessionId) { return mapper.readStatus(memberId, sessionId); }

    public void reconcile() {
        Map<String, Object> cursor = independent.execute(status -> {
            mapper.ensureCursor(); Map<String, Object> current = mapper.selectCursorForUpdate();
            if (current == null) throw new ServiceException("原徽章扫描状态缺失");
            long through = number(current.get("throughId"));
            if (through == 0) {
                Long max = mapper.highWaterMark();
                if (max != null && max > 0) { mapper.beginRound(max); return mapper.selectCursorForUpdate(); }
            }
            return current;
        });
        long after = number(cursor.get("afterId")), through = number(cursor.get("throughId"));
        if (after < 0 || through < after) throw new ServiceException("原徽章扫描游标无效");
        if (through == 0) return;
        int processed = 0;
        while (after < through && processed < 500) {
            List<Map<String, Object>> page = mapper.scanPending(after, through, Math.min(100, 500 - processed));
            if (page.isEmpty()) { mapper.completeRound(after, through); return; }
            for (Map<String, Object> row : page) {
                long id = number(row.get("id"));
                if (id <= after || id > through) throw new ServiceException("原徽章扫描游标无效");
                try { deliver(number(row.get("memberId")), number(row.get("sessionId")), (String) row.get("badgeCode"), number(row.get("sourceId"))); }
                catch (RuntimeException ex) { log.warn("原漫游徽章补交付待重试 intentId={}, reason={}", id, ex.getClass().getSimpleName()); }
                if (mapper.advanceCursor(after, id, through) != 1) return;
                after = id; processed++;
            }
        }
        if (after == through) mapper.completeRound(after, through);
    }
    private static long number(Object value) {
        if (!(value instanceof Number)) throw new ServiceException("原徽章身份缺失");
        return ((Number) value).longValue();
    }

    /** MySQL Connector/J 8 对 Map 结果里的 DATETIME 返回 LocalDateTime；按 JVM 默认时区还原为原 Date(与写入同口径)。 */
    private static Date dbDate(Object value) {
        if (value instanceof Date) return (Date) value;
        if (value instanceof java.time.LocalDateTime)
            return Date.from(((java.time.LocalDateTime) value).atZone(java.time.ZoneId.systemDefault()).toInstant());
        return null;
    }
}

package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.mapper.RoamRewardDeliveryMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;

/** Resume only original frozen drop outcomes. Finished sessions and their settlement are immutable. */
@Service
public class RoamRewardDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(RoamRewardDeliveryService.class);
    private final RoamRewardDeliveryMapper mapper;
    private final ISmsCouponHistoryService coupons;
    private final TransactionTemplate independent;
    private final IRewardDropService drops;
    private final IGrowthService growth;

    public RoamRewardDeliveryService(RoamRewardDeliveryMapper mapper, ISmsCouponHistoryService coupons,
            PlatformTransactionManager transactions, IRewardDropService drops, IGrowthService growth) {
        this.mapper = mapper; this.coupons = coupons; this.drops = drops; this.growth = growth;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Original roll/log and immutable XP snapshot commit together. Roll itself does not award XP. */
    public Map<String, Object> rollOriginalDrop(Long memberId, Long poiId, Long sessionId, int originalPoiXp) {
        return independent.execute(status -> {
            Map<String, Object> drop = drops.rollDrop(memberId, 0L, poiId, sessionId,
                    IRewardDropService.SCENE_ROAM_MERCHANT_POI, originalPoiXp);
            if (drop != null && Integer.valueOf(1).equals(drop.get("dropType"))) {
                Long id = number(drop.get("dropLogId")), amount = number(drop.get("extraXp"));
                if (id == null || id <= 0 || amount == null || amount < 0 || amount > Integer.MAX_VALUE)
                    throw new ServiceException("原探索值掉落快照无效");
                mapper.prepareXp(id, memberId, amount.intValue());
            }
            return drop;
        });
    }

    private boolean deliverOriginalXp(Long memberId, Long dropLogId) {
        return Boolean.TRUE.equals(independent.execute(status -> {
            Map<String, Object> intent = mapper.selectIntentForUpdate(dropLogId);
            if (intent == null) {
                mapper.prepareUnknownXp(dropLogId, memberId);
                return false; // Legacy log never persisted the original node XP; no amount guessing.
            }
            if (!memberId.equals(number(intent.get("memberId"))) || !"XP".equals(intent.get("rewardKind")))
                throw new ServiceException("原探索值交付身份不一致");
            if ("DELIVERED".equals(intent.get("state"))) return true;
            if ("UNKNOWN_NO_SNAPSHOT".equals(intent.get("state"))) return false;
            Long amount = number(intent.get("extraXp"));
            if (!"DUE".equals(intent.get("state")) || amount == null || amount < 0 || amount > Integer.MAX_VALUE)
                throw new ServiceException("原探索值交付快照无效");
            if (amount > 0) growth.addExp(memberId, amount.intValue(), "掉落·漫游探索值翻倍");
            if (mapper.completeIfDue(dropLogId, memberId, null, "DELIVERED", null) != 1)
                throw new ServiceException("原探索值交付收据保存失败");
            return true;
        }));
    }

    public boolean deliverOriginal(Long memberId, Long dropLogId) {
        if (memberId == null || memberId <= 0 || dropLogId == null || dropLogId <= 0)
            throw new ServiceException("原掉落身份缺失");
        Map<String, Object> original = mapper.selectOriginalRewardDrop(dropLogId, memberId);
        if (original == null) throw new ServiceException("原掉落记录不存在");
        if (Long.valueOf(1).equals(number(original.get("dropType")))) return deliverOriginalXp(memberId, dropLogId);
        if (!Long.valueOf(3).equals(number(original.get("dropType")))) throw new ServiceException("原掉落类型无效");
        JSONObject params;
        Long couponId;
        try {
            params = JSON.parseObject(String.valueOf(original.get("paramJson")));
            String raw = params == null ? "" : String.valueOf(params.get("couponTemplateId"));
            if (!raw.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            couponId = Long.valueOf(raw);
        } catch (RuntimeException ex) { throw new ServiceException("原奖励券配置无效"); }
        final Long originalCoupon = couponId;
        Map<String, Object> intent = independent.execute(status -> {
            mapper.prepare(dropLogId, memberId, originalCoupon);
            return mapper.selectIntent(dropLogId);
        });
        if (intent == null || !"COUPON".equals(intent.get("rewardKind")) || !memberId.equals(number(intent.get("memberId")))
                || !couponId.equals(number(intent.get("couponId")))) throw new ServiceException("原掉落交付身份不一致");
        if ("DELIVERED".equals(intent.get("state")) || "SKIPPED_DISABLED".equals(intent.get("state"))) return true;
        if (!"DUE".equals(intent.get("state"))) throw new ServiceException("原掉落交付状态无效");
        // Read/resume A's same-source receipt first, including a grant committed before a crash.
        // A applies the same disabled/stock guard to new issuance; C-30 remains unchanged.
        Long historyId = coupons.grantCouponBySource(memberId, couponId, "reward_drop", dropLogId);
        if (historyId == null) {
            if (!coupons.isCouponNotIssuable(couponId)) return false;
            independent.execute(status -> mapper.completeIfDue(dropLogId, memberId, originalCoupon, "SKIPPED_DISABLED", null));
            return true;
        }
        if (historyId <= 0) throw new ServiceException("原奖励券收据无效");
        independent.execute(status -> mapper.completeIfDue(dropLogId, memberId, originalCoupon, "DELIVERED", historyId));
        // Concurrent workers converge through A's same-source receipt; a failed completion is retried with that receipt.
        return true;
    }

    /** At most 500 original entries per invocation; fixed high-water cursor survives subsequent runs. */
    public void reconcile() {
        Map<String, Object> cursor = independent.execute(status -> {
            mapper.ensureCursor();
            Map<String, Object> current = mapper.selectCursorForUpdate();
            if (current == null) throw new ServiceException("原掉落扫描状态缺失");
            Long through = number(current.get("throughId"));
            if (through == null || through < 0) throw new ServiceException("原掉落扫描上界无效");
            if (through == 0) {
                Long max = mapper.highWaterMark();
                if (max == null || max <= 0) return current;
                mapper.beginRound(max);
                return mapper.selectCursorForUpdate();
            }
            return current;
        });
        Long through = number(cursor.get("throughId")), savedAfter = number(cursor.get("afterId"));
        if (through == null || savedAfter == null || through < 0 || savedAfter < 0 || savedAfter > through)
            throw new ServiceException("原掉落扫描游标无效");
        if (through == 0) return;
        long after = savedAfter;
        int processed = 0;
        while (after < through && processed < 500) {
            List<Map<String, Object>> page = mapper.scanOriginalPending(after, through, Math.min(100, 500 - processed));
            if (page.isEmpty()) { mapper.completeRound(after, through); return; }
            for (Map<String, Object> row : page) {
                Long id = number(row.get("id"));
                if (id == null || id <= after || id > through) throw new ServiceException("原掉落扫描游标无效");
                try { deliverOriginal(number(row.get("memberId")), id); }
                catch (RuntimeException ex) { log.warn("原漫游掉落补交付待重试 dropLogId={}, reason={}", id, ex.getClass().getSimpleName()); }
                // A duplicate receipt / XP row lock fences effects; CAS also fences old scanner progress.
                if (mapper.advanceCursor(after, id, through) != 1) return;
                after = id; processed++;
            }
        }
        if (after == through.longValue()) mapper.completeRound(after, through);
    }

    private static Long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }
}

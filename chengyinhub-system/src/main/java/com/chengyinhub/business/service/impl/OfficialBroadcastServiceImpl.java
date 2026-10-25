package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialBroadcastLog;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.mapper.OfficialAudienceMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastLogMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 官方广播：冻结逐成员/通道意图，站内信与回执同事务，微信 UNKNOWN 不自动重投。
 *
 * @author cy
 * @date 2026-07-08
 */
@Service
public class OfficialBroadcastServiceImpl implements IOfficialBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(OfficialBroadcastServiceImpl.class);

    /** 频控:单个 member 在窗口内最多收到的官方站内通知条数,超则本条跳过该用户防轰炸。 */
    private static final int FREQ_WINDOW_MIN = 60;
    private static final int FREQ_MAX = 5;

    @Autowired
    private OfficialBroadcastMapper broadcastMapper;
    @Autowired
    private OfficialBroadcastLogMapper logMapper;
    @Autowired
    private OfficialAudienceMapper audienceMapper;
    @Autowired
    private UmsMemberMapper memberMapper;
    @Autowired
    private IImService imService;
    @Autowired
    private IWxSubscribeMsgService wxSubscribeMsgService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Value("${wx.subscribe.tplOfficialEvent:}")
    private String officialTemplate;
    @Autowired
    private OfficialEventMapper eventMapper;
    @Autowired
    @Lazy
    private IOfficialEventV2Service officialEventV2Service;

    @Override
    public OfficialBroadcast publish(OfficialBroadcast bc, Long actorMemberId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (bc == null || StringUtils.isEmpty(bc.getTitle())) {
            throw new ServiceException("通知标题不能为空");
        }
        if (StringUtils.isEmpty(bc.getAudience())) {
            throw new ServiceException("请选择通知对象(玩家/俱乐部/商家)");
        }
        if (StringUtils.isEmpty(bc.getChannels())) bc.setChannels("inapp");
        if (bc.getCopyMode() == null) bc.setCopyMode(OfficialBroadcast.MODE_UNIFIED);
        if (requireV2EventScope(bc.getEventId(), actorMemberId)) {
            bc.setSenderAccount(actorMemberId);
        }
        validateChannels(bc.getChannels());
        bc.setCreateTime(new Date());
        bc.setStatus(OfficialBroadcast.ST_PENDING);
        bc.setReachedCount(0);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (broadcastMapper.insertOfficialBroadcast(bc) != 1 || bc.getId() == null)
                throw new ServiceException("通知保存失败");
        });

        boolean immediate = bc.getScheduledTime() == null
                || bc.getScheduledTime().getTime() <= System.currentTimeMillis();
        if (immediate) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // Only wake after the caller's broadcast insert commits; recovery also scans immediate rows.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { wakeDispatch(bc); }
                });
            } else {
                wakeDispatch(bc);
            }
        }
        return bc;
    }

    private void wakeDispatch(OfficialBroadcast bc) {
        try {
            dispatch(bc);
        } catch (RuntimeException failure) {
            // Publication is already committed. Report accepted/pending, not a rejection with side effects.
            log.warn("[官方通知] 已提交待恢复 bc={} type={}", bc.getId(), failure.getClass().getSimpleName());
        }
    }

    int dispatch(OfficialBroadcast bc) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (bc == null || bc.getId() == null) return 0;
        // The remote call must never inherit a caller's transaction (including afterCommit resources).
        TransactionTemplate outside = new TransactionTemplate(transactionManager);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        Integer reached = outside.execute(status -> dispatchOutsideTransaction(bc));
        return reached == null ? 0 : reached;
    }

    private int dispatchOutsideTransaction(OfficialBroadcast bc) {
        List<OfficialBroadcastLog> deliveries = independent().execute(status -> prepare(bc.getId()));
        Long lastMember = null;
        boolean capped = false;
        for (OfficialBroadcastLog delivery : safeDeliveries(deliveries)) {
            if (!delivery.getMemberId().equals(lastMember)) {
                lastMember = delivery.getMemberId();
                capped = freqCapped(lastMember);
            }
            // Advisory precheck preserves the existing channel policy; inapp enforces the cap under the member lock.
            if (capped) continue; // Keep the frozen intent; no fake success or deletion.
            if ("inapp".equals(delivery.getChannel())) deliverInapp(delivery);
            if ("subscribe".equals(delivery.getChannel())) deliverSubscribe(delivery);
        }
        OfficialBroadcast result = independent().execute(status -> {
            OfficialBroadcast current = logMapper.lockBroadcast(bc.getId());
            if (current == null) throw new ServiceException("通知不存在");
            int reached = logMapper.countInappReached(bc.getId());
            OfficialBroadcast patch = new OfficialBroadcast();
            patch.setId(bc.getId());
            patch.setReachedCount(reached);
            // Revoked/draft broadcasts must not be revived by an old worker.
            patch.setStatus(current.getStatus());
            if (Integer.valueOf(OfficialBroadcast.ST_PENDING).equals(current.getStatus())
                    && logMapper.countUnsuccessful(bc.getId()) == 0) {
                patch.setStatus(OfficialBroadcast.ST_SENT);
            }
            if (broadcastMapper.updateOfficialBroadcast(patch) != 1)
                throw new ServiceException("通知统计保存失败");
            return patch;
        });
        bc.setReachedCount(result.getReachedCount());
        bc.setStatus(result.getStatus());
        return result.getReachedCount();
    }

    /** Serialize the first audience snapshot. Retrying uses these rows, never a new audience/copy. */
    private List<OfficialBroadcastLog> prepare(Long broadcastId) {
        OfficialBroadcast bc = logMapper.lockBroadcast(broadcastId);
        if (bc == null || !Integer.valueOf(OfficialBroadcast.ST_PENDING).equals(bc.getStatus()))
            return java.util.Collections.emptyList();
        List<OfficialBroadcastLog> existing = logMapper.selectByBroadcast(broadcastId);
        if (!existing.isEmpty()) return existing;
        validateChannels(bc.getChannels());
        Map<Long, String> targets = new LinkedHashMap<>();
        String audience = bc.getAudience() == null ? "" : bc.getAudience();
        if (audience.contains("player"))
            for (Long id : safe(audienceMapper.selectPlayerIds(bc.getCity()))) targets.putIfAbsent(id, "player");
        if (audience.contains("club"))
            for (Long id : safe(audienceMapper.selectClubMemberIds(bc.getCity()))) targets.putIfAbsent(id, "club");
        if (audience.contains("merchant"))
            for (Long id : safe(audienceMapper.selectMerchantMemberIds(bc.getCity()))) targets.putIfAbsent(id, "merchant");
        List<OfficialBroadcastLog> result = new ArrayList<>();
        JSONObject content = parse(bc.getContentJson());
        int mode = bc.getCopyMode() == null ? OfficialBroadcast.MODE_UNIFIED : bc.getCopyMode();
        for (Map.Entry<Long, String> target : targets.entrySet()) {
            if (target.getKey() == null || target.getKey() <= 0) continue;
            String[] copy = resolveTitleSub(mode, content, target.getValue(), bc.getTitle());
            for (String channel : bc.getChannels().split(",")) {
                OfficialBroadcastLog delivery = new OfficialBroadcastLog();
                delivery.setBroadcastId(broadcastId);
                delivery.setMemberId(target.getKey());
                delivery.setRole(target.getValue());
                delivery.setChannel(channel.trim());
                delivery.setDeliveryState(OfficialBroadcastLog.PENDING);
                delivery.setDeliveryTitle(copy[0]);
                delivery.setDeliverySub(copy[1]);
                if (logMapper.insertPending(delivery) != 1)
                    throw new ServiceException("通知投递意图保存失败");
                result.add(delivery);
            }
        }
        // Empty audiences are terminal too, so later recovery cannot expand them.
        if (result.isEmpty()) {
            OfficialBroadcast patch = new OfficialBroadcast();
            patch.setId(broadcastId);
            patch.setStatus(OfficialBroadcast.ST_SENT);
            patch.setReachedCount(0);
            if (broadcastMapper.updateOfficialBroadcast(patch) != 1)
                throw new ServiceException("通知状态保存失败");
        }
        return result;
    }

    private void deliverInapp(OfficialBroadcastLog intent) {
        try {
            independent().executeWithoutResult(status -> {
                // All broadcasts share this stable member mutex. Always acquire it BEFORE a delivery/IM lock.
                Long memberId = intent.getMemberId();
                if (memberId == null || !memberId.equals(memberMapper.lockMemberById(memberId))) return;
                OfficialBroadcastLog row = logMapper.lockDelivery(intent.getId());
                if (row == null || !(OfficialBroadcastLog.PENDING.equals(row.getDeliveryState())
                        || OfficialBroadcastLog.FAILED.equals(row.getDeliveryState()))) return;
                if (!memberId.equals(row.getMemberId())) throw new ServiceException("通知接收人不一致");
                // First consistent read in this fresh transaction: observe the previous holder's committed receipt.
                // Hold the member fence through IM insertion and SUCCESS commit; capped intents remain retryable.
                if (freqCapped(memberId)) return;
                JSONObject extra = new JSONObject();
                extra.put("title", row.getDeliveryTitle());
                extra.put("sub", row.getDeliverySub());
                extra.put("bcId", row.getBroadcastId());
                Long messageId = imService.sendSystem(row.getMemberId(), ImMessage.MSG_CARD,
                        row.getDeliveryTitle(), extra.toJSONString());
                if (messageId == null || messageId <= 0) throw new ServiceException("站内信未返回回执");
                // Same DB transaction as sendSystem: no message can commit without its terminal receipt.
                transition(row, OfficialBroadcastLog.SUCCESS, null, messageId);
            });
        } catch (RuntimeException failure) {
            log.warn("[官方通知] 站内信事务未确认 bc={} delivery={} type={}",
                    intent.getBroadcastId(), intent.getId(), failure.getClass().getSimpleName());
            independent().executeWithoutResult(status -> {
                OfficialBroadcastLog row = logMapper.lockDelivery(intent.getId());
                // A lost commit response may already have left SUCCESS. Never overwrite it.
                if (row != null && OfficialBroadcastLog.PENDING.equals(row.getDeliveryState()))
                    transition(row, OfficialBroadcastLog.FAILED, "LOCAL_TRANSACTION_FAILED", null);
            });
        }
    }

    private void deliverSubscribe(OfficialBroadcastLog intent) {
        Boolean claimed = independent().execute(status -> {
            OfficialBroadcastLog row = logMapper.lockDelivery(intent.getId());
            if (row == null || !(OfficialBroadcastLog.PENDING.equals(row.getDeliveryState())
                    || (OfficialBroadcastLog.FAILED.equals(row.getDeliveryState())
                        && "TEMPLATE_MISSING".equals(row.getFailureCode())))) return false;
            if (StringUtils.isBlank(officialTemplate)) {
                transition(row, OfficialBroadcastLog.FAILED, "TEMPLATE_MISSING", null);
                return false;
            }
            // Commit before the remote call. Process death or timeout leaves UNKNOWN, never a retry lease.
            transition(row, OfficialBroadcastLog.UNKNOWN, "OUTCOME_UNCONFIRMED", null);
            return true;
        });
        if (!Boolean.TRUE.equals(claimed)) return;
        boolean delivered;
        try {
            // This is the WX-only contract. INotifyService fans out and cannot prove WX acceptance.
            delivered = wxSubscribeMsgService.notifyOfficialBroadcast(intent.getMemberId(),
                    intent.getDeliveryTitle(), intent.getDeliverySub());
        } catch (RuntimeException failure) {
            log.warn("[官方通知] 微信结果未知 bc={} delivery={} type={}",
                    intent.getBroadcastId(), intent.getId(), failure.getClass().getSimpleName());
            return;
        }
        if (!delivered) return; // false also hides network failures; it is NOT a safe-retry signal.
        independent().executeWithoutResult(status -> {
            OfficialBroadcastLog row = logMapper.lockDelivery(intent.getId());
            if (row != null && OfficialBroadcastLog.UNKNOWN.equals(row.getDeliveryState()))
                transition(row, OfficialBroadcastLog.SUCCESS, null, null);
        });
    }

    private void transition(OfficialBroadcastLog row, String state, String failureCode, Long messageId) {
        if (logMapper.transition(row.getId(), row.getDeliveryState(), state, failureCode, messageId) != 1)
            throw new ServiceException("通知投递状态冲突");
    }

    private TransactionTemplate independent() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private void validateChannels(String channels) {
        if (StringUtils.isBlank(channels)) throw new ServiceException("请选择通知通道");
        java.util.Set<String> selected = new java.util.HashSet<>();
        for (String channel : channels.split(",", -1)) {
            String value = channel.trim();
            if (!("inapp".equals(value) || "subscribe".equals(value)) || !selected.add(value))
                throw new ServiceException("通知通道无效或重复");
        }
    }

    private List<OfficialBroadcastLog> safeDeliveries(List<OfficialBroadcastLog> rows) {
        return rows == null ? java.util.Collections.emptyList() : rows;
    }

    @Override
    public List<OfficialBroadcast> listBySender(Long senderAccount, Long actorMemberId) {
        if (senderAccount == null || actorMemberId == null || !senderAccount.equals(actorMemberId)) {
            throw new ServiceException("无权查看该通知数据");
        }
        List<OfficialBroadcast> broadcasts = broadcastMapper.selectBySender(senderAccount);
        if (broadcasts == null) return java.util.Collections.emptyList();
        for (OfficialBroadcast broadcast : broadcasts) {
            // ★读路径用只读闸(2026-09-17 拍板第18条 / R9-24 核实补缺):
            //   活动结束后主办/协办 party 归档为 COMPLETED,操作闸会把「我发布的」整页打挂;
            //   历史参与身份按只读放行,撤权/非参与者仍拒(不放宽)。
            requireReadableV2EventScope(broadcast == null ? null : broadcast.getEventId(), actorMemberId);
        }
        return broadcasts;
    }

    @Override
    public void runDueScheduled() {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return;
        List<OfficialBroadcast> due = logMapper.selectRecoverableBroadcasts();
        if (due == null) return;
        for (OfficialBroadcast bc : due) {
            try {
                dispatch(bc);
            } catch (Exception ex) {
                log.warn("[官方通知] 定时分发失败 bc={}", bc.getId(), ex);
            }
        }
    }

    @Override
    public java.util.Map<String, Object> reachStats(Long broadcastId, Long actorMemberId) {
        getById(broadcastId, actorMemberId);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        // reached/clicked are inapp members; channelReached counts successful channel receipts.
        m.put("reached", logMapper.countInappReached(broadcastId));
        m.put("channelReached", logMapper.countReached(broadcastId));
        m.put("byChannel", logMapper.channelStats(broadcastId));
        m.put("clicked", logMapper.countClicked(broadcastId));
        return m;
    }

    @Override
    public OfficialBroadcast getById(Long id, Long actorMemberId) {
        OfficialBroadcast broadcast = broadcastMapper.selectOfficialBroadcastById(id);
        // 取单条是纯读口(仅被 stats 复盘/详情消费),与 listBySender 同闸:活动结束后
        // COMPLETED 原主办按历史参与身份只读放行,撤权/非参与者仍拒(不放宽)。
        if (broadcast != null) requireReadableV2EventScope(broadcast.getEventId(), actorMemberId);
        return broadcast;
    }

    @Override
    public void markClicked(Long broadcastId, Long memberId, String channel) {
        try {
            logMapper.markClicked(broadcastId, memberId, StringUtils.isEmpty(channel) ? "inapp" : channel);
        } catch (Exception ignore) {
        }
    }

    /** 返回 true 表示 V2，且已在这里完成当前操作者与活动归属校验。 */
    private boolean requireV2EventScope(Long eventId, Long actorMemberId) {
        if (eventId == null) return false;
        if (eventMapper == null) throw new ServiceException("无法确认通知关联活动");
        OfficialEvent event = eventMapper.selectOfficialEventById(eventId);
        if (event == null) throw new ServiceException("活动不存在");
        if (event.getContractVersion() == null || event.getContractVersion() < 2) return false;
        if (actorMemberId == null || officialEventV2Service == null
                || !officialEventV2Service.canOperateControlledEvent(eventId, actorMemberId)) {
            throw new ServiceException("无权操作该活动通知");
        }
        return true;
    }

    /**
     * 读路径作用域(2026-09-17 拍板第18条 / R9-24 核实补缺):V2 活动结束后主办/协办归档为 COMPLETED,
     * 操作闸会把「我发布的(活动+通知)」整页拒掉;读历史走只读闸,按当时参与身份放行。
     * 与 {@link #requireV2EventScope} 同一套校验顺序,只有闸不同 —— 撤权/非参与者仍拒。
     */
    private boolean requireReadableV2EventScope(Long eventId, Long actorMemberId) {
        if (eventId == null) return false;
        if (eventMapper == null) throw new ServiceException("无法确认通知关联活动");
        OfficialEvent event = eventMapper.selectOfficialEventById(eventId);
        if (event == null) throw new ServiceException("活动不存在");
        if (event.getContractVersion() == null || event.getContractVersion() < 2) return false;
        if (actorMemberId == null || officialEventV2Service == null
                || !officialEventV2Service.canReadControlledEvent(eventId, actorMemberId)) {
            throw new ServiceException("无权查看该活动通知");
        }
        return true;
    }

    /** 频控:该 member 近窗口内官方站内通知数是否已达上限。查询异常时 fallback 禁止发送(防轰炸)。 */
    private boolean freqCapped(Long memberId) {
        try {
            return logMapper.countRecentByMember(memberId, FREQ_WINDOW_MIN) >= FREQ_MAX;
        } catch (Exception e) {
            log.warn("[官方通知] 频控查询失败,禁止发送(防轰炸) member={} errorType={}", memberId, e.getClass().getSimpleName(), e);
            return true;
        }
    }

    /** copyMode 解析文案:1一起发(统一)/2分开发(按角色取)。返回 [title, sub]。 */
    private String[] resolveTitleSub(int copyMode, JSONObject content, String role, String fallbackTitle) {
        String title = fallbackTitle, sub = "";
        if (content == null) return new String[]{title, sub};
        if (copyMode == OfficialBroadcast.MODE_PER_ROLE) {
            JSONObject r = content.getJSONObject(role);
            if (r != null) {
                if (r.containsKey("title")) title = r.getString("title");
                sub = r.getString("sub") == null ? "" : r.getString("sub");
            }
        } else {
            if (content.containsKey("title")) title = content.getString("title");
            sub = content.getString("sub") == null ? "" : content.getString("sub");
        }
        return new String[]{title == null ? fallbackTitle : title, sub == null ? "" : sub};
    }

    private JSONObject parse(String json) {
        if (StringUtils.isEmpty(json)) return null;
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> safe(List<Long> list) {
        return list == null ? java.util.Collections.emptyList() : list;
    }

}

package com.chengyinhub.business.service.impl;

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
import com.chengyinhub.business.notify.NotifyResult;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 官方通知/广播 实现。best-effort:单个 member 发送失败不影响整体。
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
    private NotificationDeliveryReceiptService receiptService;
    @Autowired
    private OfficialAudienceMapper audienceMapper;
    @Autowired
    private INotifyService notifyService;
    @Autowired
    private OfficialEventMapper eventMapper;
    @Autowired
    @Lazy
    private IOfficialEventV2Service officialEventV2Service;

    @Override
    public OfficialBroadcast publish(OfficialBroadcast bc, Long actorMemberId) {
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
        bc.setCreateTime(new Date());
        bc.setStatus(OfficialBroadcast.ST_PENDING);
        broadcastMapper.insertOfficialBroadcast(bc);

        boolean immediate = bc.getScheduledTime() == null
                || bc.getScheduledTime().getTime() <= System.currentTimeMillis();
        if (immediate) {
            dispatch(bc);
        }
        return bc;
    }

    int dispatch(OfficialBroadcast bc) {
        if (bc == null || bc.getId() == null) return 0;
        String channels = bc.getChannels() == null ? "" : bc.getChannels();
        boolean inapp = channels.contains("inapp");
        boolean subscribe = channels.contains("subscribe");
        int copyMode = bc.getCopyMode() == null ? OfficialBroadcast.MODE_UNIFIED : bc.getCopyMode();
        JSONObject content = parse(bc.getContentJson());

        // 圈人:角色×城市;memberId 去重(角色互斥,首命中角色为准)
        Map<Long, String> targets = new LinkedHashMap<>();
        String aud = bc.getAudience() == null ? "" : bc.getAudience();
        if (aud.contains("player")) {
            for (Long id : safe(audienceMapper.selectPlayerIds(bc.getCity()))) targets.putIfAbsent(id, "player");
        }
        if (aud.contains("club")) {
            for (Long id : safe(audienceMapper.selectClubMemberIds(bc.getCity()))) targets.putIfAbsent(id, "club");
        }
        if (aud.contains("merchant")) {
            for (Long id : safe(audienceMapper.selectMerchantMemberIds(bc.getCity()))) targets.putIfAbsent(id, "merchant");
        }

        int reached = 0, capped = 0;
        for (Map.Entry<Long, String> e : targets.entrySet()) {
            Long memberId = e.getKey();
            String role = e.getValue();
            if (memberId == null || memberId <= 0) continue;
            // 频控:近 FREQ_WINDOW_MIN 分钟已收 >= FREQ_MAX 条官方通知 → 跳过该用户防轰炸
            if (freqCapped(memberId)) { capped++; continue; }
            String[] ts = resolveTitleSub(copyMode, content, role, bc.getTitle());
            String title = ts[0], sub = ts[1];

            if (inapp && insertLog(bc.getId(), memberId, role, "inapp")) {
                try {
                    String extra = "{\"title\":\"" + esc(title) + "\",\"sub\":\"" + esc(sub)
                            + "\",\"bcId\":" + bc.getId() + "}";
                    if (receiptService.storeInApp("OFFICIAL_BROADCAST", bc.getId(), memberId,
                            ImMessage.MSG_CARD, title, extra)
                            || receiptService.confirmed("OFFICIAL_BROADCAST", bc.getId(), memberId, "IN_APP")) {
                        logMapper.markReached(bc.getId(), memberId, "inapp");
                        reached++;
                    }
                } catch (Throwable ex) {
                    log.warn("[官方通知] 站内落库失败 member={} reason={}",
                            memberId, ex.getClass().getSimpleName());
                }
            }
            // 订阅消息(强触达,best-effort;未授权/未配模板跳过)
            if (subscribe && insertLog(bc.getId(), memberId, role, "subscribe")) {
                if (!claimReceipt("OFFICIAL_BROADCAST", bc.getId(), memberId, "WECHAT_SUBSCRIBE")) {
                    try {
                        if (receiptService.confirmed("OFFICIAL_BROADCAST", bc.getId(), memberId, "WECHAT_SUBSCRIBE")) {
                            logMapper.markReached(bc.getId(), memberId, "subscribe");
                        }
                    } catch (Throwable ex) {
                        log.warn("[官方通知] 收据回读失败 member={} reason={}", memberId, ex.getClass().getSimpleName());
                    }
                    continue;
                }
                try {
                    NotifyResult result = notifyService.notifyOfficialBroadcastResult(memberId, title, sub);
                    String status = result == null ? NotifyResult.Status.UNKNOWN.name() : result.getStatus().name();
                    String code = result == null ? null : result.getProviderCode();
                    boolean recorded = completeReceipt("OFFICIAL_BROADCAST", bc.getId(), memberId,
                            "WECHAT_SUBSCRIBE", status, code);
                    if (recorded && result != null && result.getStatus() == NotifyResult.Status.CHANNEL_ACCEPTED
                            && "WECHAT_SUBSCRIBE".equals(result.getChannel())) {
                        logMapper.markReached(bc.getId(), memberId, "subscribe");
                    }
                } catch (Throwable ex) {
                    // 越过外部边界后的 Throwable 不能推断失败；留 UNKNOWN，且不穿透主流程。
                    completeReceipt("OFFICIAL_BROADCAST", bc.getId(), memberId,
                            "WECHAT_SUBSCRIBE", NotifyResult.Status.UNKNOWN.name(), null);
                    log.warn("[官方通知] 订阅消息失败(未置达) member={} reason={}", memberId, ex.getClass().getSimpleName());
                }
            }
        }
        bc.setReachedCount(reached);
        bc.setStatus(OfficialBroadcast.ST_SENT);
        broadcastMapper.updateOfficialBroadcast(bc);
        log.info("[官方通知] bc={} 圈人={} 站内触达={} 频控跳过={}", bc.getId(), targets.size(), reached, capped);
        return reached;
    }

    private boolean claimReceipt(String eventType, Long eventId, Long memberId, String channel) {
        try {
            return receiptService.claim(eventType, eventId, memberId, channel, false);
        } catch (Throwable ex) {
            log.warn("[官方通知] 收据领取失败 event={} member={} channel={} reason={}",
                    eventId, memberId, channel, ex.getClass().getSimpleName());
            return false;
        }
    }

    private boolean completeReceipt(String eventType, Long eventId, Long memberId,
                                    String channel, String status, String providerCode) {
        try {
            return receiptService.complete(eventType, eventId, memberId, channel, status, providerCode);
        } catch (Throwable ex) {
            // 外部成功但本地落库失败时原 PROCESSING 是 UNKNOWN，不可补写已达或盲重发。
            log.error("[官方通知] 收据结果落库失败 event={} member={} channel={} status={} reason={}",
                    eventId, memberId, channel, status, ex.getClass().getSimpleName());
            return false;
        }
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
        List<OfficialBroadcast> due = broadcastMapper.selectDueScheduled();
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
        m.put("reached", logMapper.countReached(broadcastId));
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

    /**
     * insert ignore 保留尝试日志；发送身份由收据 CAS 判断。
     * 落 reached=0 的尝试行;真正发送成功后由 markReached 置 1,避免"发送失败仍计触达"。
     */
    private boolean insertLog(Long bcId, Long memberId, String role, String channel) {
        OfficialBroadcastLog l = new OfficialBroadcastLog();
        l.setBroadcastId(bcId);
        l.setMemberId(memberId);
        l.setRole(role);
        l.setChannel(channel);
        l.setReached(0);
        try {
            logMapper.insertIgnore(l);
            return true; // 收据 CAS 是唯一发送闸门，日志占位不能阻断恢复。
        } catch (Throwable ex) {
            return false;
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

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}

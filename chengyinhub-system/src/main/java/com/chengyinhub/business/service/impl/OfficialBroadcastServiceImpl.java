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
import com.chengyinhub.business.service.IImService;
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
    private OfficialAudienceMapper audienceMapper;
    @Autowired
    private IImService imService;
    @Autowired
    private INotifyService notifyService;
    @Autowired
    private OfficialBroadcastDeliveryService delivery;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private OfficialEventMapper eventMapper;
    @Autowired
    @Lazy
    private IOfficialEventV2Service officialEventV2Service;

    @Override
    public OfficialBroadcast publish(OfficialBroadcast bc, Long actorMemberId) {
        if(bc==null || bc.getClientRequestId()==null)return publishOnce(bc,actorMemberId);
        requireCreationKey(bc.getRequestOwner(),bc.getClientRequestId());
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            broadcastMapper.reserveCreation(bc.getRequestOwner(),bc.getClientRequestId());
            Map<String,Object> row=broadcastMapper.lockCreation(bc.getRequestOwner(),bc.getClientRequestId());
            if("CANCELLED".equals(row.get("state")))throw new ServiceException("原广播创建请求已终止");
            OfficialBroadcast saved=publishOnce(bc,actorMemberId);
            if("PENDING".equals(row.get("state")))broadcastMapper.finishCreation(bc.getRequestOwner(),bc.getClientRequestId(),"APPLIED",saved.getId());
            return saved;
        });
    }

    private OfficialBroadcast publishOnce(OfficialBroadcast bc, Long actorMemberId) {
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
        if(bc.getClientRequestId()!=null) {
            if(!bc.getClientRequestId().matches("[a-f0-9]{32}") || bc.getRequestOwner()==null)throw new ServiceException("广播原请求身份无效");
            String frozen=JSON.toJSONString(java.util.Arrays.asList(bc.getEventId(),bc.getTitle(),bc.getAudience(),bc.getCopyMode(),bc.getContentJson(),bc.getChannels(),bc.getCity(),bc.getScheduledTime()));
            try{bc.setRequestHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen.getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            OfficialBroadcast previous=broadcastMapper.findRequest(bc.getRequestOwner(),bc.getClientRequestId());
            if(previous!=null)return sameCreation(previous,bc);
        }
        if(bc.getScheduledTime()==null)bc.setScheduledTime(new Date());
        bc.setCreateTime(new Date());
        bc.setStatus(OfficialBroadcast.ST_PENDING);
        try { broadcastMapper.insertOfficialBroadcast(bc); }
        catch(org.springframework.dao.DuplicateKeyException duplicate) {
            if(bc.getClientRequestId()==null)throw duplicate;
            OfficialBroadcast previous=broadcastMapper.findRequest(bc.getRequestOwner(),bc.getClientRequestId());
            if(previous==null)throw duplicate;return sameCreation(previous,bc);
        }

        boolean immediate = bc.getScheduledTime() == null
                || bc.getScheduledTime().getTime() <= System.currentTimeMillis();
        if (immediate) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { try { dispatch(bc); } catch (RuntimeException failure) { log.warn("官方广播待恢复 bc={}",bc.getId()); } }
                });
            } else dispatch(bc);
        }
        return bc;
    }

    private OfficialBroadcast sameCreation(OfficialBroadcast previous,OfficialBroadcast input){
        if(!java.util.Objects.equals(previous.getRequestHash(),input.getRequestHash()))throw new ServiceException("原广播请求号与内容不一致");
        return previous;
    }
    private void requireCreationKey(String owner,String key){
        if(owner==null || key==null || !key.matches("[a-f0-9]{32}"))throw new ServiceException("原请求身份无效");
    }
    @Override public Map<String,Object> creationReceipt(String owner,String key){
        requireCreationKey(owner,key);Map<String,Object> row=broadcastMapper.readCreation(owner,key);
        Map<String,Object> out=new LinkedHashMap<>();out.put("requestId",key);
        out.put("state",row==null || "PENDING".equals(row.get("state"))?"UNKNOWN":row.get("state"));
        if(row!=null && row.get("broadcast_id")!=null)out.put("broadcastId",String.valueOf(row.get("broadcast_id")));return out;
    }
    @Override public Map<String,Object> cancelCreation(String owner,String key){
        requireCreationKey(owner,key);
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            broadcastMapper.reserveCreation(owner,key);Map<String,Object> row=broadcastMapper.lockCreation(owner,key);
            if("PENDING".equals(row.get("state")))broadcastMapper.finishCreation(owner,key,"CANCELLED",null);
            return creationReceipt(owner,key);
        });
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

        List<Map<String,Object>> frozen = new java.util.ArrayList<>();
        for (Map.Entry<Long,String> entry : targets.entrySet()) {
            Long member=entry.getKey();if(member==null || member<=0)continue;
            String[] text=resolveTitleSub(copyMode,content,entry.getValue(),bc.getTitle());
            boolean capped=freqCapped(member);
            for(String channel : List.of("inapp","subscribe")) {
                if (!(channel.equals("inapp")?inapp:subscribe)) continue;
                Map<String,Object> row=new LinkedHashMap<>();row.put("member",member);row.put("role",entry.getValue());row.put("channel",channel);
                row.put("title",text[0]);row.put("sub",text[1]);row.put("state",capped?"CAPPED":"READY");frozen.add(row);
            }
        }
        delivery.prepare(bc,frozen);
        delivery.resume(bc);
        return refreshDelivery(bc);
    }

    private int refreshDelivery(OfficialBroadcast bc) {return delivery.finish(bc);}

    @Override
    public Map<String,Object> recover(Long id,Long actorMemberId) {return recoverOwned(id,actorMemberId,"MEMBER:"+actorMemberId);}
    @Override public Map<String,Object> recoverOwned(Long id,Long actorMemberId,String owner) {
        OfficialBroadcast bc=getById(id,actorMemberId);
        if(bc==null)throw new ServiceException("通知不存在");
        if(!java.util.Objects.equals(owner,bc.getRequestOwner()) && (actorMemberId==null || !actorMemberId.equals(bc.getSenderAccount())))throw new ServiceException("仅原发布者可恢复通知");
        requireV2EventScope(bc.getEventId(),actorMemberId);
        if(bc.getScheduledTime()!=null && bc.getScheduledTime().after(new Date()))throw new ServiceException("尚未到广播计划时间");
        dispatch(bc);return delivery.summary(id);
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
        return delivery.summary(broadcastId);
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

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}

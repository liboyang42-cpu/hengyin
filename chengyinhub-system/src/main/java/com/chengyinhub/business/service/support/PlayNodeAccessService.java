package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Date;
import java.util.List;

/** Shared current-node admission. Transaction boundaries remain with each original caller. */
@Service
public class PlayNodeAccessService {
    private static final Logger logger = LoggerFactory.getLogger(PlayNodeAccessService.class);
    private final ICmsActivityService cmsActivityService;
    private final CmsActivityMapper currentPlayActivityMapper;
    private final CmsRegistrationMapper cmsRegistrationMapper;
    private final ICmsTopicNodeService cmsTopicNodeService;
    private final IPlayerNodeProgressService playerNodeProgressService;
    private final ICmsTopicService cmsTopicService;
    private final IOmsTicketService omsTicketService;
    private final ClubLeadMapper clubLeadMapper;
    private final IGameSessionRuntimeService gameSessionRuntimeService;
    private final ICmsTopicChapterService cmsTopicChapterService;
    private final ITopicRouteRuntimeService topicRouteRuntimeService;

    public PlayNodeAccessService(ICmsActivityService activities, CmsActivityMapper currentActivities,
            CmsRegistrationMapper registrations, ICmsTopicNodeService nodes, IPlayerNodeProgressService progress,
            ICmsTopicService topics, IOmsTicketService tickets, ClubLeadMapper leads,
            IGameSessionRuntimeService gameRuntime, ICmsTopicChapterService chapters, ITopicRouteRuntimeService routes) {
        this.cmsActivityService=activities; this.currentPlayActivityMapper=currentActivities;
        this.cmsRegistrationMapper=registrations; this.cmsTopicNodeService=nodes;
        this.playerNodeProgressService=progress; this.cmsTopicService=topics; this.omsTicketService=tickets;
        this.clubLeadMapper=leads; this.gameSessionRuntimeService=gameRuntime;
        this.cmsTopicChapterService=chapters; this.topicRouteRuntimeService=routes;
    }

    public record NodeScope(Long nodeId, Long topicId, Long chapterId, Long sortId, Long status, Long unlockAfterNodeId) {}
    public record ActivityWindow(Long startEpochMillis, Long endEpochMillis) {}
    public record ProgressFact(Long id, Integer status, String picUrl) {}
    public record Failure(int originalAjaxCode, String message) {}
    public record Context(Long activityId, Long topicId, boolean selfPlay, boolean nightWarning, boolean continuing,
                          Long registrationId, Integer purchaseKind, Failure failure) {}

    public Context resolveContext(Long actor, String activityId, String topicId, NodeScope node) {
        return context(resolveCtx(actor,activityId,topicId,nodeEntity(node)));
    }
    public Context resolveContext(Long actor, String activityId, String topicId, NodeScope node, Long registrationId) {
        return context(resolveCtx(actor,activityId,topicId,nodeEntity(node),registrationId));
    }
    public String activityTimeError(ActivityWindow window) {
        CmsActivity activity=null;
        if(window!=null) {
            activity=new CmsActivity();
            activity.setStartDate(window.startEpochMillis()==null?null:new Date(window.startEpochMillis()));
            activity.setEndDate(window.endEpochMillis()==null?null:new Date(window.endEpochMillis()));
        }
        return activityTimeError(activity);
    }
    public String leadGateError(Long activityId,NodeScope node) { return leadGateError(activityId,nodeEntity(node)); }
    public String routeAccessError(Long actor,Long activityId,Long topicId,NodeScope node,String originalActionId) {
        return routeAccessError(actor,activityId,topicId,nodeEntity(node),originalActionId);
    }
    public boolean isDelistedNode(NodeScope node) { return isDelistedNode(nodeEntity(node)); }
    public boolean hasCompletedNodeFact(Long actor,String activityId,NodeScope node) { return hasCompletedNodeFact(actor,activityId,nodeEntity(node)); }
    public boolean hasCompletedNodeFact(Long actor,Long activityId,NodeScope node) { return hasCompletedNodeFact(actor,activityId,nodeEntity(node)); }
    public ProgressFact findNodeProgressRow(Long actor,Long activityId,Long topicId,Long nodeId) {
        PlayerNodeProgress row=findNodeProgressEntity(actor,activityId,topicId,nodeId);
        return row==null?null:new ProgressFact(row.getId(),row.getStatus(),row.getPicUrl());
    }
    public ProgressFact findNodeProgressRow(Long actor,Long activityId,Long topicId,Long nodeId,String originalPlayInstance) {
        if (StringUtils.isBlank(originalPlayInstance)) throw new IllegalArgumentException("原局身份缺失");
        PlayerNodeProgress probe=new PlayerNodeProgress();
        probe.setMemberId(actor); probe.setActivityId(activityId); probe.setTopicId(topicId);
        probe.setNodeId(nodeId); probe.setPlayInstanceId(originalPlayInstance);
        List<PlayerNodeProgress> rows=playerNodeProgressService.selectPlayerNodeProgressList(probe);
        PlayerNodeProgress row=rows==null || rows.isEmpty()?null:rows.get(0);
        return row==null?null:new ProgressFact(row.getId(),row.getStatus(),row.getPicUrl());
    }
    public boolean isPrevDone(Long actor,Long activityId,NodeScope node) { return isPrevDone(actor,activityId,nodeEntity(node)); }
    public String unlockGateError(Long actor,Long activityId,NodeScope node) { return unlockGateError(actor,activityId,nodeEntity(node)); }

    private static CmsTopicNode nodeEntity(NodeScope view) {
        if(view==null)return null;
        CmsTopicNode node=new CmsTopicNode(); node.setId(view.nodeId()); node.setTopicId(view.topicId());
        node.setChapterId(view.chapterId()); node.setSortId(view.sortId()); node.setStatus(view.status());
        node.setUnlockAfterNodeId(view.unlockAfterNodeId()); return node;
    }
    private static Context context(PlayCtx c) {
        return new Context(c.actId,c.topicId,c.selfPlay,c.nightWarning,c.continuing,
                c.registration==null?null:c.registration.getId(),c.registration==null?null:c.registration.getPurchaseKind(),c.error);
    }
    private static Failure error(String message) { return new Failure(500,message); }
    private static class PlayCtx {
        Long actId; Long topicId; boolean selfPlay; boolean nightWarning; boolean continuing;
        CmsRegistration registration; Failure error;
    }

    private boolean isDelistedNode(CmsTopicNode node)
    {
        return node != null && node.getStatus() != null && !Long.valueOf(1L).equals(node.getStatus());
    }

    private boolean hasCompletedNodeFact(Long memberId, String activityId, CmsTopicNode node)
    {
        Long actId = 0L;
        if (StringUtils.isNotBlank(activityId) && !"0".equals(activityId.trim())) {
            try { actId = Long.valueOf(activityId.trim()); }
            catch (NumberFormatException invalid) { return false; }
        }
        return hasCompletedNodeFact(memberId, actId, node);
    }

    private boolean hasCompletedNodeFact(Long memberId, Long actId, CmsTopicNode node)
    {
        if (memberId == null || node == null || node.getId() == null) return false;
        PlayerNodeProgress row = findNodeProgressEntity(memberId, actId == null ? 0L : actId,
                node.getTopicId(), node.getId());
        return row != null && Integer.valueOf(1).equals(row.getStatus());
    }

    private PlayerNodeProgress findNodeProgressEntity(Long memberId, Long actId, Long topicId, Long nodeId)
    {
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(actId == null ? 0L : actId);
        probe.setNodeId(nodeId);
        if (actId == null || actId == 0L) {
            probe.setTopicId(topicId);
        }
        List<PlayerNodeProgress> rows = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isPrevDone(Long memberId, Long actId, CmsTopicNode node)
    {
        long mySort = node.getSortId() == null ? 0L : node.getSortId();
        CmsTopicNode allProbe = new CmsTopicNode();
        allProbe.setTopicId(node.getTopicId());
        allProbe.setStatus(1L);
        List<CmsTopicNode> all = cmsTopicNodeService.selectCmsTopicNodeList(allProbe);

        PlayerNodeProgress doneProbe = new PlayerNodeProgress();
        doneProbe.setMemberId(memberId);
        doneProbe.setActivityId(actId);
        List<PlayerNodeProgress> done = playerNodeProgressService.selectPlayerNodeProgressList(doneProbe);
        java.util.Set<Long> doneIds = new java.util.HashSet<>();
        // 完成事实只认 status==1:拍照待审(photo_review=1 的 status=0 行)不得据此放行下一站。
        for (PlayerNodeProgress p : done) {
            if (p.getStatus() != null && p.getStatus() == 1) doneIds.add(p.getNodeId());
        }

        for (CmsTopicNode n : all) {
            long s = n.getSortId() == null ? 0L : n.getSortId();
            if (s < mySort && !doneIds.contains(n.getId())) return false;
        }
        return true;
    }

    private String routeAccessError(Long memberId, Long actId, Long topicId, CmsTopicNode node,
                                    String routeActionId)
    {
        // §7-2 幂等豁免必须先于路线 seam:canPlay 的活节点集只取 status=1(TopicRouteRuntimeServiceImpl.nodes),
        // 已被本人正式完成的下架节点会被判「不属于当前主题」⇒ 幂等回执被路线闸吃掉。已完成事实判定放在
        // canPlay 之前;未完成的首次提交在更早的下架闸已被拒,不会借这条路放行新完成。
        if (isDelistedNode(node) && hasCompletedNodeFact(memberId, actId, node)) return null;
        if (resolveMode(actId, topicId) == PlayMode.LINEAR_ORIENTEERING) {
            if (topicRouteRuntimeService != null) {
                // 业务异常交由统一异常处理返回，不能吞掉已将事务标为回滚的异常。
                return topicRouteRuntimeService.canPlay(memberId, topicId, actId, node.getId(), routeActionId)
                        ? null : "该地点尚未解锁";
            }
            if (!isPrevDone(memberId, actId, node)) return "请先完成上一站";
        }
        return unlockGateError(memberId, actId, node);
    }

    private String unlockGateError(Long memberId, Long actId, CmsTopicNode node)
    {
        if (node.getUnlockAfterNodeId() == null || node.getUnlockAfterNodeId() <= 0) return null;
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(actId);
        probe.setNodeId(node.getUnlockAfterNodeId());
        List<PlayerNodeProgress> rows = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        for (PlayerNodeProgress p : rows) {
            if (p.getStatus() != null && p.getStatus() == 1) return null;
        }
        CmsTopicNode prev = cmsTopicNodeService.selectCmsTopicNodeById(node.getUnlockAfterNodeId());
        return prev != null && StringUtils.isNotBlank(prev.getName())
                ? "先完成「" + prev.getName() + "」才能解锁本关"
                : "本关尚未解锁,先完成前置关卡";
    }

    private String activityTimeError(CmsActivity activity)
    {
        if (activity == null) return null;
        Date now = new Date();
        if (activity.getStartDate() != null && now.before(activity.getStartDate())) return "活动未开始";
        if (activity.getEndDate() != null && !now.before(activity.getEndDate())) return "活动已结束";
        return null;
    }

    public int resolveMode(Long activityId, Long topicId)
    {
        if (topicId != null) {
            com.chengyinhub.business.domain.CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            if (topic != null && topic.getProductType() != null) {
                return topic.getProductType();
            }
        }
        // fallback:存量未回填 —— 与 P0-02 之前完全一致的行为
        Integer mode = firstTicketMode(2L, activityId);
        if (mode == null && topicId != null) mode = firstTicketMode(1L, topicId);
        return mode == null ? PlayMode.LINEAR_ORIENTEERING : mode;
    }

    private Integer firstTicketMode(Long ownerType, Long ownerId)
    {
        if (ownerId == null) return null;
        OmsTicket probe = new OmsTicket();
        probe.setOwnerType(ownerType);
        probe.setOwnerId(ownerId);
        List<OmsTicket> tickets = omsTicketService.selectOmsTicketList(probe);
        if (tickets == null) return null;
        for (OmsTicket t : tickets) {
            if (t.getMode() != null) return t.getMode();
        }
        return null;
    }

    private boolean isNightNow()
    {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 6;
    }

    private PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node)
    {
        return resolveCtx(memberId, activityId, topicId, node, null);
    }

    private PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node,
            Long registrationId)
    {
        PlayCtx c = new PlayCtx();
        if (StringUtils.isNotBlank(activityId) && !"0".equals(activityId.trim())) {
            // 活动会话(原逻辑,原样保留)
            Long actId;
            try { actId = Long.valueOf(activityId); } catch (Exception e) { c.error = error("参数有误"); return c; }
            CmsActivity activity = cmsActivityService.selectCmsActivityById(actId);
            if (activity == null || activity.getTopicId() == null) { c.error = error("活动不存在"); return c; }
            if (!activity.getTopicId().equals(node.getTopicId())) { c.error = error("该节点不属于本次活动"); return c; }
            CmsRegistration ticket;
            try { ticket=com.chengyinhub.business.service.support.CurrentPlayRegistration.require(
                    currentPlayActivityMapper,cmsRegistrationMapper,memberId,2L,actId,registrationId,new Date()); }
            catch(IllegalStateException denied) { c.error=error(denied.getMessage());return c; }
            if (ticket == null) { c.error = error("请先报名并完成支付"); return c; }
            c.registration = ticket;
            activity=currentPlayActivityMapper.selectCmsActivityByIdForUpdate(actId);
            if(activity==null || !node.getTopicId().equals(activity.getTopicId())) {
                c.error=error("该节点不属于本次活动");return c;
            }
            String timeErr = activityTimeError(activity);
            if (timeErr != null) { c.error = error(timeErr); return c; }
            c.actId = actId;
            c.topicId = activity.getTopicId();
            c.selfPlay = false;
            return c;
        }
        // 自玩会话(activity_id=0 + topic 通行证)
        if (StringUtils.isBlank(topicId)) { c.error = error("参数有误"); return c; }
        Long tId;
        try { tId = Long.valueOf(topicId); } catch (Exception e) { c.error = error("参数有误"); return c; }
        if (node.getTopicId() == null || !node.getTopicId().equals(tId)) { c.error = error("该节点不属于本主题"); return c; }
        CmsRegistration pass;
        // 已开局的自玩路线：通行证开局时有效即可打完本局(中途到期不打断)；未开局仍需有效通行证。
        // 「已开局」由路线运行时判定：有进行中的路线局，或不建局的普通线性路线已记过节点进度。
        boolean continuing = topicRouteRuntimeService != null && topicRouteRuntimeService.hasRunningSession(memberId, tId, 0L);
        try { pass = continuing
                ? com.chengyinhub.business.service.support.CurrentPlayRegistration.requireToContinue(
                        currentPlayActivityMapper,cmsRegistrationMapper,memberId,1L,tId,registrationId,new Date())
                : com.chengyinhub.business.service.support.CurrentPlayRegistration.require(
                        currentPlayActivityMapper,cmsRegistrationMapper,memberId,1L,tId,registrationId,new Date()); }
        catch(IllegalStateException denied) { c.error=new Failure(402,denied.getMessage());return c; }
        if (pass == null) { c.error = new Failure(402, "请先购买自玩通行证"); return c; }
        c.registration = pass;
        c.actId = 0L;
        c.topicId = tId;
        c.selfPlay = true;
        c.continuing = continuing;
        c.nightWarning = isNightNow();
        return c;
    }

    private String leadGateError(Long actId, CmsTopicNode node) {
        if (actId == null || actId <= 0) return null; // 自玩会话无带领
        try {
            String routeStatus = gameSessionRuntimeService == null ? null
                    : gameSessionRuntimeService.routeSessionStatus(actId);
            if (routeStatus != null) {
                return "RUNNING".equals(routeStatus) ? null : "本场带队尚未开局或已结束";
            }
            com.chengyinhub.business.domain.ClubLeadState st = clubLeadMapper.selectByActivity(actId);
            if (st == null || st.getStatus() == null || st.getStatus() == 3) return null;
            if (st.getStatus() == 1) return "队伍集合中,等队长开始行程";
            if (node == null || node.getChapterId() == null || st.getCurrentChapterId() == null) return null;
            if (node.getChapterId().equals(st.getCurrentChapterId())) return null;
            CmsTopicChapter cur = cmsTopicChapterService.selectCmsTopicChapterById(st.getCurrentChapterId());
            CmsTopicChapter mine = cmsTopicChapterService.selectCmsTopicChapterById(node.getChapterId());
            if (cur == null || mine == null || cur.getSortId() == null || mine.getSortId() == null) return null;
            return mine.getSortId() > cur.getSortId() ? "本章还没开启,等队长放行" : null;
        } catch (ServiceException refusal) {
            // 已有业务/权限拒绝沿原 code/data 传播，不能改写成依赖故障或非带领场。
            throw refusal;
        } catch (RuntimeException unavailable) {
            // 只有成功查无记录才是非带领场。故障必须越过写入口，使原事务及动作 claim 回滚。
            logger.warn("带领状态读取失败 activityId={} failureType={}", actId, unavailable.getClass().getSimpleName());
            throw new ServiceException("带领状态暂时不可用，请稍后重试", 503)
                    .setData(java.util.Collections.singletonMap("outcome", "NOT_RUN"));
        }
    }

}

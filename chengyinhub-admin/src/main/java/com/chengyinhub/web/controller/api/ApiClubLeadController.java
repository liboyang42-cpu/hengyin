package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeadCheckin;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.support.ClubGatherArrivalPolicy;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P1 经典定向 · 俱乐部带领(集合/行进/结算;队长控章节 + 轮询,不上 WebSocket)。
 *
 * 带领场判定:cms_activity.club_id 非空的场次;队长 = 活动创建者(活动归属俱乐部主理人)。
 * 状态机:1 集合中 → 2 行进(队长发车) → 3 已结算。成员推进节点的强校验在
 * ApiPlayProgressController.leadGateError(服务端,不靠前端禁按钮)。
 */
@RestController
@Tag(name = "俱乐部带领")
@RequestMapping("/api/club/lead")
public class ApiClubLeadController extends BaseController {

    @Autowired
    private ClubLeadMapper clubLeadMapper;
    @Autowired
    private com.chengyinhub.business.mapper.GameSessionRuntimeMapper commandReceiptMapper;
    @Autowired
    private CmsActivityMapper cmsActivityMapper;
    @Autowired
    private ClubGatherArrivalPolicy clubGatherArrivalPolicy;
    @Autowired
    private ClubEventOpsMapper clubEventOpsMapper;
    @Autowired
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;
    @Autowired
    private ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    @Autowired
    private com.chengyinhub.business.service.IClubService clubService;
    @Autowired
    private com.chengyinhub.business.access.club.ClubEventLeadResolver clubEventLeadResolver;
    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;
    @Autowired
    private ICmsRegistrationService cmsRegistrationService;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;
    @Autowired
    private ISettlementEntryService settlementEntryService;
    @Autowired
    private IPointsAwardService pointsAwardService;
    @Autowired
    private ICmsRegistrationScanService registrationScanService;
    @Autowired
    private IGrowthService growthService;

    private Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    /** 带领场校验 + 懒建/校正 state；活动领队委派优先，无委派才回落俱乐部主理人。 */
    private ClubLeadState ensureState(Long activityId) {
        CmsActivity act = cmsActivityService.selectCmsActivityById(activityId);
        if (act == null || act.getClubId() == null || act.getClubId() <= 0) {
            return null; // 非带领场
        }
        Long leaderId = resolveLeaderMemberId(act);
        ClubLeadState st = clubLeadMapper.selectByActivity(activityId);
        if (st == null) {
            st = new ClubLeadState();
            st.setActivityId(activityId);
            st.setLeaderMemberId(leaderId);
            st.setStatus(1); // 集合中
            st.setCreateTime(new Date());
            try {
                clubLeadMapper.insertClubLeadState(st);
            } catch (DuplicateKeyException e) {
                st = clubLeadMapper.selectByActivity(activityId); // 并发懒建
            }
        }
        if (st != null && leaderId != null && !leaderId.equals(st.getLeaderMemberId())) {
            ClubLeadState upd = new ClubLeadState();
            upd.setId(st.getId());
            upd.setLeaderMemberId(leaderId);
            clubLeadMapper.updateClubLeadState(upd);
            st.setLeaderMemberId(leaderId);
        }
        String routeStatus = gameSessionRuntimeService == null ? null
                : gameSessionRuntimeService.routeSessionStatus(activityId);
        if (st != null && routeStatus != null) {
            // 普通路线由导演场次控制，旧带队状态只作为兼容展示，不能重新拦住已开局玩家。
            st.setStatus("RUNNING".equals(routeStatus) ? 2
                    : ("FINISHED".equals(routeStatus) || "CANCELLED".equals(routeStatus)) ? 3 : 1);
            st.setCurrentChapterId(null);
        }
        return st;
    }

    /** 口径已抽到 ClubEventLeadResolver;这里只保留调用点,避免两处实现分叉。 */
    private Long resolveLeaderMemberId(CmsActivity activity) {
        return clubEventLeadResolver.resolveLeaderMemberId(activity);
    }

    @Operation(summary = "成员集合签到(幂等)")
    @PostMapping("/arrive")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult arrive(String activityId, Double latitude, Double longitude) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long actId = Convert.toLong(activityId, 0L);
        if (actId <= 0) return error("参数有误");
        CmsActivity activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(actId);
        String invalid = clubGatherArrivalPolicy.validate(activity, latitude, longitude, new Date());
        if (invalid != null) return error(invalid);
        ClubLeadState st = ensureState(actId);
        if (st == null) return error("该场次不是带领场");
        if (!Integer.valueOf(1).equals(st.getStatus()) && !Integer.valueOf(2).equals(st.getStatus())) return error("该场次已结束");
        if (!canAccessLeadActivity(actId, memberId, st)) {
            return error("仅本场已支付成员或领队可签到");
        }
        Date arrivedAt = new Date();
        invalid = clubGatherArrivalPolicy.validate(activity, latitude, longitude, arrivedAt);
        if (invalid != null) return error(invalid);
        ClubLeadCheckin c = new ClubLeadCheckin();
        c.setActivityId(actId);
        c.setMemberId(memberId);
        c.setArrivedAt(arrivedAt);
        try {
            if (clubLeadMapper.insertCheckin(c) != 1) return error("签到未完成，请重试");
        } catch (DuplicateKeyException e) {
            // 已签到,幂等成功
        }
        return AjaxResult.success("已到集合点");
    }

    @Operation(summary = "队长发车(集合→行进,放行首章)")
    @PostMapping("/start")
    public AjaxResult start(String activityId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long actId = Convert.toLong(activityId, 0L);
        ClubLeadState st = actId > 0 ? ensureState(actId) : null;
        if (st == null) return error("该场次不是带领场");
        if (!memberId.equals(st.getLeaderMemberId())) return error("只有队长可以开始行程");
        if (st.getStatus() != null && st.getStatus() >= 2) return AjaxResult.success("行程已开始");
        CmsTopicChapter first = firstChapter(actId);
        ClubLeadState upd = new ClubLeadState();
        upd.setId(st.getId());
        upd.setStatus(2);
        if (first != null) upd.setCurrentChapterId(first.getId());
        clubLeadMapper.updateClubLeadState(upd);
        return AjaxResult.success("出发!");
    }

    @Operation(summary = "队长放行下一章")
    @PostMapping("/unlock-chapter")
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult unlockChapter(String activityId, String commandId, Long expectedChapterId, Long targetChapterId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long actId = Convert.toLong(activityId, 0L);
        // 0 明确表示尚未放行；缺字段不能解释成“读取当前章再前进”。
        if (actId <= 0 || commandId == null || !commandId.matches("[A-Za-z0-9:_-]{1,80}")
                || expectedChapterId == null || expectedChapterId < 0 || targetChapterId == null || targetChapterId <= 0) {
            return error("必须提供命令标识、期望章节和目标章节");
        }
        CmsActivity probe = cmsActivityService.selectCmsActivityById(actId);
        if (probe == null || probe.getClubId() == null || probe.getClubId() <= 0) return error("该场次不是带领场");
        if (clubLeadMapper.lockClubOwner(probe.getClubId()) == null) return error("俱乐部不存在");
        CmsActivity activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(actId);
        if (activity == null || !probe.getClubId().equals(activity.getClubId())) return error("场次不可用");
        // 先读权威授权，再读回执。旧队长不能借已知 commandId 读取新队长的结果。
        if (!memberId.equals(resolveLeaderMemberId(activity))) return error("只有当前队长可以放行章节");
        String requestId = "lead:" + commandHash(commandId).substring(0, 48);
        String hash = commandHash(actId + ":" + memberId + ":" + expectedChapterId + ":" + targetChapterId);
        GameCommandReceipt prior = commandReceiptMapper.selectReceiptForUpdate(actId, memberId, requestId);
        if (prior != null) return chapterReplay(prior, hash);
        if (!Integer.valueOf(0).equals(activity.getDelFlag()) || activity.getCancelTime() != null) return error("场次不可用");
        // 导演运行时拥有的场次不能从旧带队入口推进另一个 state。
        if (gameSessionRuntimeService != null && gameSessionRuntimeService.routeSessionStatus(actId) != null) return error("请使用本场导演的章节命令");
        ClubLeadState st = clubLeadMapper.selectByActivityForUpdate(actId);
        if (st == null) return error("该场次不是带领场");
        if (!Integer.valueOf(2).equals(st.getStatus())) return error("仅行进中的场次可放行章节");
        Long expected = expectedChapterId == 0 ? null : expectedChapterId;
        if (!java.util.Objects.equals(expected, st.getCurrentChapterId())) return new AjaxResult(409, "期望章节已过期，请核对后重新确认").put("intentNotExecuted", true);
        List<CmsTopicChapter> chapters = chaptersOf(actId);
        if (chapters.isEmpty()) return error("本场无章节");
        int idx = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getId().equals(expected)) { idx = i; break; }
        }
        if (expected != null && idx < 0) return error("期望章节不属于本场主题");
        if (idx + 1 >= chapters.size()) return error("已是最后一章");
        CmsTopicChapter target = chapters.get(idx + 1);
        if (!targetChapterId.equals(target.getId())) return error("目标必须是期望章节的相邻下一章");
        GameCommandReceipt claim = new GameCommandReceipt();
        claim.setActivityId(actId); claim.setActorMemberId(memberId); claim.setActorPerspective("CLUB_LEAD");
        claim.setRequestId(requestId); claim.setRequestHash(hash); claim.setAction("LEAD_UNLOCK_CHAPTER");
        claim.setOutcome("PENDING"); claim.setCreateTime(new Date()); claim.setUpdateTime(new Date());
        if (commandReceiptMapper.insertReceiptClaim(claim) != 1) {
            prior = commandReceiptMapper.selectReceiptForUpdate(actId, memberId, requestId);
            if (prior == null) throw new IllegalStateException("章节命令登记失败");
            return chapterReplay(prior, hash);
        }
        if (clubLeadMapper.unlockChapterCas(actId, st.getLeaderMemberId(), memberId, expected, targetChapterId) != 1) throw new IllegalStateException("章节状态已变化，命令未执行");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandId", commandId); result.put("expectedChapterId", expectedChapterId); result.put("targetChapterId", targetChapterId);
        AjaxResult response = AjaxResult.success("已开启:" + target.getName(), result);
        claim.setOutcome("APPLIED"); claim.setResultJson(commandJson(response)); claim.setUpdateTime(new Date());
        if (commandReceiptMapper.completeReceipt(claim) != 1) throw new IllegalStateException("章节回执保存失败");
        // 首响也使用已保存回执的表示，避免 Long ID 经无类型 JSON 回放变为 Integer 后不等价。
        return chapterReplay(claim, hash);
    }

    /** 仅保留 Java 调用的显式拒绝，不提供旧语义执行路径。 */
    public AjaxResult unlockChapter(String activityId) { return unlockChapter(activityId, null, null, null); }

    @GetMapping("/unlock-chapter-receipt")
    @Transactional(readOnly = true)
    public AjaxResult unlockChapterReceipt(String activityId, String commandId) {
        Long memberId = uid();
        Long actId = Convert.toLong(activityId, 0L);
        if (memberId <= 0) return error("请先登录");
        if (actId <= 0 || commandId == null || !commandId.matches("[A-Za-z0-9:_-]{1,80}")) return error("参数有误");
        CmsActivity activity = cmsActivityService.selectCmsActivityById(actId);
        if (activity == null || !memberId.equals(resolveLeaderMemberId(activity))) return error("无权读取本场章节命令");
        GameCommandReceipt receipt = commandReceiptMapper.selectReceipt(actId, memberId, "lead:" + commandHash(commandId).substring(0, 48));
        return receipt == null ? new AjaxResult(520, "章节命令结果待确认") : chapterReplay(receipt, receipt.getRequestHash());
    }

    private AjaxResult chapterReplay(GameCommandReceipt receipt, String hash) {
        if (!"LEAD_UNLOCK_CHAPTER".equals(receipt.getAction()) || !"CLUB_LEAD".equals(receipt.getActorPerspective())
                || !hash.equals(receipt.getRequestHash())) return new AjaxResult(409, "命令标识已用于其他输入");
        if (!"APPLIED".equals(receipt.getOutcome())) return new AjaxResult(520, "章节命令结果待确认");
        try { return com.chengyinhub.common.utils.JsonUtils.getObjectMapper().readValue(receipt.getResultJson(), AjaxResult.class); }
        catch (java.io.IOException e) { throw new IllegalStateException("章节回执不可读", e); }
    }

    private String commandJson(Object value) {
        try { return com.chengyinhub.common.utils.JsonUtils.getObjectMapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("章节回执不可编码", e); }
    }

    private String commandHash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Operation(summary = "承接方改运营详情(集合时间/地点/结束时间/当日备注,报备留痕通知发起人)")
    @PostMapping("/edit-ops")
    public AjaxResult editOps(@RequestBody java.util.Map<String, Object> body) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long actId = Convert.toLong(body.get("activityId"), 0L);
        ClubLeadState st = actId > 0 ? ensureState(actId) : null;
        if (st == null) return error("该场次不是带领场");
        // 运营权委托:只有承接方领队(承接俱乐部主理人)可改运营详情
        if (!memberId.equals(st.getLeaderMemberId())) return error("只有承接方领队可改运营详情");
        CmsActivity act = cmsActivityService.selectCmsActivityById(actId);
        if (act == null) return error("场次不存在");
        // 白名单:只允许改运营字段(集合时间/地点/结束时间/当日备注);核心(票价/奖励/结算/安全底线)锁发起人,不在此列。
        CmsActivity upd = new CmsActivity();
        upd.setId(actId);
        StringBuilder chg = new StringBuilder();
        String addr = Convert.toStr(body.get("address"), null);
        if (addr != null) { upd.setAddress(addr); chg.append("集合地点; "); }
        String addrName = Convert.toStr(body.get("addressName"), null);
        if (addrName != null) { upd.setAddressName(addrName); chg.append("地点名; "); }
        java.util.Date sd = parseOpsDate(body.get("startDate"));
        if (sd != null) { upd.setStartDate(sd); chg.append("集合时间; "); }
        java.util.Date ed = parseOpsDate(body.get("endDate"));
        if (ed != null) { upd.setEndDate(ed); chg.append("结束时间; "); }
        String note = Convert.toStr(body.get("dayNote"), null);
        if (note != null) { upd.setDayNote(note); chg.append("当日备注"); }
        if (chg.length() == 0) return error("无可改运营字段");
        // F-L5C-02:集合地点与当日备注给参与者看,与 /broadcast 同一道内容安全闸。
        String opsText = com.chengyinhub.business.service.IWxContentSecurityService.joinText(addr, addrName, note);
        if (!opsText.isEmpty()) {
            String risk = com.chengyinhub.business.service.IWxContentSecurityService.checkTextAsMember(wxContentSecurityService, umsMemberService,
                    memberId, opsText);
            if (risk != null) return error(risk);
        }
        // CR-63(2026-09-15 裁决 15):已售出(有已支付报名)的场次锁集合时间与地点;
        // 当日备注是文字,按裁决仍可改。P1-2:待支付/支付中也算已售出(下单后改期同样是后门)。
        // 服务端为准,前端置灰只是体验。
        boolean touchesTimeOrPlace = addr != null || addrName != null || sd != null || ed != null;
        // #334 F7:已售判断与写入是同一条条件 UPDATE,先数后写之间的下单窗口已关;零行 = 已售锁定。
        int rows = cmsActivityService.updateLeadOpsUnlessSold(upd, touchesTimeOrPlace);
        if (rows == 0 && touchesTimeOrPlace) {
            return error("该场次已售出，集合时间与地点不能修改；当日备注仍可改")
                    .put("errorCode", "CLUB_OPS_SOLD_LOCKED");
        }
        if (rows == 0) return error("场次不存在");
        // 报备只留痕(入日志)+ 记发起人;没有实时推送,响应如实回执,不声称已通知。
        logger.info("运营权委托·承接方改运营详情 actId={} 领队(承接主理人)={} 发起人={} 改动=[{}]",
                actId, memberId, act.getMemberId(), chg.toString());
        java.util.Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("organizerNotified", false);
        receipt.put("noticeReceipt", "LOGGED_ONLY");
        return AjaxResult.success("已更新；已留痕，未推送通知发起人", receipt);
    }

    private java.util.Date parseOpsDate(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return com.chengyinhub.common.utils.DateUtils.parseDate(s);
    }

    @Operation(summary = "队长广播(置顶一条)")
    @PostMapping("/broadcast")
    public AjaxResult broadcast(String activityId, String text) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long actId = Convert.toLong(activityId, 0L);
        ClubLeadState st = actId > 0 ? ensureState(actId) : null;
        if (st == null) return error("该场次不是带领场");
        if (!memberId.equals(st.getLeaderMemberId())) return error("只有队长可以广播");
        if (text == null || text.trim().isEmpty()) return error("广播内容为空");
        String t = text.trim();
        String risk = com.chengyinhub.business.service.IWxContentSecurityService.checkTextAsMember(wxContentSecurityService, umsMemberService,
                memberId, t);
        if (risk != null) return error(risk);
        ClubLeadState upd = new ClubLeadState();
        upd.setId(st.getId());
        upd.setBroadcastText(t.length() > 200 ? t.substring(0, 200) : t);
        upd.setBroadcastAt(new Date());
        clubLeadMapper.updateClubLeadState(upd);
        return AjaxResult.success("已广播");
    }

    @Operation(summary = "队伍进度轮询(3~5s;state+集合n/m+成员完成数)")
    @GetMapping("/team-progress")
    public AjaxResult teamProgress(Long activityId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (activityId == null || activityId <= 0) return error("参数有误");
        ClubLeadState st = ensureState(activityId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (st == null) {
            data.put("exists", false);
            return success(data);
        }
        if (!canAccessLeadActivity(activityId, memberId, st)) {
            return error("无权查看本场队伍进度");
        }
        data.put("exists", true);
        data.put("status", st.getStatus());
        data.put("leaderMemberId", st.getLeaderMemberId());
        data.put("isLeader", memberId.equals(st.getLeaderMemberId()));
        data.put("currentChapterId", st.getCurrentChapterId());
        List<CmsTopicChapter> ordered = chaptersOf(activityId);
        Long nextChapterId = null;
        if (st.getCurrentChapterId() == null && !ordered.isEmpty()) nextChapterId = ordered.get(0).getId();
        for (int i = 0; i + 1 < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(st.getCurrentChapterId())) nextChapterId = ordered.get(i + 1).getId();
        }
        data.put("nextChapterId", nextChapterId);
        // 当前章节名
        if (st.getCurrentChapterId() != null) {
            CmsTopicChapter c = cmsTopicChapterService.selectCmsTopicChapterById(st.getCurrentChapterId());
            data.put("chapterName", c != null ? c.getName() : "");
        }
        data.put("broadcast", st.getBroadcastText());
        data.put("broadcastAt", st.getBroadcastAt());

        // 集合 n/m:n=已签到,m=报名成功人数(去重,含队长)
        List<ClubLeadCheckin> checkins = clubLeadMapper.selectCheckins(activityId);
        Set<Long> arrivedIds = new HashSet<>();
        for (ClubLeadCheckin c : checkins) arrivedIds.add(c.getMemberId());
        // 复用名册的最新更正事实；原始签到仍保留用于调证。
        for (Map<String, Object> row : clubEventOpsMapper.selectPaidRoster(activityId)) {
            Object corrected = row.get("correctedArrived");
            if (corrected == null) continue;
            Long correctedMemberId = ((Number) row.get("memberId")).longValue();
            if (Boolean.TRUE.equals(corrected)
                    || corrected instanceof Number && ((Number) corrected).intValue() == 1) {
                arrivedIds.add(correctedMemberId);
            } else {
                arrivedIds.remove(correctedMemberId);
            }
        }
        Set<Long> memberIds = new HashSet<>(arrivedIds);
        CmsRegistration regProbe = new CmsRegistration();
        regProbe.setOwnerType(2L);
        regProbe.setOwnerId(activityId);
        regProbe.setRegistrationStatus(2);
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(regProbe);
        Set<Long> paidIds = new HashSet<>();
        if (regs != null) for (CmsRegistration r : regs) if (r.getMemberId() != null) paidIds.add(r.getMemberId());
        memberIds.addAll(paidIds);
        memberIds.add(st.getLeaderMemberId());
        data.put("arrived", arrivedIds.size());
        data.put("total", memberIds.size());
        data.put("meArrived", arrivedIds.contains(memberId));

        // 成员完成数(player_node_progress 按 activityId 聚合)
        // R10-09 收尾:完成数只认 status==1 正式完成;status=0 到店/凭证行不得刷排名与 done 字段
        // (到达事实由 arrivedIds 独立承重,不在此列)。
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setActivityId(activityId);
        List<PlayerNodeProgress> progress = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        Map<Long, Integer> doneCount = new HashMap<>();
        if (progress != null) for (PlayerNodeProgress p : progress) {
            if (!Integer.valueOf(1).equals(p.getStatus())) continue;
            doneCount.merge(p.getMemberId(), 1, Integer::sum);
        }
        List<Map<String, Object>> members = new ArrayList<>();
        List<Long> orderedMembers = new ArrayList<>(memberIds);
        orderedMembers.sort(Comparator.comparingInt(id -> -doneCount.getOrDefault(id, 0)));
        int cap = 0;
        for (Long mid : orderedMembers) {
            if (++cap > 20) break; // 轮询接口,人数封顶防大团刷库
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("memberId", mid);
            UmsMember u = umsMemberService.selectUmsMemberById(mid);
            m.put("nickname", u != null ? u.getNickname() : "");
            m.put("avatar", u != null ? u.getAvatar() : "");
            m.put("done", doneCount.getOrDefault(mid, 0));
            m.put("arrived", arrivedIds.contains(mid));
            m.put("isLeader", mid.equals(st.getLeaderMemberId()));
            members.add(m);
        }
        data.put("members", members);
        return success(data);
    }

    /** 队伍实时数据只向本场领队或已完成支付的本场报名者开放。 */
    private boolean canAccessLeadActivity(Long activityId, Long memberId, ClubLeadState state) {
        if (state != null && memberId != null && memberId.equals(state.getLeaderMemberId())) {
            return true;
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setOwnerType(2L);
        probe.setOwnerId(activityId);
        probe.setMemberId(memberId);
        probe.setPaymentStatus(2);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> registrations = cmsRegistrationService.selectCmsRegistrationList(probe);
        return registrations != null && !registrations.isEmpty();
    }

    @Operation(summary = "队长全团结算(D-9:领队一次核销 = 全团入结算)")
    @PostMapping("/settle")
    public AjaxResult settle(String activityId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long actId = Convert.toLong(activityId, 0L);
        if (actId <= 0) return error("参数有误");
        CmsActivity act = cmsActivityService.selectCmsActivityById(actId);
        if (act == null || act.getClubId() == null || act.getClubId() <= 0) return error("该场次不是带领场");
        ClubLeadState st = ensureState(actId);
        if (st == null) return error("该场次不是带领场");
        // D-9 授权:仅本场当前领队本人可触发全团结算
        if (!memberId.equals(st.getLeaderMemberId())) return error("只有队长可以结算全团");
        Integer status = st.getStatus();
        if (status == null || status < 2) return error("请先发车再结算");
        if (status >= 3) {
            replayStoreVerifyGrowth(actId);
            return AjaxResult.success("全团已结算");
        }

        // 结转名单(D-9 已核名单):已付款(status=2)∩ 集合签到到场;无人签到则回落全部已付
        CmsRegistration probe = new CmsRegistration();
        probe.setOwnerType(2L);
        probe.setOwnerId(actId);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> paid = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (paid == null) paid = new ArrayList<>();
        Set<Long> arrived = new HashSet<>();
        List<ClubLeadCheckin> checkins = clubLeadMapper.selectCheckins(actId);
        if (checkins != null) for (ClubLeadCheckin c : checkins) arrived.add(c.getMemberId());

        Long merchantId = act.getMemberId(); // 收款方 = 活动创建者(俱乐部主理人),与单张商家核销口径一致
        int settled = 0;
        for (CmsRegistration reg : paid) {
            if (reg.getMemberId() == null) continue;
            // ★★ [P0-14 资损] ③ 探索票**跳过**,绝不能走整单结算。
            //
            // 上面的探针只过滤 ownerType=2 / ownerId=actId / registrationStatus=2,**不看 purchase_kind**
            // ⇒ 若该活动挂的是 ③ 主题,玩家的探索票也会被捞进这个循环。
            // 而 verifyAndSettle 的语义是「整单票款按 settlement_rule 结算给单一 payee」,这里 payee =
            // 俱乐部主理人 ⇒ **③ 的整单票款结给主理人,真正逐章端出权益的那 N 家商家一分钱没有** = 资损。
            //
            // ③ 的结算走的是另一条路:商家扫码 → redeemExploreChapterAndRecord 按章核销
            //(本轮入账口径未定,见交接文档 §6bis 决策 B),与领队全团结算无关。
            // ⚠️ 必须在**这里**过滤而不是靠 verifyAndSettle 抛异常来跳过 —— 那会中断整批结算,
            //    让同活动里的 ① 玩家也结不了账。被调方那道闸是给「漏了过滤的新入口」兜底用的。
            if (reg.getPurchaseKind() != null
                    && reg.getPurchaseKind() == com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS) {
                continue;
            }
            // 到场 ∩ 已付;checkins 为空则不设到场门槛(全部已付)
            if (!arrived.isEmpty() && !arrived.contains(reg.getMemberId())) continue;
            // [P0-21] 核销收口:条件置位 + 入账 + 台账,同一事务(verifyAndSettle)。
            //
            // 这里原本是自己手写的三步,和单张商家核销那条路一模一样地踩了三个洞:
            // ①「读出来判 verificationStatus==1 就 continue」是读判不是条件置位 ⇒ 队长点全团结算的
            //   同时商家扫单张码,两边都读到 0、都置 1、都进结算 → 同一报名双重入账;
            // ② 本方法**无事务**(整个类零 @Transactional),置位先提交、入账再抛就永远补不回来;
            // ③ 台账探针没设 registrationType(1) ⇒ 命中报名时按节点模板插的 type=0 行 → 误判「已有台账」
            //   → 核销行不插 → ownerType=2 的核销在商家台账/待办/动态永久不可见。
            // 三条都由 verifyAndSettle 一并解决;返 false = 已被核销(替代原来的读判 continue)。
            if (!cmsRegistrationService.verifyAndSettle(reg, memberId, merchantId, null)) {
                applyStoreVerifyGrowth(reg);
                continue;
            }
            // 积分/勋章留在事务外:幂等、非资金,发分失败不该回滚已成立的核销。
            pointsAwardService.award(reg.getMemberId(), 11L, reg.getId(), "领队全团核销");
            applyStoreVerifyGrowth(reg);
            settled++;
        }

        // 状态机 行进(2) → 已结算(3),幂等
        ClubLeadState upd = new ClubLeadState();
        upd.setId(st.getId());
        upd.setStatus(3);
        clubLeadMapper.updateClubLeadState(upd);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settled", settled);
        return AjaxResult.success("全团结算完成,共 " + settled + " 人", data);
    }

    // ---------- helpers ----------
    private void replayStoreVerifyGrowth(Long activityId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setOwnerType(2L);
        probe.setOwnerId(activityId);
        probe.setPaymentStatus(2);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> registrations = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (registrations == null) return;
        for (CmsRegistration registration : registrations) applyStoreVerifyGrowth(registration);
    }

    private void applyStoreVerifyGrowth(CmsRegistration registration) {
        if (registration == null || registration.getId() == null || registration.getMemberId() == null) return;
        try {
            growthService.deliverStoreVerification(registration.getId());
        } catch (Exception e) {
            logger.error("领队核销成长奖励发放失败 registrationId={} member={},持久待办等待恢复",
                    registration.getId(), registration.getMemberId(), e);
        }
    }

    private List<CmsTopicChapter> chaptersOf(Long activityId) {
        CmsActivity act = cmsActivityService.selectCmsActivityById(activityId);
        List<CmsTopicChapter> empty = new ArrayList<>();
        if (act == null || act.getTopicId() == null) return empty;
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(act.getTopicId());
        List<CmsTopicChapter> list = cmsTopicChapterService.selectCmsTopicChapterList(probe);
        if (list == null) return empty;
        list.sort(Comparator.comparing(c -> c.getSortId() == null ? 0L : c.getSortId()));
        return list;
    }

    private CmsTopicChapter firstChapter(Long activityId) {
        List<CmsTopicChapter> list = chaptersOf(activityId);
        return list.isEmpty() ? null : list.get(0);
    }
}

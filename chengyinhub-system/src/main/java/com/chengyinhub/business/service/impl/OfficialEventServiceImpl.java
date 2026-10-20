package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.EventSignup;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 官方活动 实现。
 *
 * @author cy
 * @date 2026-07-08
 */
@Service
public class OfficialEventServiceImpl implements IOfficialEventService {

    private static final Logger log = LoggerFactory.getLogger(OfficialEventServiceImpl.class);

    @Autowired
    private OfficialEventMapper eventMapper;
    @Autowired
    private EventSignupMapper signupMapper;
    @Autowired
    private IGrowthService growthService;
    @Autowired
    private OfficialSettlementHelper settlementHelper;
    @Autowired
    private IOfficialEventV2Service officialEventV2Service;
    @Autowired
    private IAuditTaskService auditTaskService;

    @Override
    public List<Map<String, Object>> listPublic(String city, Long memberId) {
        List<OfficialEvent> list = eventMapper.selectPublicList(city);
        List<Map<String, Object>> out = new ArrayList<>();
        if (list == null) return out;
        for (OfficialEvent e : list) {
            if (isV2(e)) continue;
            Map<String, Object> m = baseVo(e);
            m.put("participants", signupMapper.countByEvent(e.getId()));
            m.put("collective", collective(e));
            if (memberId != null && memberId > 0) {
                m.put("signed", signupMapper.selectByEventAndMember(e.getId(), memberId) != null);
            }
            out.add(m);
        }
        out.addAll(officialEventV2Service.listPublic(city, memberId));
        return out;
    }

    @Override
    public Map<String, Object> detail(Long id, Long memberId) {
        OfficialEvent e = eventMapper.selectOfficialEventById(id);
        if (e == null) return null;
        if (isV2(e)) return officialEventV2Service.detail(id, memberId);
        int status = e.getStatus() == null ? OfficialEvent.ST_DRAFT : e.getStatus();
        if (status == OfficialEvent.ST_DRAFT || status == OfficialEvent.ST_OFFLINE) return null;
        Map<String, Object> m = baseVo(e);
        m.put("story", e.getStory());
        m.put("contentJson", e.getContentJson());
        m.put("rewardJson", e.getRewardJson());
        m.put("merchantIds", e.getMerchantIds());
        m.put("brandLogo", e.getBrandLogo());
        m.put("leaderboardEnabled", e.getLeaderboardEnabled());
        m.put("participants", signupMapper.countByEvent(id));
        m.put("collective", collective(e));
        if (memberId != null && memberId > 0) {
            EventSignup s = signupMapper.selectByEventAndMember(id, memberId);
            m.put("signed", s != null);
            if (s != null) {
                m.put("mySignupStatus", s.getStatus());
                m.put("myProgress", s.getProgress());
            }
        }
        return m;
    }

    @Override
    @Transactional
    public String signup(Long eventId, Long memberId) {
        // V1 与 V2 均锁原活动行，最后一个既有名额的计数/插入必须串行。
        OfficialEvent e = eventMapper.selectOfficialEventByIdForUpdate(eventId);
        if (e == null) return "活动不存在";
        if (isV2(e)) return officialEventV2Service.signup(eventId, memberId);
        int st = e.getStatus() == null ? OfficialEvent.ST_DRAFT : e.getStatus();
        if (st == OfficialEvent.ST_DRAFT || st == OfficialEvent.ST_OFFLINE) return "活动未开放";
        if (st >= OfficialEvent.ST_ENDED) return "活动已结束";
        if (st == OfficialEvent.ST_SETTLING) return "活动已进入结算,报名截止";
        // 原报名上限：持活动锁计数；已报名者仍幂等放行。
        if (e.getSignupLimit() != null && e.getSignupLimit() > 0) {
            if (signupMapper.selectByEventAndMemberForUpdate(eventId, memberId) == null
                    && signupMapper.countByEventForUpdate(eventId) >= e.getSignupLimit()) {
                return "报名人数已满";
            }
        }
        EventSignup s = new EventSignup();
        s.setEventId(eventId);
        s.setMemberId(memberId);
        s.setStatus(EventSignup.ST_SIGNED);
        s.setProgress(0);
        if (signupMapper.insertIgnore(s) != 1
                && signupMapper.selectByEventAndMemberForUpdate(eventId, memberId) == null) {
            throw new IllegalStateException("官方活动报名写入失败");
        }
        return null;
    }

    @Override
    public String completeTask(Long eventId, Long memberId) {
        OfficialEvent event = eventMapper.selectOfficialEventById(eventId);
        if (event == null) return "活动不存在";
        // 完成进度与奖励只能由服务端任务事实推进，历史 V1 没有可验证的完成凭证。
        return "请完成对应任务后自动验证；旧版活动请联系主办方处理";
    }

    @Override
    public Map<String, Object> progress(Long eventId) {
        OfficialEvent e = eventMapper.selectOfficialEventById(eventId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) return m;
        // 该入口属于玩家公开 API。V2 的运营 360 含承接、风险、名单与奖励明细，
        // 只能由后台受权接口读取，不能借旧 progress 路径泄露。
        if (isV2(e)) return officialEventV2Service.publicProgress(eventId);
        m.put("participants", signupMapper.countByEvent(eventId));
        m.put("done", signupMapper.countDoneByEvent(eventId));
        m.put("collective", collective(e));
        return m;
    }

    @Override
    public List<Map<String, Object>> myEvents(Long memberId) {
        List<EventSignup> mine = signupMapper.selectByMember(memberId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (mine == null) return out;
        for (EventSignup s : mine) {
            OfficialEvent e = eventMapper.selectOfficialEventById(s.getEventId());
            if (e == null) continue;
            if (isV2(e)) continue;
            Map<String, Object> m = baseVo(e);
            m.put("mySignupStatus", s.getStatus());
            m.put("myProgress", s.getProgress());
            m.put("rewardClaimed", s.getRewardClaimed());
            out.add(m);
        }
        out.addAll(officialEventV2Service.myEvents(memberId));
        return out;
    }

    @Override
    public List<Map<String, Object>> activeForRoam(String city, Long memberId) {
        List<OfficialEvent> list = eventMapper.selectPublicList(city);
        List<Map<String, Object>> out = new ArrayList<>();
        if (list == null) return out;
        for (OfficialEvent e : list) {
            if (isV2(e)) continue;
            if (!isRoamBound(e)) continue;
            int st = e.getStatus() == null ? OfficialEvent.ST_DRAFT : e.getStatus();
            // 仅"报名中/进行中"可去点亮(排期/结算/结束/草稿/下线不出现在 roam 入口)
            if (st != OfficialEvent.ST_SIGNUP && st != OfficialEvent.ST_LIVE) continue;
            Map<String, Object> m = baseVo(e);
            m.put("collective", collective(e));
            if (memberId != null && memberId > 0) {
                m.put("signed", signupMapper.selectByEventAndMember(e.getId(), memberId) != null);
            }
            out.add(m);
        }
        out.addAll(officialEventV2Service.activeForRoam(city, memberId));
        return out;
    }

    @Override
    @Transactional
    public OfficialEvent publish(OfficialEvent e, Long senderAccount) {
        if (e == null || StringUtils.isEmpty(e.getTitle())) {
            throw new ServiceException("活动标题不能为空");
        }
        e.setSenderAccount(senderAccount);
        if (isV2(e)) {
            throw new ServiceException("V2 活动必须通过草稿制作台保存和发布");
        }
        e.setCreateTime(new Date());
        if (e.getContractVersion() == null) e.setContractVersion(1);
        if (e.getStatus() == null) {
            e.setStatus(computeStatusByTime(e));
        }
        eventMapper.insertOfficialEvent(e);
        return e;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OfficialEvent submitForReview(OfficialEvent e, Long senderAccount) {
        // 先审后发:请求体带的 status/control_state 一律不认,只能落待审;审核单与活动同事务,
        // 建单失败整体回滚,不留「永远没人审」的待审活动。
        if (e != null) {
            e.setStatus(OfficialEvent.ST_DRAFT);
            e.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);
        }
        OfficialEvent saved = publish(e, senderAccount);
        JSONObject snap = new JSONObject();
        snap.put("bizType", AuditTask.BIZ_OFFICIAL_EVENT);
        snap.put("bizId", saved.getId());
        snap.put("contractVersion", saved.getContractVersion());
        snap.put("title", saved.getTitle());
        snap.put("subtitle", saved.getSubtitle());
        snap.put("story", saved.getStory());
        snap.put("coverImg", saved.getCoverImg());
        snap.put("city", saved.getCity());
        auditTaskService.submit(AuditTask.BIZ_OFFICIAL_EVENT, saved.getId(), senderAccount,
                "user_publish", snap.toJSONString());
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyAuditDecision(Long eventId, boolean approved, String reason, Long submitterId) {
        OfficialEvent e = eventMapper.selectOfficialEventByIdForUpdate(eventId);
        if (e == null) throw new ServiceException("官方活动不存在");
        if (isV2(e)) {
            officialEventV2Service.applyAuditDecision(eventId, approved, reason, submitterId);
            return;
        }
        if (!isPendingReview(e)) {
            throw new ServiceException("官方活动审核状态已变化，请刷新后重试");
        }
        OfficialEvent upd = new OfficialEvent();
        upd.setId(eventId);
        // 过审 → 按时间定状态上架;驳回 → 下线(详情/列表都不出)
        upd.setStatus(approved ? computeStatusByTime(e) : OfficialEvent.ST_OFFLINE);
        upd.setControlState("NORMAL");
        upd.setUpdateTime(new Date());
        eventMapper.updateOfficialEvent(upd);
    }

    private boolean isPendingReview(OfficialEvent e) {
        return e.getStatus() != null && e.getStatus() == OfficialEvent.ST_DRAFT
                && OfficialEvent.CONTROL_PENDING_REVIEW.equals(e.getControlState());
    }

    @Override
    public List<OfficialEvent> listBySender(Long senderAccount) {
        return eventMapper.selectBySender(senderAccount);
    }

    @Override
    public List<OfficialEvent> adminList(OfficialEvent criteria) {
        return eventMapper.selectOfficialEventList(criteria == null ? new OfficialEvent() : criteria);
    }

    @Override
    public OfficialEvent adminGet(Long id) {
        return eventMapper.selectOfficialEventById(id);
    }

    @Override
    @Transactional
    public OfficialEvent adminSave(OfficialEvent e, Long operatorId) {
        if (e == null || StringUtils.isEmpty(e.getTitle())) {
            throw new ServiceException("活动标题不能为空");
        }
        if (e.getId() == null) {
            if (isV2(e)) throw new ServiceException("V2 活动必须通过 V2 制作台保存");
            return publish(e, operatorId); // 新建:按时间定初始 status
        }
        OfficialEvent stored = eventMapper.selectOfficialEventById(e.getId());
        if (isV2(stored) || isV2(e)) {
            throw new ServiceException("V2 活动禁止通过旧表单修改");
        }
        e.setUpdateTime(new Date());
        eventMapper.updateOfficialEvent(e);
        return eventMapper.selectOfficialEventById(e.getId());
    }

    @Override
    public void adminSetStatus(Long id, Integer status) {
        OfficialEvent upd = new OfficialEvent();
        upd.setId(id);
        upd.setStatus(status);
        eventMapper.updateOfficialEvent(upd);
    }

    @Override
    public void runStatusFlow() {
        List<OfficialEvent> active = eventMapper.selectActiveForFlow();
        if (active == null) return;
        for (OfficialEvent e : active) {
            if (isV2(e)) continue;
            int newSt = computeStatusByTime(e);
            int oldSt = e.getStatus() == null ? OfficialEvent.ST_DRAFT : e.getStatus();
            if (oldSt < OfficialEvent.ST_SETTLING && newSt >= OfficialEvent.ST_SETTLING) {
                OfficialEvent upd = new OfficialEvent();
                upd.setId(e.getId());
                upd.setStatus(OfficialEvent.ST_SETTLING);
                eventMapper.updateOfficialEvent(upd);
                oldSt = OfficialEvent.ST_SETTLING;
            }
            if (oldSt == OfficialEvent.ST_SETTLING && newSt >= OfficialEvent.ST_SETTLING) {
                try {
                    settle(e.getId());
                } catch (Exception ex) {
                    log.error("[官方活动] 结算异常 event={}", e.getId(), ex);
                    continue;
                }
                if (newSt == OfficialEvent.ST_ENDED) {
                    OfficialEvent upd = new OfficialEvent();
                    upd.setId(e.getId());
                    upd.setStatus(OfficialEvent.ST_ENDED);
                    eventMapper.updateOfficialEvent(upd);
                }
                continue;
            }
            if (newSt != oldSt) {
                OfficialEvent upd = new OfficialEvent();
                upd.setId(e.getId());
                upd.setStatus(newSt);
                eventMapper.updateOfficialEvent(upd);
            }
        }
        officialEventV2Service.runLifecycle();
    }

    /**
     * 结算发奖。发放逐人走 {@link OfficialSettlementHelper#settleOne} 的独立事务:
     *  - 原子领取(WHERE reward_claimed=0)保证并发/重跑不重复发放;
     *  - 单人发奖失败只回滚该人、reward_claimed 退回0留待重试,不吞异常、不牵连他人;
     *  - 本方法自身不需事务(工作都在 helper),故 runStatusFlow 自调用也安全。
     */
    @Override
    public int settle(Long eventId) {
        OfficialEvent e = eventMapper.selectOfficialEventById(eventId);
        if (e == null) return 0;
        if (isV2(e)) {
            return officialEventV2Service.settle(eventId);
        }
        JSONObject rw = parse(e.getRewardJson());
        Map<String, Object> col = collective(e);
        boolean collectiveReached = Boolean.TRUE.equals(col.get("reached"));
        Long settleCouponId = rw == null ? null : rw.getLong("settleCouponId");
        Integer settleXp = rw == null ? null : rw.getInteger("settleXp");
        boolean settleBadge = rw != null && rw.getBooleanValue("settleBadge");
        Long collectiveCouponId = rw == null ? null : rw.getLong("collectiveCouponId");

        List<EventSignup> list = signupMapper.selectByEvent(eventId);
        if (list == null) return 0;
        int granted = 0;
        int failed = 0;
        for (EventSignup s : list) {
            if (s.getRewardClaimed() != null && s.getRewardClaimed() == 1) continue; // 快速幂等;真正的并发守卫在 settleOne 的原子领取
            try {
                if (settlementHelper.settleOne(s, eventId, settleCouponId, settleXp,
                        settleBadge, collectiveReached, collectiveCouponId)) {
                    granted++;
                }
            } catch (Exception ex) {
                failed++;
                log.error("[官方活动] 单人结算失败(已回滚,reward_claimed 保持0待重试) event={} signupId={} member={}",
                        eventId, s.getId(), s.getMemberId(), ex);
            }
        }
        log.info("[官方活动] 结算 event={} 发奖={} 失败={} 集体达标={}", eventId, granted, failed, collectiveReached);
        if (failed > 0) {
            throw new ServiceException("官方活动结算存在失败 event=" + eventId + " failed=" + failed);
        }
        return granted;
    }

    // ===== helpers =====

    /** 集体进度 map:enabled/metric/current/threshold/pct/reached。 */
    private Map<String, Object> collective(OfficialEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e.getCollectiveEnabled() == null || e.getCollectiveEnabled() == 0) {
            m.put("enabled", false);
            return m;
        }
        String metric = StringUtils.isEmpty(e.getCollectiveMetric()) ? "complete_count" : e.getCollectiveMetric();
        int current;
        if ("signup_count".equals(metric)) current = signupMapper.countByEvent(e.getId());
        else if ("light_count".equals(metric)) current = signupMapper.sumProgressByEvent(e.getId());
        else current = signupMapper.countDoneByEvent(e.getId());
        int threshold = e.getCollectiveThreshold() == null ? 0 : e.getCollectiveThreshold();
        int pct = threshold > 0 ? (int) Math.min(100L, (long) current * 100 / threshold) : 0;
        m.put("enabled", true);
        m.put("metric", metric);
        m.put("current", current);
        m.put("threshold", threshold);
        m.put("pct", pct);
        m.put("reached", threshold > 0 && current >= threshold);
        return m;
    }

    /** 基础展示字段(列表/详情共用)。倒计时交前端按时间戳算。 */
    private Map<String, Object> baseVo(OfficialEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("subtitle", e.getSubtitle());
        m.put("category", e.getCategory());
        m.put("coverImg", e.getCoverImg());
        m.put("city", e.getCity());
        m.put("status", e.getStatus());
        m.put("contractVersion", e.getContractVersion());
        m.put("controlState", e.getControlState());
        m.put("roamEnabled", isRoamBound(e));
        m.put("bannerEnabled", e.getBannerEnabled());
        m.put("signupStart", e.getSignupStart());
        m.put("activityStart", e.getActivityStart());
        m.put("activityEnd", e.getActivityEnd());
        m.put("settleTime", e.getSettleTime());
        return m;
    }

    /** 是否绑定"点亮城市"漫游玩法:content_json.roam==true 或 play_type=="roam"。无 schema 改动。 */
    private boolean isRoamBound(OfficialEvent e) {
        JSONObject c = parse(e.getContentJson());
        if (c == null) return false;
        if (c.getBooleanValue("roam")) return true;
        return "roam".equalsIgnoreCase(c.getString("play_type"));
    }

    /** content_json 的任务数:poi_ids / tasks 数组长度;缺省 1。 */
    private int taskCountOf(OfficialEvent e) {
        JSONObject c = parse(e.getContentJson());
        if (c == null) return 1;
        JSONArray pois = c.getJSONArray("poi_ids");
        if (pois != null && !pois.isEmpty()) return pois.size();
        JSONArray tasks = c.getJSONArray("tasks");
        if (tasks != null && !tasks.isEmpty()) return tasks.size();
        return 1;
    }

    /** 按时间算 status(结算→结束→进行→报名→排期→默认报名开放)。 */
    private int computeStatusByTime(OfficialEvent e) {
        Date now = new Date();
        if (e.getSettleTime() != null && !now.before(e.getSettleTime())) return OfficialEvent.ST_ENDED;
        if (e.getActivityEnd() != null && !now.before(e.getActivityEnd())) return OfficialEvent.ST_SETTLING;
        if (e.getActivityStart() != null && !now.before(e.getActivityStart())) return OfficialEvent.ST_LIVE;
        if (e.getSignupStart() != null && !now.before(e.getSignupStart())) return OfficialEvent.ST_SIGNUP;
        if (e.getSignupStart() != null && now.before(e.getSignupStart())) return OfficialEvent.ST_SCHEDULED;
        return OfficialEvent.ST_SIGNUP;
    }

    private boolean isV2(OfficialEvent event) {
        return event != null && event.getContractVersion() != null && event.getContractVersion() >= 2;
    }

    private JSONObject parse(String json) {
        if (StringUtils.isEmpty(json)) return null;
        try {
            return JSON.parseObject(json);
        } catch (Exception ex) {
            return null;
        }
    }
}

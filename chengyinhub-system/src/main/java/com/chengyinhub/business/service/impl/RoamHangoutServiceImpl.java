package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.RoamHangout;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoamHangoutServiceImpl implements IRoamHangoutService {
    private static final Logger log = LoggerFactory.getLogger(RoamHangoutServiceImpl.class);

    @Autowired private RoamHangoutMapper roamHangoutMapper;
    @Autowired private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;
    @Autowired private IImService imService;
    @Autowired private IWxContentSecurityService wxContentSecurityService;
    @Autowired private IAuditTaskService auditTaskService;
    @Autowired private IUmsMemberService umsMemberService;

    private static final int LIST_LIMIT = 100;

    // ---------- 附近 ----------

    @Override
    public Map<String, Object> nearby(Long viewerId, double lat, double lng, int radiusM) {
        requireLogin(viewerId);
        int r = radiusM <= 0 ? DEFAULT_RADIUS_M : Math.min(radiusM, MAX_RADIUS_M);
        double[] box = bbox(lat, lng, r);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        // 2026-09-15 裁决:搭子局(A)下架,地图不再返回 kind=hangout 图层(也不再按局数探半径)。
        // 存量局的 detail/leave/close 仍走本 service 的其它方法,读取不崩。
        List<Map<String, Object>> acts = withoutMemberOnlyTopics(
                roamHangoutMapper.selectNearbyActivities(lat, lng, box[0], box[1], box[2], box[3], r, LIST_LIMIT, viewerId), viewerId);
        if (acts != null) for (Map<String, Object> a : acts) { a.put("kind", "activity"); a.put("viewerHasTicket", ((Number) a.getOrDefault("viewerHasTicket", 0)).intValue() > 0); items.add(a); }
        // 用户 2026-09-06:这张图不只漫游的局,发布出去的城市定向 / 自由探索也要出现在上面
        List<Map<String, Object>> topics = cmsTopicMapper.selectNearbyTopics(lat, lng, box[0], box[1], box[2], box[3], r, LIST_LIMIT, viewerId);
        if (topics != null) for (Map<String, Object> t : topics) { t.put("kind", "topic"); t.put("viewerHasTicket", ((Number) t.getOrDefault("viewerHasTicket", 0)).intValue() > 0); if (t.get("ownerRole") == null) t.put("ownerRole", "player"); items.add(t); }
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("items", items);
        out.put("radius", r);
        return out;
    }

    /** O-08:「仅俱乐部成员可见」主题下的场次坐标不落到非成员地图上(判据与主题图层同一道 clubMemberOnlyGate)。 */
    private List<Map<String, Object>> withoutMemberOnlyTopics(List<Map<String, Object>> acts, Long viewerId) {
        if (acts == null || acts.isEmpty()) return acts;
        List<Long> topicIds = new ArrayList<Long>();
        for (Map<String, Object> a : acts) {
            Object topicId = a.get("topicId");
            if (topicId instanceof Number && ((Number) topicId).longValue() > 0
                    && !topicIds.contains(((Number) topicId).longValue())) topicIds.add(((Number) topicId).longValue());
        }
        if (topicIds.isEmpty()) return acts;
        List<Long> hidden = cmsTopicMapper.selectClubMemberOnlyHiddenTopicIds(topicIds, viewerId);
        if (hidden == null || hidden.isEmpty()) return acts;
        List<Map<String, Object>> visible = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : acts) {
            Object topicId = a.get("topicId");
            if (topicId instanceof Number && hidden.contains(((Number) topicId).longValue())) continue;
            visible.add(a);
        }
        return visible;
    }

    static double[] bbox(double lat, double lng, double radiusM) {
        double dLat = radiusM / 111320.0;
        double dLng = radiusM / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        return new double[] { lat - dLat, lat + dLat, lng - dLng, lng + dLng };
    }

    private Map<String, Object> hangoutItem(RoamHangout h) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("kind", "hangout");
        m.put("id", h.getId());
        m.put("title", h.getTitle());
        m.put("description", h.getDescription());
        m.put("coverUrl", h.getCoverUrl());
        m.put("ownerId", h.getMemberId());
        m.put("ownerAvatar", h.getOwnerAvatar());
        m.put("ownerNickname", h.getOwnerNickname());
        m.put("ownerRole", h.getOwnerRole() == null ? "player" : h.getOwnerRole());   // player / merchant / club:地图上决定头像框形态
        m.put("full", h.getMemberCount() != null && h.getMemberCount() >= MAX_MEMBERS);
        m.put("latitude", h.getLatitude());
        m.put("longitude", h.getLongitude());
        m.put("addressName", h.getAddressName());
        m.put("startAt", h.getStartAt());
        m.put("status", h.getStatus());
        m.put("memberCount", h.getMemberCount() == null ? 0 : h.getMemberCount());
        m.put("isMember", h.getIsMember() != null && h.getIsMember() > 0);
        m.put("distance", h.getDistance());
        m.put("conversationId", h.getConversationId());
        return m;
    }

    // ---------- 开局 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoamHangout create(Long memberId, String title, String description, String coverUrl,
                              BigDecimal latitude, BigDecimal longitude, String addressName, Date startAt) {
        requireLogin(memberId);
        if (imService.isPlatformBlocked(memberId)) throw new ServiceException("账号已被限制,不能开局", 403);
        String t = title == null ? "" : title.trim();
        if (t.length() < 2 || t.length() > 30) throw new ServiceException("标题 2–30 字", 400);
        String d = description == null ? null : description.trim();
        if (d != null && d.length() > 120) throw new ServiceException("说明最多 120 字", 400);
        if (latitude == null || longitude == null
                || latitude.doubleValue() < -90 || latitude.doubleValue() > 90
                || longitude.doubleValue() < -180 || longitude.doubleValue() > 180) {
            throw new ServiceException("请选择地点", 400);
        }
        if (roamHangoutMapper.countActiveByMember(memberId) >= MAX_ACTIVE_PER_MEMBER) {
            throw new ServiceException("同时最多开 " + MAX_ACTIVE_PER_MEMBER + " 个局,先关一个再开", 400);
        }
        // 文本同步机审:命中即拒,不落库(广场同款口径)
        String openid = openidOf(memberId);
        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(t, d), openid);
        if (secMsg != null) {
            // 机审「审不了」也返回非 null 文案:那不是「违规」,文案要分开,别把用户当违规者
            boolean unavailable = IWxContentSecurityService.isDetectionUnavailable(secMsg);
            throw new ServiceException(unavailable ? secMsg : "标题或说明含违规内容:" + secMsg, unavailable ? 503 : 400);
        }

        RoamHangout h = new RoamHangout();
        h.setMemberId(memberId);
        h.setTitle(t);
        h.setDescription(d);
        h.setCoverUrl(coverUrl);
        h.setLatitude(latitude);
        h.setLongitude(longitude);
        h.setAddressName(addressName);
        h.setStartAt(startAt);
        roamHangoutMapper.insert(h);
        Long convId = imService.getOrCreateHangoutGroup(h.getId(), memberId, memberId);
        roamHangoutMapper.updateConversationId(h.getId(), convId);
        h.setConversationId(convId);
        h.setStatus(RoamHangout.STATUS_OPEN);
        // 封面异步送检,命中由回调自动隐藏(WxContentSecurityServiceImpl BIZ_TYPE_HANGOUT 分支)
        if (coverUrl != null && !coverUrl.trim().isEmpty()) {
            try {
                wxContentSecurityService.submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_HANGOUT, h.getId(), coverUrl, openid);
            } catch (Exception e) {
                log.error("[附近的局] 封面送检异常,需人工复审: id={}", h.getId(), e);
            }
        }
        return h;
    }

    // ---------- 详情 / 加入 / 退出 / 关局 / 举报 ----------

    @Override
    public Map<String, Object> detail(Long viewerId, Long id) {
        requireLogin(viewerId);
        Long viewer = viewerId;
        RoamHangout h = roamHangoutMapper.selectById(id, viewer);
        if (h == null) throw new ServiceException("局不存在", 404);
        boolean isOwner = viewer.equals(h.getMemberId());
        boolean isMember = h.getIsMember() != null && h.getIsMember() > 0;
        if (!Integer.valueOf(RoamHangout.STATUS_OPEN).equals(h.getStatus()) && !isOwner && !isMember) {
            throw new ServiceException("这个局已经关了", 404);
        }
        Map<String, Object> out = hangoutItem(h);
        out.put("isOwner", isOwner);
        out.put("members", h.getConversationId() == null ? new ArrayList<Map<String, Object>>()
                : roamHangoutMapper.selectMemberBriefs(h.getConversationId(), h.getMemberId(), 6));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long join(Long memberId, Long id) {
        requireLogin(memberId);
        if (imService.isPlatformBlocked(memberId)) throw new ServiceException("账号已被限制,不能加入", 403);
        RoamHangout h = roamHangoutMapper.selectByIdForUpdate(id);
        if (h == null) throw new ServiceException("局不存在", 404);
        if (!Integer.valueOf(RoamHangout.STATUS_OPEN).equals(h.getStatus())) throw new ServiceException("这个局已经关了", 400);
        if (h.getConversationId() != null && imService.isActiveGroupMember(memberId, h.getConversationId())) {
            return h.getConversationId();   // 幂等
        }
        int n = h.getConversationId() == null ? 0 : roamHangoutMapper.countActiveMembers(h.getConversationId());
        if (n >= MAX_MEMBERS) throw new ServiceException("群已满(" + MAX_MEMBERS + " 人)", 400);
        Long convId = imService.getOrCreateHangoutGroup(h.getId(), memberId, h.getMemberId());
        if (h.getConversationId() == null) roamHangoutMapper.updateConversationId(h.getId(), convId);
        return convId;
    }

    @Override
    public void leave(Long memberId, Long id) {
        requireLogin(memberId);
        RoamHangout h = roamHangoutMapper.selectById(id, memberId);
        if (h == null) throw new ServiceException("局不存在", 404);
        if (memberId.equals(h.getMemberId())) throw new ServiceException("局主不能退出,只能关局", 400);
        imService.leaveHangoutGroup(id, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long memberId, Long id) {
        requireLogin(memberId);
        RoamHangout h = roamHangoutMapper.selectByIdForUpdate(id);
        if (h == null) throw new ServiceException("局不存在", 404);
        if (!memberId.equals(h.getMemberId())) throw new ServiceException("只有局主可以关局", 403);
        if (Integer.valueOf(RoamHangout.STATUS_OPEN).equals(h.getStatus())) {
            roamHangoutMapper.updateStatus(id, RoamHangout.STATUS_CLOSED);
        }
        imService.closeHangoutGroup(id);
    }

    @Override
    public Long report(Long memberId, Long id, String reason) {
        requireLogin(memberId);
        RoamHangout h = roamHangoutMapper.selectById(id, memberId);
        if (h == null) throw new ServiceException("局不存在", 404);
        return auditTaskService.reportContent(AuditTask.BIZ_HANGOUT, id, memberId,
                reason == null || reason.trim().isEmpty() ? "用户举报" : reason.trim());
    }

    // ---------- 过期 ----------

    @Override
    public int expireDue(Date now) {
        List<RoamHangout> due = roamHangoutMapper.selectExpired(now == null ? new Date() : now);
        int n = 0;
        if (due == null) return 0;
        for (RoamHangout h : due) {
            try {
                roamHangoutMapper.updateStatus(h.getId(), RoamHangout.STATUS_CLOSED);
                imService.closeHangoutGroup(h.getId());
                n++;
            } catch (Exception e) {
                log.error("[附近的局] 过期关局失败 id={}", h.getId(), e);
            }
        }
        return n;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moderate(Long id, boolean hidden) {
        // 无效举报只结束该举报；任何来源的处罚都不能在这里撤销。
        if (!hidden) return;
        RoamHangout h = roamHangoutMapper.selectByIdForUpdate(id);
        if (h == null) return;
        if (Integer.valueOf(RoamHangout.STATUS_OPEN).equals(h.getStatus())) {
            if (roamHangoutMapper.updateStatus(id, RoamHangout.STATUS_HIDDEN) != 1) {
                throw new ServiceException("组局处罚保存失败");
            }
        }
        imService.closeHangoutGroup(id);
    }

    @Override
    public int closeAllByMember(Long memberId) {
        if (memberId == null || memberId <= 0) return 0;
        List<RoamHangout> open = roamHangoutMapper.selectOpenByMember(memberId);
        int n = 0;
        if (open == null) return 0;
        for (RoamHangout h : open) {
            roamHangoutMapper.updateStatus(h.getId(), RoamHangout.STATUS_CLOSED);
            imService.closeHangoutGroup(h.getId());
            n++;
        }
        return n;
    }

    // ---------- helpers ----------

    private static void requireLogin(Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
    }

    private String openidOf(Long memberId) {
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        return m == null ? null : m.getWxOpenId();
    }
}

package com.chengyinhub.business.sponsoredquest;

import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.sponsoredquest.mapper.SponsoredQuestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S3b sponsored quests, operations side: create, edit, publish, pause, end. Every write appends one platform audit
 * row in the same transaction. Creating or publishing a campaign never charges anyone: charges live in
 * {@link SponsoredQuestBudgetService} behind the money switch.
 */
@Service
public class SponsoredQuestAdminService {
    public static final int DRAFT = 0;
    public static final int LIVE = 1;
    public static final int PAUSED = 2;
    public static final int ENDED = 3;
    public static final int MAX_PLACES = 20;
    public static final int ADMIN_ROWS = 200;
    public static final long MAX_BUDGET_CENT = 100_000_000_00L;
    public static final long MAX_DURATION_MS = 366L * 86_400_000L;

    private final SponsoredQuestMapper mapper;
    private final OpsConsoleAudit audit;

    @Autowired
    public SponsoredQuestAdminService(SponsoredQuestMapper mapper, OpsConsoleAudit audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = mapper.selectAdminList(ADMIN_ROWS);
        if (rows == null) return Collections.emptyList();
        for (Map<String, Object> row : rows) SponsoredQuestService.labelled(row);
        return rows;
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = requireCampaign(id);
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("placeIds", orEmpty(mapper.selectPlaceIds(id)));
        return SponsoredQuestService.labelled(out);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(SponsoredQuestCampaign campaign, String operator, Long sysUserId) {
        List<Long> places = validate(campaign);
        if (mapper.insertCampaign(campaign, by(operator)) != 1 || campaign.getId() == null) {
            throw new IllegalStateException("赞助任务保存失败");
        }
        writePlaces(campaign.getId(), places);
        audit.record(sysUserId, "SPONSORED_QUEST_CREATE", "SPONSORED_QUEST", campaign.getId(), null, view(campaign, places), null);
        return campaign.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(SponsoredQuestCampaign campaign, String operator, Long sysUserId) {
        if (campaign == null || campaign.getId() == null || campaign.getId() <= 0) throw new IllegalArgumentException("请选择赞助任务");
        Map<String, Object> before = requireCampaign(campaign.getId());
        if (intValue(before.get("status")) == ENDED) throw new IllegalArgumentException("已结束的赞助任务不能再修改");
        List<Long> places = validate(campaign);
        List<Long> beforePlaces = orEmpty(mapper.selectPlaceIds(campaign.getId()));
        if (mapper.updateCampaign(campaign, by(operator)) != 1) throw new IllegalArgumentException("已结束的赞助任务不能再修改");
        writePlaces(campaign.getId(), places);
        Map<String, Object> beforeView = new LinkedHashMap<>(before);
        beforeView.put("placeIds", beforePlaces);
        audit.record(sysUserId, "SPONSORED_QUEST_UPDATE", "SPONSORED_QUEST", campaign.getId(), beforeView, view(campaign, places), null);
    }

    /** Publish (draft or paused → live), pause (live → paused) or end (any → ended, final). */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, int target, String operator, Long sysUserId, String reason) {
        Map<String, Object> before = requireCampaign(id);
        int from = intValue(before.get("status"));
        if (!allowed(from, target)) throw new IllegalArgumentException("当前状态不能这样操作");
        if (target == LIVE) {
            Date end = asDate(before.get("endTime"));
            if (end != null && !end.after(new Date())) throw new IllegalArgumentException("结束时间已过,请先修改投放时间");
            List<Long> places = orEmpty(mapper.selectPlaceIds(id));
            if (places.isEmpty() || mapper.countPublishedPlaces(places) != places.size()) {
                throw new IllegalArgumentException("有地点已下架或未通过审核,请先修改地点");
            }
        }
        if (mapper.updateStatus(id, from, target, by(operator)) != 1) throw new IllegalArgumentException("状态已被他人修改,请刷新");
        audit.record(sysUserId, "SPONSORED_QUEST_STATUS", "SPONSORED_QUEST", id,
                Map.of("status", from), Map.of("status", target), reason);
    }

    static boolean allowed(int from, int target) {
        return switch (target) {
            case LIVE -> from == DRAFT || from == PAUSED;
            case PAUSED -> from == LIVE;
            case ENDED -> from == DRAFT || from == LIVE || from == PAUSED;
            default -> false;
        };
    }

    /** Validates and normalizes the campaign in place; returns the distinct place ids in order. */
    List<Long> validate(SponsoredQuestCampaign c) {
        if (c == null) throw new IllegalArgumentException("请填写赞助任务");
        c.setSponsorName(required(c.getSponsorName(), 64, "赞助方"));
        c.setTitle(required(c.getTitle(), 64, "任务标题"));
        c.setContent(required(c.getContent(), 1000, "任务内容"));
        String cover = c.getCoverImg() == null ? null : c.getCoverImg().trim();
        if (cover != null && cover.isEmpty()) cover = null;
        if (cover != null && (cover.length() > 500 || !cover.startsWith("https://"))) throw new IllegalArgumentException("封面图需为 https 地址");
        c.setCoverImg(cover);
        if (c.getSponsorMerchantId() != null && c.getSponsorMerchantId() <= 0) c.setSponsorMerchantId(null);
        long budget = c.getBudgetCent() == null ? 0L : c.getBudgetCent();
        if (budget < 0 || budget > MAX_BUDGET_CENT) throw new IllegalArgumentException("预算无效");
        c.setBudgetCent(budget);
        if (c.getStartTime() == null || c.getEndTime() == null) throw new IllegalArgumentException("请填写投放开始和结束时间");
        if (!c.getEndTime().after(c.getStartTime())) throw new IllegalArgumentException("结束时间必须晚于开始时间");
        if (c.getEndTime().getTime() - c.getStartTime().getTime() > MAX_DURATION_MS) throw new IllegalArgumentException("单次投放最长一年");
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : c.getPlaceIds()) {
            if (id == null || id <= 0) throw new IllegalArgumentException("地点编号无效");
            ids.add(id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("请至少选择一个地点");
        if (ids.size() > MAX_PLACES) throw new IllegalArgumentException("一个赞助任务最多 " + MAX_PLACES + " 个地点");
        List<Long> places = new ArrayList<>(ids);
        if (mapper.countPublishedPlaces(places) != places.size()) throw new IllegalArgumentException("只能选择已上架、已通过审核的地点");
        c.setPlaceIds(places);
        return places;
    }

    private void writePlaces(Long id, List<Long> places) {
        mapper.deletePlaces(id);
        int sort = 0;
        for (Long poiId : places) {
            if (mapper.insertPlace(id, poiId, sort++) != 1) throw new IllegalStateException("赞助任务地点保存失败");
        }
    }

    private Map<String, Object> requireCampaign(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("请选择赞助任务");
        Map<String, Object> row = mapper.selectById(id);
        if (row == null) throw new IllegalArgumentException("赞助任务不存在");
        return row;
    }

    private static Map<String, Object> view(SponsoredQuestCampaign c, List<Long> places) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sponsorName", c.getSponsorName());
        v.put("sponsorMerchantId", c.getSponsorMerchantId());
        v.put("title", c.getTitle());
        v.put("content", c.getContent());
        v.put("coverImg", c.getCoverImg());
        v.put("budgetCent", c.getBudgetCent());
        v.put("startTime", c.getStartTime());
        v.put("endTime", c.getEndTime());
        v.put("placeIds", places);
        return v;
    }

    private static String required(String value, int max, String name) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("请填写" + name);
        if (v.length() > max) throw new IllegalArgumentException(name + "最多 " + max + " 个字");
        return v;
    }

    static int intValue(Object value) {
        Long v = SponsoredQuestService.asLong(value);
        return v == null ? -1 : v.intValue();
    }

    static Date asDate(Object value) {
        if (value instanceof Date d) return d;
        if (value instanceof java.time.LocalDateTime t) return Date.from(t.atZone(java.time.ZoneId.systemDefault()).toInstant());
        return null;
    }

    private static String by(String operator) {
        return operator == null ? "" : operator;
    }

    private static <T> List<T> orEmpty(List<T> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }
}

package com.chengyinhub.business.service.impl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.dto.MerchantInsightCrowdFact;
import com.chengyinhub.business.domain.dto.MerchantInsightSupplyFact;
import com.chengyinhub.business.domain.dto.MerchantInsightVisitFact;
import com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp;
import com.chengyinhub.business.domain.vo.ai.MerchantInsightResp;
import com.chengyinhub.business.mapper.MerchantInsightMapper;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.IMerchantInsightService;
import com.chengyinhub.business.service.ai.AiServiceException;

/**
 * 商家店铺参谋:facts 纯 SQL 聚合(必出)→ AI 解读(可降级)→ 整份缓存(当日)。
 *
 * 设计约束(真源 = vault04《商家AI店铺参谋》):
 * - 事实包只含聚合统计,禁止出现 nickname/phone/member 明细列表(有 PII 白名单测试锁着);
 * - 核销人数 < 10 走 lowSample 闸:summary 强制换固定引导模板,闸在服务端不在 prompt;
 * - AI 失败/配额耗尽 → facts 照常返回、ai=null;此时**不写缓存**,否则失败会被锁一整天;
 * - 缓存 TTL 到本地次日零点(「次日更新」语义),不是滚动 24h。
 *
 * @author chengyinhub
 */
@Service
public class MerchantInsightServiceImpl implements IMerchantInsightService
{
    private static final Logger log = LoggerFactory.getLogger(MerchantInsightServiceImpl.class);

    private static final int WINDOW_DAYS = 30;
    /** 店铺参谋主卡可切换的时间窗(自然日,含今天) */
    private static final int[] TREND_WINDOWS = { 7, 30, 90 };
    private static final int LOW_SAMPLE_THRESHOLD = 10;
    private static final String CACHE_PREFIX = "ai:merchant:insight:v3:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String LOW_SAMPLE_SUMMARY =
            "近30天到店核销的顾客还不多,以下解读仅供参考。建议先通过合作中心承接主题、完善店铺装修来积累客流,数据多了参谋会更准。";

    @Autowired
    private MerchantInsightMapper merchantInsightMapper;

    @Autowired
    private IAiCreatorService aiCreatorService;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private com.chengyinhub.business.mapper.MmsMerchantMapper mmsMerchantMapper;

    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;

    @Autowired
    private com.chengyinhub.business.service.IMerchantLeadService merchantLeadService;

    @Autowired
    private com.chengyinhub.business.service.ITopicContentPublisherProjectionService
            topicContentPublisherProjectionService;

    @Override
    public MerchantInsightResp insight(Long merchantMemberId)
    {
        LocalDateTime now = LocalDateTime.now();
        String cacheKey = CACHE_PREFIX + merchantMemberId + ":" + now.toLocalDate().format(DAY_FMT);
        // 承接档案(店铺大小/适合类型/诉求)复用装修域 coop-profile 的存量字段,不新建表。
        // 档案在别页保存,当日缓存不会随之失效 ⇒ 展示层 profile 永远实时读,只有喂 AI 的
        // facts.profile 跟随缓存(改档案对 AI 建议次日生效,MVP 接受)。
        JSONObject profileView = buildProfileView(merchantMemberId);
        MerchantInsightResp cached = readCache(cacheKey);
        if (cached != null)
        {
            cached.setProfile(profileView);
            // facts/AI 可按日缓存，『招商中』不能：报名截止可能在当天跨过，开关也可能被关闭。
            // 用当前候选池重放缓存里的 AI 选择；失效选择沿用既有规则丢弃并回退到当前池。
            List<com.chengyinhub.business.domain.CmsTopic> topicPool = recruitingTopicPool(merchantMemberId);
            AiMerchantInsightResp cachedAi = cached.getAi();
            cached.setRecommendedTopics(fillTopicPicks(
                    cachedAi == null ? null : cachedAi.getRecommendedTopics(), topicPool));
            return cached;
        }

        MerchantInsightResp resp = new MerchantInsightResp();
        resp.setGeneratedAt(now.format(TIME_FMT));
        resp.setProfile(profileView);

        Date since = daysAgo(WINDOW_DAYS);
        List<MerchantInsightVisitFact> visits = merchantInsightMapper.selectVisitFacts(merchantMemberId, since);
        List<MerchantInsightCrowdFact> crowd = merchantInsightMapper.selectCrowdFacts(merchantMemberId, since);
        List<MerchantInsightSupplyFact> supply = merchantInsightMapper.selectSupplyFacts(merchantMemberId);
        List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> events =
                merchantInsightMapper.selectVisitorEvents(merchantMemberId);

        JSONObject facts = buildFacts(visits, crowd, supply, events, since);
        // 店铺档案进事实包(纯档案字段无 PII):AI 据此推「适合的主题/联动商铺」
        facts.put("profile", profileFactsOf(profileView));
        // 真实候选池:招商中主题 + 附近商家(只带 id/name/category,不带 phone 等联系方式)。
        // AI 只能从池里挑,推荐区因此永远是「可点的真实实体」,不是编的文字。
        List<com.chengyinhub.business.domain.CmsTopic> topicPool = recruitingTopicPool(merchantMemberId);
        List<com.chengyinhub.business.domain.vo.NearbyMerchantVO> partnerPool =
                nearbyPartnerPool(merchantMemberId);
        facts.put("candidates", candidatesFactsOf(topicPool, partnerPool));
        resp.setFacts(facts);
        // 单客行只做页面展示,绝不放进 facts(facts 会整包喂给模型,昵称头像不许进 prompt)
        resp.setRecentVisitors(buildRecentVisitors(events));
        // 主卡 7/30/90 天走势:与 recentVisitors 一样只做页面展示,不进 facts、不喂 AI
        resp.setTrends(buildTrends(events, now.toLocalDate()));

        boolean lowSample = facts.getBooleanValue("lowSample", true);
        AiMerchantInsightResp ai = null;
        try
        {
            ai = aiCreatorService.merchantInsight(facts.toJSONString(), merchantMemberId);
            if (lowSample)
            {
                ai.setSummary(LOW_SAMPLE_SUMMARY);
            }
            resp.setAi(ai);
        }
        catch (AiServiceException e)
        {
            // 降级:数据区不连坐;不缓存,下次请求还有机会拿到 AI(配额次日也会重置)。
            resp.setAiError(e.getMessage());
        }
        // 推荐区是必出层:AI 在 → 按它挑的 id 反幻觉校验回填;AI 挂/没挑 → 候选前 3 兜底(无理由)。
        resp.setRecommendedTopics(fillTopicPicks(ai == null ? null : ai.getRecommendedTopics(), topicPool));
        resp.setRecommendedPartners(fillPartnerPicks(ai == null ? null : ai.getRecommendedPartners(), partnerPool));
        if (ai != null)
        {
            writeCache(cacheKey, resp, now);
        }
        return resp;
    }

    @Override
    public JSONObject dashboard(Long merchantMemberId)
    {
        Date since = daysAgo(WINDOW_DAYS);
        List<MerchantInsightVisitFact> visits = merchantInsightMapper.selectVisitFacts(merchantMemberId, since);
        List<MerchantInsightSupplyFact> supply = merchantInsightMapper.selectSupplyFacts(merchantMemberId);
        JSONObject data = new JSONObject();
        data.put("windowDays", WINDOW_DAYS);
        // 曝光 = 我有节点的主题近窗口浏览数(店铺页/分享暂无埋点,不造数 —— 有埋点后再补字段)
        data.put("exposure", merchantInsightMapper.countTopicExposure(merchantMemberId, since));
        // 到店/核销率/时段/复购率:与店铺参谋同一 facts 口径,数字直出不走 AI
        data.put("checkin", buildCheckinFacts(visits));
        data.put("supplyCount", supply == null ? 0 : supply.size());
        return data;
    }

    /**
     * 店铺承接档案视图(实时读 mms_merchant 存量字段):
     * configured = capacity 或 suitActivityTypes 任一有值;未配置时前端置顶引导去装修-合作设置页。
     */
    private JSONObject buildProfileView(Long merchantMemberId)
    {
        JSONObject view = new JSONObject();
        com.chengyinhub.business.domain.MmsMerchant m =
                mmsMerchantMapper.selectMmsMerchantByMemberId(merchantMemberId);
        if (m == null)
        {
            view.put("configured", false);
            return view;
        }
        boolean hasCapacity = m.getCapacity() != null && m.getCapacity() > 0;
        boolean hasTypes = m.getSuitActivityTypes() != null && !m.getSuitActivityTypes().trim().isEmpty();
        view.put("configured", hasCapacity || hasTypes);
        view.put("capacity", m.getCapacity());
        view.put("suitActivityTypes", m.getSuitActivityTypes());
        view.put("demand", m.getDemand());
        // 页头问候区「Hi，店名」+ 店铺头像;只在展示视图,profileFactsOf 不带它们进 AI
        view.put("storeName", m.getName());
        view.put("logo", m.getLogo());
        return view;
    }

    /** 喂 AI 的档案块 = 视图去掉 configured 标志(那是前端引导用的) */
    private JSONObject profileFactsOf(JSONObject view)
    {
        JSONObject out = new JSONObject();
        out.put("capacity", view.get("capacity"));
        out.put("suitActivityTypes", view.get("suitActivityTypes"));
        out.put("demand", view.get("demand"));
        return out;
    }

    // ---- 推荐候选池与回填 ----

    private static final int CANDIDATE_LIMIT = 8;
    private static final int PICK_LIMIT = 3;

    /** 招商中主题池:口径同 marketing-home 的 recruiting()(上架/招商开/俱乐部在营) */
    private List<com.chengyinhub.business.domain.CmsTopic> recruitingTopicPool(Long merchantMemberId)
    {
        com.chengyinhub.business.domain.CmsTopic query = new com.chengyinhub.business.domain.CmsTopic();
        query.setDelFlag(0);
        query.setStatus(1);
        query.setMerchantStatus(1);
        query.setActiveClubOnly(true);
        // 稿 J4 的主题级开关与稿 M 的俱乐部级开关。此前两个都写得进去、没人读 ——
        // 主题详情上那句「关掉后本主题不再出现在商家的可承接列表里」是句空话。
        query.setMerchantPoolOnly(true);
        List<com.chengyinhub.business.domain.CmsTopic> topics = cmsTopicService.selectCmsTopicList(query);
        if (topics == null) return new ArrayList<>();
        // 商家不能承接自己的主题；AI 候选和无 AI 推荐使用同一份过滤后的池。
        // 合作中心以 merchantSignUpEndDate 判『已截止』；活动 start/end 与商业生命周期
        // recruitDeadline 都不是商家报名截止。推荐卡固定写『招商中』，所以截止项必须在这里剔除。
        Date recruitNow = new Date();
        topics = new ArrayList<>(topics);
        topics.removeIf(topic -> topic == null
                || merchantMemberId.equals(topic.getMemberId())
                || (topic.getMerchantSignUpEndDate() != null
                    && !topic.getMerchantSignUpEndDate().after(recruitNow)));
        // CU-M-85(2026-09-24 走查):推荐卡要能区分同名主题的场次,主办方名走 app 侧同一份
        // 「来源」投影(内容是产品槽,不在 cms_topic 表上)。池子 ≤8 条,一次批量查询。
        topicContentPublisherProjectionService.project(topics);
        return topics.size() <= CANDIDATE_LIMIT ? topics : topics.subList(0, CANDIDATE_LIMIT);
    }

    /** 附近商家池:商家自己没坐标则为空;排除自己 */
    private List<com.chengyinhub.business.domain.vo.NearbyMerchantVO> nearbyPartnerPool(Long merchantMemberId)
    {
        com.chengyinhub.business.domain.MmsMerchant self =
                mmsMerchantMapper.selectMmsMerchantByMemberId(merchantMemberId);
        List<com.chengyinhub.business.domain.vo.NearbyMerchantVO> out = new ArrayList<>();
        if (self == null || self.getLocationLat() == null || self.getLocationLng() == null) return out;
        List<com.chengyinhub.business.domain.vo.NearbyMerchantVO> nearby = merchantLeadService.nearby(
                self.getLocationLat(), self.getLocationLng(), 3000, CANDIDATE_LIMIT + 1, false);
        if (nearby == null) return out;
        for (com.chengyinhub.business.domain.vo.NearbyMerchantVO v : nearby)
        {
            if (v == null || v.getId() == null) continue;
            if (merchantMemberId.equals(v.getMemberId())) continue;
            out.add(v);
            if (out.size() >= CANDIDATE_LIMIT) break;
        }
        return out;
    }

    /** 喂 AI 的候选池:只带 id/name/category,绝不带 phone/address 等联系信息 */
    private JSONObject candidatesFactsOf(List<com.chengyinhub.business.domain.CmsTopic> topicPool,
                                         List<com.chengyinhub.business.domain.vo.NearbyMerchantVO> partnerPool)
    {
        JSONArray topics = new JSONArray();
        for (com.chengyinhub.business.domain.CmsTopic t : topicPool)
        {
            JSONObject row = new JSONObject();
            row.put("id", t.getId());
            row.put("name", t.getName());
            topics.add(row);
        }
        JSONArray partners = new JSONArray();
        for (com.chengyinhub.business.domain.vo.NearbyMerchantVO v : partnerPool)
        {
            JSONObject row = new JSONObject();
            row.put("id", v.getId());
            row.put("name", v.getName());
            row.put("category", v.getCategory());
            partners.add(row);
        }
        JSONObject out = new JSONObject();
        out.put("recruitingTopics", topics);
        out.put("nearbyMerchants", partners);
        return out;
    }

    /** 主题推荐回填:AI 挑的 id 必须 ∈ 池(反幻觉,越界丢弃);没有合法挑选 → 池前 3 兜底 */
    private List<JSONObject> fillTopicPicks(List<AiMerchantInsightResp.InsightPick> picks,
                                            List<com.chengyinhub.business.domain.CmsTopic> pool)
    {
        Map<Long, com.chengyinhub.business.domain.CmsTopic> byId = new LinkedHashMap<>();
        for (com.chengyinhub.business.domain.CmsTopic t : pool) byId.put(t.getId(), t);
        List<JSONObject> out = new ArrayList<>();
        if (picks != null)
        {
            for (AiMerchantInsightResp.InsightPick p : picks)
            {
                com.chengyinhub.business.domain.CmsTopic t = p == null ? null : byId.get(p.getId());
                if (t == null)
                {
                    log.info("[MerchantInsight] dropped hallucinated topic pick id={}", p == null ? null : p.getId());
                    continue;
                }
                out.add(topicRow(t, p.getReason()));
                if (out.size() >= PICK_LIMIT) break;
            }
        }
        if (out.isEmpty())
        {
            for (com.chengyinhub.business.domain.CmsTopic t : pool)
            {
                out.add(topicRow(t, null));
                if (out.size() >= PICK_LIMIT) break;
            }
        }
        return out;
    }

    private JSONObject topicRow(com.chengyinhub.business.domain.CmsTopic t, String reason)
    {
        JSONObject row = new JSONObject();
        row.put("topicId", t.getId());
        row.put("name", t.getName());
        row.put("coverUrl", t.getImgUrl());
        row.put("reason", reason);
        // CU-M-85(2026-09-24 走查):同名主题(两期「探店日一期」)光看名字分不出是哪一场 ——
        // 合作广场的路线卡拿的就是这两个日期字段,推荐卡按同一份口径补起止与主办方。
        row.put("startDate", t.getStartDate());
        row.put("endDate", t.getEndDate());
        row.put("hostName", t.getContentPublisherName());
        return row;
    }

    /** 商家推荐回填:同上;展示带品类与距离,跳名片页用 mms id */
    private List<JSONObject> fillPartnerPicks(List<AiMerchantInsightResp.InsightPick> picks,
                                              List<com.chengyinhub.business.domain.vo.NearbyMerchantVO> pool)
    {
        Map<Long, com.chengyinhub.business.domain.vo.NearbyMerchantVO> byId = new LinkedHashMap<>();
        for (com.chengyinhub.business.domain.vo.NearbyMerchantVO v : pool) byId.put(v.getId(), v);
        List<JSONObject> out = new ArrayList<>();
        if (picks != null)
        {
            for (AiMerchantInsightResp.InsightPick p : picks)
            {
                com.chengyinhub.business.domain.vo.NearbyMerchantVO v = p == null ? null : byId.get(p.getId());
                if (v == null)
                {
                    log.info("[MerchantInsight] dropped hallucinated partner pick id={}", p == null ? null : p.getId());
                    continue;
                }
                out.add(partnerRow(v, p.getReason()));
                if (out.size() >= PICK_LIMIT) break;
            }
        }
        if (out.isEmpty())
        {
            for (com.chengyinhub.business.domain.vo.NearbyMerchantVO v : pool)
            {
                out.add(partnerRow(v, null));
                if (out.size() >= PICK_LIMIT) break;
            }
        }
        return out;
    }

    private JSONObject partnerRow(com.chengyinhub.business.domain.vo.NearbyMerchantVO v, String reason)
    {
        JSONObject row = new JSONObject();
        row.put("merchantId", v.getId());
        // 商家主页 canonical 主体是 memberId(merchantHomeUrl),不是商家档案主键
        row.put("memberId", v.getMemberId());
        row.put("name", v.getName());
        row.put("category", v.getCategory());
        row.put("tags", v.getTags());
        row.put("distanceM", v.getDistance() == null ? null : Math.round(v.getDistance()));
        row.put("reason", reason);
        // 商家卡定稿(Figma A1)要封面/logo/城市角色/slogan/营业状态,nearby VO 不带 ⇒ 按 mms id 补查(≤3 行)
        com.chengyinhub.business.domain.MmsMerchant m = mmsMerchantMapper.selectMmsMerchantById(v.getId());
        if (m != null)
        {
            row.put("coverImage", m.getCoverImage());
            row.put("logo", m.getLogo());
            row.put("cityRole", m.getCityRole());
            row.put("slogan", m.getSlogan());
            row.put("businessStatus", m.getBusinessStatus());
        }
        return row;
    }

    // ---- facts 聚合(全部纯 Java,数据量 = 单商家窗口内行数,内存聚合无压力) ----

    private JSONObject buildFacts(List<MerchantInsightVisitFact> visits,
                                  List<MerchantInsightCrowdFact> crowd,
                                  List<MerchantInsightSupplyFact> supply,
                                  List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> events,
                                  Date since)
    {
        JSONObject facts = new JSONObject();
        facts.put("window", "近" + WINDOW_DAYS + "天");
        facts.put("attribution", buildAttribution(events, since));
        facts.put("split", buildVisitorSplit(events, since));
        facts.put("sources", buildTopicSources(events, since));
        facts.put("checkin", buildCheckinFacts(visits));
        facts.put("crowd", buildCrowdFacts(crowd));
        facts.put("supply", buildSupplyFacts(supply));
        int redeemMembers = countRedeemMembers(visits);
        facts.put("sampleMembers", redeemMembers);
        facts.put("lowSample", redeemMembers < LOW_SAMPLE_THRESHOLD);
        return facts;
    }

    /** 归因价值卡:本期(近30天)与上期(30-60天前)的 到店玩家/打卡/核销 三数,环比在前端算 */
    private JSONObject buildAttribution(List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> events,
                                        Date since)
    {
        Date prevSince = daysAgo(WINDOW_DAYS * 2);
        Set<Long> curMembers = new HashSet<>();
        Set<Long> prevMembers = new HashSet<>();
        int curCheckins = 0, prevCheckins = 0, curRedeems = 0, prevRedeems = 0;
        for (com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e : events)
        {
            Date at = eventAt(e);
            if (at == null) continue;
            boolean cur = !at.before(since);
            boolean prev = at.before(since) && !at.before(prevSince);
            if (cur && e.getMemberId() != null) curMembers.add(e.getMemberId());
            if (prev && e.getMemberId() != null) prevMembers.add(e.getMemberId());
            if (e.getCheckinAt() != null)
            {
                if (!e.getCheckinAt().before(since)) curCheckins++;
                else if (!e.getCheckinAt().before(prevSince)) prevCheckins++;
            }
            if (e.getRedeemedAt() != null)
            {
                if (!e.getRedeemedAt().before(since)) curRedeems++;
                else if (!e.getRedeemedAt().before(prevSince)) prevRedeems++;
            }
        }
        JSONObject out = new JSONObject();
        out.put("visitors", curMembers.size());
        out.put("checkins", curCheckins);
        out.put("redeems", curRedeems);
        out.put("prevVisitors", prevMembers.size());
        out.put("prevCheckins", prevCheckins);
        out.put("prevRedeems", prevRedeems);
        return out;
    }

    /** 新客 vs 回头客:窗口内到店的 member,窗口前来过 = 回头客 */
    private JSONObject buildVisitorSplit(List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> events,
                                         Date since)
    {
        Set<Long> current = new HashSet<>();
        Set<Long> before = new HashSet<>();
        for (com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e : events)
        {
            Date at = eventAt(e);
            if (at == null || e.getMemberId() == null) continue;
            if (at.before(since)) before.add(e.getMemberId());
            else current.add(e.getMemberId());
        }
        int returning = 0;
        for (Long m : current)
        {
            if (before.contains(m)) returning++;
        }
        JSONObject out = new JSONObject();
        out.put("newVisitors", current.size() - returning);
        out.put("returningVisitors", returning);
        return out;
    }

    /** 来源主题列表(窗口内,按打卡+核销事件数降序) */
    private JSONArray buildTopicSources(List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> events,
                                        Date since)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e : events)
        {
            Date at = eventAt(e);
            if (at == null || at.before(since) || e.getTopicName() == null) continue;
            counts.put(e.getTopicName(), counts.getOrDefault(e.getTopicName(), 0) + 1);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        JSONArray out = new JSONArray();
        for (Map.Entry<String, Integer> en : entries)
        {
            JSONObject row = new JSONObject();
            row.put("name", en.getKey());
            row.put("count", en.getValue());
            out.add(row);
        }
        return out;
    }

    /** Blackbird 式单客行:按 member 聚合全历史事件,取最近 5 位 */
    private List<MerchantInsightResp.RecentVisitor> buildRecentVisitors(
            List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> events)
    {
        Map<Long, com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> latest = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e : events)
        {
            Date at = eventAt(e);
            if (at == null || e.getMemberId() == null) continue;
            counts.put(e.getMemberId(), counts.getOrDefault(e.getMemberId(), 0) + 1);
            com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent old = latest.get(e.getMemberId());
            if (old == null || eventAt(old).before(at)) latest.put(e.getMemberId(), e);
        }
        List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> ordered = new ArrayList<>(latest.values());
        ordered.sort((a, b) -> eventAt(b).compareTo(eventAt(a)));
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm");
        List<MerchantInsightResp.RecentVisitor> out = new ArrayList<>();
        for (com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e : ordered)
        {
            MerchantInsightResp.RecentVisitor v = new MerchantInsightResp.RecentVisitor();
            v.setNickname(e.getNickname());
            v.setAvatar(e.getAvatar());
            v.setVisitCount(counts.get(e.getMemberId()));
            v.setTopicName(e.getTopicName());
            v.setLastAtText(fmt.format(eventAt(e)));
            out.add(v);
            if (out.size() >= 5) break;
        }
        return out;
    }

    /**
     * 主卡走势:每个时间窗(7/30/90 个自然日,含今天)给本期/上期的 到店玩家/打卡/核销 三数,
     * 外加逐日三数(daily 按日期升序,长度 = 窗口天数)。计数口径与 buildAttribution 相同:
     * 到店玩家按事件时刻去重 member,打卡按 checkinAt,核销按 redeemedAt;区别只在窗口按自然日切,
     * 逐日格才能和窗口合计对得上。逐日到店玩家是当天去重,跨天相加不等于窗口去重人数。
     */
    private JSONObject buildTrends(List<com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent> events,
                                   LocalDate today)
    {
        JSONArray windows = new JSONArray();
        DateTimeFormatter dayLabel = DateTimeFormatter.ofPattern("MM-dd");
        for (int days : TREND_WINDOWS)
        {
            LocalDate start = today.minusDays(days - 1L);
            LocalDate prevStart = start.minusDays(days);
            List<Set<Long>> dayMembers = new ArrayList<>();
            int[] dayCheckins = new int[days];
            int[] dayRedeems = new int[days];
            for (int i = 0; i < days; i++) dayMembers.add(new HashSet<>());
            Set<Long> curMembers = new HashSet<>();
            Set<Long> prevMembers = new HashSet<>();
            int curCheckins = 0, prevCheckins = 0, curRedeems = 0, prevRedeems = 0;
            for (com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e : events)
            {
                LocalDate at = localDay(eventAt(e));
                if (at != null && e.getMemberId() != null && !at.isAfter(today))
                {
                    if (!at.isBefore(start))
                    {
                        curMembers.add(e.getMemberId());
                        dayMembers.get(dayIndex(start, at)).add(e.getMemberId());
                    }
                    else if (!at.isBefore(prevStart)) prevMembers.add(e.getMemberId());
                }
                LocalDate checkin = localDay(e.getCheckinAt());
                if (checkin != null && !checkin.isAfter(today))
                {
                    if (!checkin.isBefore(start)) { curCheckins++; dayCheckins[dayIndex(start, checkin)]++; }
                    else if (!checkin.isBefore(prevStart)) prevCheckins++;
                }
                LocalDate redeemed = localDay(e.getRedeemedAt());
                if (redeemed != null && !redeemed.isAfter(today))
                {
                    if (!redeemed.isBefore(start)) { curRedeems++; dayRedeems[dayIndex(start, redeemed)]++; }
                    else if (!redeemed.isBefore(prevStart)) prevRedeems++;
                }
            }
            JSONArray daily = new JSONArray();
            for (int i = 0; i < days; i++)
            {
                JSONObject d = new JSONObject();
                d.put("date", start.plusDays(i).format(dayLabel));
                d.put("visitors", dayMembers.get(i).size());
                d.put("checkins", dayCheckins[i]);
                d.put("redeems", dayRedeems[i]);
                daily.add(d);
            }
            JSONObject w = new JSONObject();
            w.put("days", days);
            w.put("visitors", curMembers.size());
            w.put("checkins", curCheckins);
            w.put("redeems", curRedeems);
            w.put("prevVisitors", prevMembers.size());
            w.put("prevCheckins", prevCheckins);
            w.put("prevRedeems", prevRedeems);
            w.put("daily", daily);
            windows.add(w);
        }
        JSONObject out = new JSONObject();
        out.put("windows", windows);
        return out;
    }

    private static LocalDate localDay(Date d)
    {
        return d == null ? null : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static int dayIndex(LocalDate start, LocalDate day)
    {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(start, day);
    }

    /** 事件时刻 = 打卡时间优先,无打卡取核销时间 */
    private Date eventAt(com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e)
    {
        return e.getCheckinAt() != null ? e.getCheckinAt() : e.getRedeemedAt();
    }

    private JSONObject buildCheckinFacts(List<MerchantInsightVisitFact> visits)
    {
        JSONObject checkin = new JSONObject();
        int entitlements = visits.size();
        int checkins = 0;
        int redeems = 0;
        Map<Integer, Integer> hourCounts = new HashMap<>();
        long waitSumMs = 0;
        int waitCount = 0;
        Map<Long, Integer> redeemsByMember = new HashMap<>();
        for (MerchantInsightVisitFact v : visits)
        {
            if (v.getCheckinAt() != null)
            {
                checkins++;
                Calendar c = Calendar.getInstance();
                c.setTime(v.getCheckinAt());
                int hour = c.get(Calendar.HOUR_OF_DAY);
                hourCounts.put(hour, hourCounts.getOrDefault(hour, 0) + 1);
            }
            if (v.getRedeemedAt() != null)
            {
                redeems++;
                if (v.getMemberId() != null)
                {
                    redeemsByMember.put(v.getMemberId(), redeemsByMember.getOrDefault(v.getMemberId(), 0) + 1);
                }
                if (v.getCheckinAt() != null)
                {
                    long diff = v.getRedeemedAt().getTime() - v.getCheckinAt().getTime();
                    if (diff >= 0)
                    {
                        waitSumMs += diff;
                        waitCount++;
                    }
                }
            }
        }
        checkin.put("total", checkins);
        checkin.put("redeemRate", com.chengyinhub.common.utils.SafeRatio.ratio(redeems, entitlements, 2));
        // 24 列在几十次样本下必然稀稀拉拉,收成 4 段(调研结论:早/午/晚/夜)
        String[] slotNames = { "早", "午", "晚", "夜" };
        int[] slotCounts = new int[4];
        for (Map.Entry<Integer, Integer> en : hourCounts.entrySet())
        {
            slotCounts[slotOf(en.getKey())] += en.getValue();
        }
        JSONArray slotBuckets = new JSONArray();
        for (int i = 0; i < 4; i++)
        {
            JSONObject b = new JSONObject();
            b.put("slot", slotNames[i]);
            b.put("count", slotCounts[i]);
            slotBuckets.add(b);
        }
        checkin.put("slotBuckets", slotBuckets);
        int redeemMembers = redeemsByMember.size();
        int repeatMembers = 0;
        for (Integer n : redeemsByMember.values())
        {
            if (n >= 2) repeatMembers++;
        }
        checkin.put("repeatRate", com.chengyinhub.common.utils.SafeRatio.ratio(repeatMembers, redeemMembers, 2));
        checkin.put("avgWaitMinutes", waitCount == 0 ? null : Math.round(waitSumMs / 60000.0 / waitCount));
        return checkin;
    }

    /** 早 6-11 / 午 11-16 / 晚 16-21 / 夜 21-6 */
    private int slotOf(int hour)
    {
        if (hour >= 6 && hour < 11) return 0;
        if (hour >= 11 && hour < 16) return 1;
        if (hour >= 16 && hour < 21) return 2;
        return 3;
    }

    private int countRedeemMembers(List<MerchantInsightVisitFact> visits)
    {
        Set<Long> members = new HashSet<>();
        for (MerchantInsightVisitFact v : visits)
        {
            if (v.getRedeemedAt() != null && v.getMemberId() != null) members.add(v.getMemberId());
        }
        return members.size();
    }

    private JSONObject buildCrowdFacts(List<MerchantInsightCrowdFact> crowd)
    {
        JSONObject out = new JSONObject();
        out.put("members", crowd.size());
        int male = 0, female = 0, sexUnknown = 0;
        Map<String, Integer> ageBuckets = new LinkedHashMap<>();
        Map<String, Integer> levelBuckets = new LinkedHashMap<>();
        Map<String, Double> interestWeights = new HashMap<>();
        for (MerchantInsightCrowdFact f : crowd)
        {
            Integer sex = f.getSex();
            if (sex != null && sex == 1) male++;
            else if (sex != null && sex == 2) female++;
            else sexUnknown++;
            String ageBucket = ageBucketOf(f.getBirthday());
            ageBuckets.put(ageBucket, ageBuckets.getOrDefault(ageBucket, 0) + 1);
            String levelBucket = f.getLevel() == null ? "未知" : ("L" + f.getLevel());
            levelBuckets.put(levelBucket, levelBuckets.getOrDefault(levelBucket, 0) + 1);
            mergeInterestWeights(interestWeights, f.getCategoryWeightsJson());
        }
        int n = crowd.size();
        JSONObject sexRatio = new JSONObject();
        sexRatio.put("male", n == 0 ? 0 : round2((double) male / n));
        sexRatio.put("female", n == 0 ? 0 : round2((double) female / n));
        sexRatio.put("unknown", n == 0 ? 0 : round2((double) sexUnknown / n));
        out.put("sexRatio", sexRatio);
        out.put("ageBuckets", ageBuckets);
        out.put("levelBuckets", levelBuckets);
        out.put("interestTop", topInterests(interestWeights, 3));
        return out;
    }

    private void mergeInterestWeights(Map<String, Double> acc, String weightsJson)
    {
        if (weightsJson == null || weightsJson.trim().isEmpty()) return;
        try
        {
            JSONObject weights = JSON.parseObject(weightsJson);
            if (weights == null) return;
            for (String key : weights.keySet())
            {
                Double w = weights.getDouble(key);
                if (w != null) acc.put(key, acc.getOrDefault(key, 0.0) + w);
            }
        }
        catch (com.alibaba.fastjson2.JSONException e)
        {
            log.warn("[MerchantInsight] bad category_weights_json, skipped: {}", truncate(weightsJson, 80));
        }
    }

    private List<String> topInterests(Map<String, Double> weights, int limit)
    {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(weights.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Double> e : entries)
        {
            top.add(e.getKey());
            if (top.size() >= limit) break;
        }
        return top;
    }

    /** 五档 + 未知;birthday 空归「未知」 */
    private String ageBucketOf(Date birthday)
    {
        if (birthday == null) return "未知";
        Calendar b = Calendar.getInstance();
        b.setTime(birthday);
        Calendar today = Calendar.getInstance();
        int age = today.get(Calendar.YEAR) - b.get(Calendar.YEAR);
        if (today.get(Calendar.DAY_OF_YEAR) < b.get(Calendar.DAY_OF_YEAR)) age--;
        if (age < 0) return "未知";
        if (age < 18) return "<18";
        if (age < 25) return "18-24";
        if (age < 35) return "25-34";
        if (age < 45) return "35-44";
        return "45+";
    }

    private JSONObject buildSupplyFacts(List<MerchantInsightSupplyFact> supply)
    {
        JSONObject out = new JSONObject();
        out.put("activeOffers", supply.size());
        Set<String> topics = new HashSet<>();
        int quotaTotal = 0, quotaUsed = 0;
        for (MerchantInsightSupplyFact f : supply)
        {
            if (f.getTopicName() != null) topics.add(f.getTopicName());
            if (f.getQuotaTotal() != null) quotaTotal += f.getQuotaTotal();
            if (f.getQuotaUsed() != null) quotaUsed += f.getQuotaUsed();
        }
        out.put("topics", new ArrayList<>(topics));
        out.put("quotaUsedRate", com.chengyinhub.common.utils.SafeRatio.ratio(quotaUsed, quotaTotal, 2));
        return out;
    }

    // ---- 缓存 ----

    private MerchantInsightResp readCache(String key)
    {
        try
        {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) return null;
            return JSON.parseObject(String.valueOf(raw), MerchantInsightResp.class);
        }
        catch (RuntimeException e)
        {
            // 缓存只是省钱手段,读不到就现算,别让 Redis 故障拖垮功能。
            log.warn("[MerchantInsight] cache read failed key={}", key, e);
            return null;
        }
    }

    private void writeCache(String key, MerchantInsightResp resp, LocalDateTime now)
    {
        try
        {
            long ttl = Math.max(1L,
                    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds());
            redisTemplate.opsForValue().set(key, JSON.toJSONString(resp), ttl, TimeUnit.SECONDS);
        }
        catch (RuntimeException e)
        {
            log.warn("[MerchantInsight] cache write failed key={}", key, e);
        }
    }

    private Date daysAgo(int days)
    {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -days);
        return c.getTime();
    }

    private Double round2(double v)
    {
        return Math.round(v * 100) / 100.0;
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.dto.MerchantMarketingAnalyticsFact;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.IMerchantAggregateReadService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聚合现有商家能力，不创建第二套订单、客户或合作状态。
 * 所有发现列表默认公开脱敏；联系方式仅在请求主题与已接受邀约同时匹配时下发。
 */
@Service
public class MerchantAggregateReadServiceImpl implements IMerchantAggregateReadService {

    private static final ZoneId MARKETING_TIMEZONE = ZoneId.of("Asia/Shanghai");

    private final IMmsMerchantService merchantService;
    private final IClubService clubService;
    private final IClubMemberService clubMemberService;
    private final CoopInviteMapper coopInviteMapper;
    private final ICmsTopicService topicService;
    private final ISmsCouponService couponService;
    private final ICmsActivityService activityService;
    private final MerchantMarketingAnalyticsMapper marketingAnalyticsMapper;
    private final CmsTopicChapterMapper chapterMapper;
    /** CU-M-61:把 category_id 翻成品类名,商家卡无图时按品类出图标 */
    private final ISysCategoryService categoryService;

    public MerchantAggregateReadServiceImpl(IMmsMerchantService merchantService,
                                            IClubService clubService,
                                            IClubMemberService clubMemberService,
                                            CoopInviteMapper coopInviteMapper,
                                            ICmsTopicService topicService,
                                            ISmsCouponService couponService,
                                            ICmsActivityService activityService,
                                            MerchantMarketingAnalyticsMapper marketingAnalyticsMapper,
                                            CmsTopicChapterMapper chapterMapper,
                                            ISysCategoryService categoryService) {
        this.merchantService = merchantService;
        this.clubService = clubService;
        this.clubMemberService = clubMemberService;
        this.coopInviteMapper = coopInviteMapper;
        this.topicService = topicService;
        this.couponService = couponService;
        this.activityService = activityService;
        this.marketingAnalyticsMapper = marketingAnalyticsMapper;
        this.chapterMapper = chapterMapper;
        this.categoryService = categoryService;
    }

    @Override
    public Map<String, Object> relationHome(Long memberId, Long topicId,
                                            Double originLat, Double originLng, int limit) {
        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("merchants", discoverMerchants(memberId, originLat, originLng, limit));
        discovery.put("clubs", discoverClubs(limit));

        List<CoopInvite> invites = invitesFor(memberId);
        Date now = new Date();
        Set<Long> acceptedMerchantIds = new LinkedHashSet<>();
        Set<Long> acceptedClubIds = new LinkedHashSet<>();
        Set<Long> pendingInviteIds = new LinkedHashSet<>();
        List<Map<String, Object>> relationItems = new ArrayList<>();
        for (CoopInvite invite : invites) {
            Partner partner = partnerOf(invite, memberId);
            if (partner == null) continue;
            if (Integer.valueOf(1).equals(invite.getStatus())) {
                if ("merchant".equals(partner.type)) acceptedMerchantIds.add(partner.id);
                if ("club".equals(partner.type)) acceptedClubIds.add(partner.id);
                relationItems.add(relationItem(invite, partner, topicId));
            } else if (isPendingAt(invite, now)) {
                pendingInviteIds.add(invite.getId());
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("merchantCount", acceptedMerchantIds.size());
        stats.put("clubCount", acceptedClubIds.size());
        stats.put("pendingCoopCount", pendingInviteIds.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discovery", discovery);
        result.put("stats", stats);
        result.put("relations", relationItems);
        return result;
    }

    private List<Map<String, Object>> discoverMerchants(Long memberId, Double lat, Double lng, int limit) {
        List<? extends MmsMerchant> source;
        if (lat != null && lng != null) {
            MerchantSearchQuery query = new MerchantSearchQuery();
            query.setStatus(1L);
            query.setAccountStatus(1L);
            query.setDelFlag(0);
            query.setOriginLat(lat);
            query.setOriginLng(lng);
            source = safe(merchantService.selectMerchantListByDistance(query));
        } else {
            MmsMerchant query = new MmsMerchant();
            query.setStatus(1L);
            query.setAccountStatus(1L);
            query.setDelFlag(0);
            source = safe(merchantService.selectMmsMerchantList(query));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<MmsMerchant> kept = new ArrayList<>();
        for (MmsMerchant merchant : source) {
            if (memberId.equals(merchant.getMemberId())) continue;
            Map<String, Object> item = publicMerchant(merchant);
            if (merchant instanceof MerchantSearchQuery) {
                item.put("distance", ((MerchantSearchQuery) merchant).getDistance());
            }
            rows.add(item);
            kept.add(merchant);
            if (rows.size() >= limit) break;
        }
        // CU-M-61:一页一次批量查品类名(与 /api/merchant/list 同一形状),不按行查库
        fillCategoryNames(rows, kept);
        return rows;
    }

    /**
     * CU-M-61(2026-09-24 走查):「合作 → 商家」列表也要带品类名。
     *
     * <p>商家卡无封面/Logo 时按品类出兜底图标;这一页以前只下发 category_id,前端拿不到名字,
     * 于是书店和花店在列表里长得一模一样(同一只咖啡杯)。字段名与形状照
     * {@code ApiMerchantController#fillDiscoveryCategories},两条列表路共用一份前端归一化。
     * 品类查不到(未分类 / 分类已被删)就不写这个键,由前端退回中性店铺图标 —— 不冒充品类。
     */
    private void fillCategoryNames(List<Map<String, Object>> rows, List<MmsMerchant> sources) {
        if (rows.isEmpty() || sources.isEmpty()) return;
        Set<String> ids = new LinkedHashSet<>();
        for (MmsMerchant merchant : sources) {
            String csv = categoryIdOf(merchant);
            if (csv == null) continue;
            for (String part : csv.split(",")) {
                if (!part.trim().isEmpty()) ids.add(part.trim());
            }
        }
        if (ids.isEmpty()) return;
        List<SysCategory> categories = safe(categoryService.selectSysCategoryListByIds(ids.toArray(new String[0])));
        if (categories.isEmpty()) return;
        Map<String, PublicMemberVO.PublicCategoryVO> byId = new LinkedHashMap<>();
        for (SysCategory category : categories) {
            if (category == null || category.getId() == null) continue;
            PublicMemberVO.PublicCategoryVO item = new PublicMemberVO.PublicCategoryVO();
            item.setId(category.getId());
            item.setCategoryName(category.getCategoryName());
            byId.put(String.valueOf(category.getId()), item);
        }
        for (int index = 0; index < rows.size() && index < sources.size(); index++) {
            String csv = categoryIdOf(sources.get(index));
            if (csv == null) continue;
            List<PublicMemberVO.PublicCategoryVO> own = new ArrayList<>();
            for (String part : csv.split(",")) {
                PublicMemberVO.PublicCategoryVO hit = byId.get(part.trim());
                if (hit != null) own.add(hit);
            }
            if (!own.isEmpty()) rows.get(index).put("sysCategoryList", own);
        }
    }

    /** category_id 这一列历史上是 int,也存过 CSV;取值口径与控制器那条 fill 保持一致。 */
    private static String categoryIdOf(MmsMerchant merchant) {
        Object raw = merchant == null ? null : merchant.getCategoryId();
        if (raw == null) return null;
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private List<Map<String, Object>> discoverClubs(int limit) {
        Club query = new Club();
        query.setStatus(1L);
        query.setDelFlag(0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Club club : safe(clubService.selectClubList(query))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", club.getId());
            item.put("name", club.getName());
            item.put("logo", club.getLogo());
            item.put("city", club.getCity());
            item.put("address", club.getAddress());
            // 成员数走全端唯一口径:club.member_count 不含主理人,直接下发会让同一个
            // 俱乐部在合作目录里「0 位」、点进详情「1 名」(CU-M-94)。
            item.put("memberCount", clubMemberService.countEffectiveMembers(club));
            // 2026-08-20 俱乐部卡重设计消费的公开展示字段(/api/club/home 已对玩家公开同一批)。
            // topicCount 该查询不填,不投出去 —— 前端卡片按 null 整段隐藏,不渲染成 0
            item.put("cover", club.getCover());
            item.put("clubType", club.getClubType());
            item.put("level", club.getLevel());
            item.put("leaderName", club.getLeaderName());
            item.put("leaderLevel", club.getLeaderLevel());
            item.put("leaderTags", club.getLeaderTags());
            item.put("canDesignRoute", club.getCanDesignRoute());
            item.put("canNpc", club.getCanNpc());
            item.put("canMerchantCoop", club.getCanMerchantCoop());
            item.put("hasGuideCert", club.getHasGuideCert());
            item.put("keywords", club.getKeywords());
            rows.add(item);
            if (rows.size() >= limit) break;
        }
        return rows;
    }

    private List<CoopInvite> invitesFor(Long memberId) {
        CoopInvite sentQuery = new CoopInvite();
        sentQuery.setFromId(memberId);
        CoopInvite receivedQuery = new CoopInvite();
        receivedQuery.setToType("merchant");
        receivedQuery.setToId(memberId);
        Map<Long, CoopInvite> byId = new LinkedHashMap<>();
        for (CoopInvite invite : safe(coopInviteMapper.selectCoopInviteList(sentQuery))) {
            if (isInviteVisible(invite)) byId.put(invite.getId(), invite);
        }
        for (CoopInvite invite : safe(coopInviteMapper.selectCoopInviteList(receivedQuery))) {
            if (isInviteVisible(invite)) byId.put(invite.getId(), invite);
        }
        return new ArrayList<>(byId.values());
    }

    /** 与 expirePendingInvites 的双截止语义一致，只投影待办，不更新邀约事实。 */
    private boolean isPendingAt(CoopInvite invite, Date now) {
        if (!Integer.valueOf(0).equals(invite.getStatus())) return false;
        // SQL 的 invite_type != 2 不匹配 NULL；只有 del_flag=0 的行会被原任务过期。
        if (!Integer.valueOf(0).equals(invite.getDelFlag())
                || invite.getInviteType() == null || Integer.valueOf(2).equals(invite.getInviteType())) {
            return true;
        }
        boolean deadlineReached = invite.getExpireTime() != null && !invite.getExpireTime().after(now);
        boolean olderThan48Hours = invite.getCreateTime() != null
                && invite.getCreateTime().getTime() <= now.getTime() - 48L * 60L * 60L * 1000L;
        return !deadlineReached && !olderThan48Hours;
    }

    private boolean isInviteVisible(CoopInvite invite) {
        if (invite == null || Integer.valueOf(2).equals(invite.getDelFlag())) return false;
        if ("club".equals(invite.getFromType())
                && (invite.getFromId() == null || clubService.selectClubById(invite.getFromId()) == null)) {
            return false;
        }
        if ("club".equals(invite.getToType())
                && (invite.getToId() == null || clubService.selectClubById(invite.getToId()) == null)) {
            return false;
        }
        if (invite.getTopicId() != null) {
            CmsTopic topic = topicService.selectCmsTopicById(invite.getTopicId());
            if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) return false;
            if (topic.getClubId() != null && clubService.selectClubById(topic.getClubId()) == null) return false;
        }
        return true;
    }

    private Map<String, Object> relationItem(CoopInvite invite, Partner partner, Long requestedTopicId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", invite.getId());
        item.put("topicId", invite.getTopicId());
        item.put("partnerType", partner.type);
        item.put("status", invite.getStatus());

        Map<String, Object> contact = new LinkedHashMap<>();
        if ("merchant".equals(partner.type)) {
            MmsMerchant merchant = merchantService.selectMmsMerchantByMemberId(partner.id);
            if (merchant != null) {
                item.put("partnerId", merchant.getId());
                item.put("name", merchant.getName());
                item.put("logo", merchant.getLogo());
                if (mayRevealContact(invite, requestedTopicId)) {
                    putIfText(contact, "phone", merchant.getPhone());
                    putIfText(contact, "wechat", merchant.getWechat());
                }
            }
        } else if ("club".equals(partner.type)) {
            Club club = clubService.selectClubById(partner.id);
            if (club != null) {
                item.put("partnerId", club.getId());
                item.put("name", club.getName());
                item.put("logo", club.getLogo());
                if (mayRevealContact(invite, requestedTopicId)) {
                    putIfText(contact, "phone", club.getPhone());
                }
            }
        }
        if (!contact.isEmpty()) item.put("contact", contact);
        return item;
    }

    private boolean mayRevealContact(CoopInvite invite, Long requestedTopicId) {
        return requestedTopicId != null
                && requestedTopicId.equals(invite.getTopicId())
                && Integer.valueOf(1).equals(invite.getStatus());
    }

    private Partner partnerOf(CoopInvite invite, Long memberId) {
        if (memberId.equals(invite.getFromId())) {
            if (Integer.valueOf(1).equals(invite.getInviteType())) return supportedPartner("club", invite.getToId());
            if (Integer.valueOf(2).equals(invite.getInviteType())) return supportedPartner("merchant", invite.getToId());
            return supportedPartner(invite.getToType(), invite.getToId());
        }
        if ("merchant".equals(invite.getToType()) && memberId.equals(invite.getToId())) {
            if (Integer.valueOf(2).equals(invite.getInviteType())) return supportedPartner("merchant", invite.getFromId());
            return supportedPartner(invite.getFromType(), invite.getFromId());
        }
        return null;
    }

    private Partner supportedPartner(String type, Long id) {
        if (id == null || (!"merchant".equals(type) && !"club".equals(type))) return null;
        return new Partner(type, id);
    }

    @Override
    public Map<String, Object> marketingHome(Long memberId, Long merchantId, int windowDays) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recruiting", recruiting());
        result.put("coupons", couponStats(memberId, merchantId));
        result.put("content", contentStats(memberId));
        result.put("analytics", marketingAnalytics(memberId, windowDays));
        // 滚动发布兼容：旧版小程序仍会执行 home.funnel.map；不再返回旧累计/收入漏斗数据。
        result.put("funnel", Collections.emptyList());
        return result;
    }

    private Map<String, Object> recruiting() {
        CmsTopic query = new CmsTopic();
        query.setDelFlag(0);
        query.setStatus(1);
        query.setMerchantStatus(1);
        query.setActiveClubOnly(true);
        // 稿 J4 的主题级开关与稿 M 的俱乐部级开关。此前两个都写得进去、没人读 ——
        // 主题详情上那句「关掉后本主题不再出现在商家的可承接列表里」是句空话。
        query.setMerchantPoolOnly(true);
        List<CmsTopic> topics = safe(topicService.selectCmsTopicList(query));
        /* CU-M-132/133:招商已截止的项目过去既计入「N 个开放商机」,又占掉首屏 10 席 ——
           商家看到 15 个开放商机,点进广场却是一屏「已截止」,真正还能接的反而看不见。
           这里只改**计数口径**与**排列先后**,不改任何可见性闸(上面那四行 query 一个没动):
           已截止的路线仍然下发、仍然按「已截止」显示(2026-09-06 裁决:决定权在商家,不替他藏),
           只是不再冒充"开放",也不再挤掉未截止的。判据与小程序 routeCards 同一套。 */
        Date recruitNow = new Date();
        List<CmsTopic> ordered = new ArrayList<>();
        int openRecruitCount = 0;
        for (CmsTopic topic : topics) {
            if (isRecruitOpenAt(topic, recruitNow)) { ordered.add(topic); openRecruitCount += 1; }
        }
        for (CmsTopic topic : topics) {
            if (!isRecruitOpenAt(topic, recruitNow)) ordered.add(topic);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (CmsTopic topic : ordered) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", topic.getId());
            item.put("name", topic.getName());
            item.put("imgUrl", topic.getImgUrl());
            item.put("productType", topic.getProductType());
            item.put("merchantSignUpEndDate", topic.getMerchantSignUpEndDate());
            /* 2026-09-06 补四个字段:合作中心的路线卡(Figma 234:276)要商家一眼判断
               「要不要接这一站」—— 开始时间 / 在哪个城市 / 几站 / 还剩几席。
               四个都是 CmsTopic 上现成的列,原来只是没往这张卡里放,
               卡上因此只有名字和封面,商家得点进详情才知道值不值得接。
               ⚠️ 只加读、不改任何筛选条件:上面那四行 query 是可见性闸,一个都没动。 */
            /* ⚠️ recruitDeadline 与 merchantSignUpEndDate 是**同一个值的两个名字**:
               小程序 coop-center 的 routeCards 一直读 item.recruitDeadline,而这里只 put 了
               merchantSignUpEndDate —— 名字对不上 ⇒ available 恒 false ⇒
               「开放承接路线」在真实数据下**恒为空列表**,而且不报错(2026-09-06 发现)。
               两个名字都给,前端那边同时改成优先读真实字段;这一条留着兜住任何还在读旧名的地方。 */
            item.put("recruitDeadline", topic.getMerchantSignUpEndDate());
            // 卡片右上角要写「由『XX 俱乐部』发起」,同样是原来漏 put 的
            item.put("contentPublisherType", topic.getContentPublisherType());
            item.put("contentPublisherName", topic.getContentPublisherName());
            item.put("startDate", topic.getStartDate());
            item.put("endDate", topic.getEndDate());
            item.put("cityKeyword", topic.getCityKeyword());
            item.put("locationCount", topic.getLocationCount());
            item.put("remainingMerchantCount", topic.getRemainingMerchantCount());
            /* 2026-09-06 稿 234:276:卡片底排左边那两行,第一行要的是**结算方式**(档位),
               不是发起方 —— 稿的注记原话:「商家在这里做的是『要不要接这一站』…卡面给的是
               模式 / 余席 / 档期 / 地点与场地要求 / 结算方式 / 招商截止」。
               档位挂在章节(CmsTopicChapter.termsMode),CmsTopic 上没有,所以在这里聚合。
               ⚠️ 一个主题可以混档(第1章权益、第2章引流),所以给的是**集合**不是单值,
                  由前端决定怎么写成一行。空集合就不给,前端那一行不出。
               ⚠️ 稿上还有「· 分润 15%」—— 招商阶段没有任何字段能给出分润率
                  (sharing_rate 在 cms_registration_merchant 上,是承接**之后**才有的),
                  不编一个出来。这一条记在 Figma 代码待改表。 */
            item.put("termsModes", recruitTermsModes(topic.getId()));
            item.putAll(recruitTermsEconomics(topic.getId()));
            items.add(item);
            if (items.size() >= 10) break;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        // count = 还没截止的可承接数(营销卡上那句「N 个开放商机」直接读它);
        // shownCount / hasMoreOpen 给广场说明「共 N、当前只排了前 M」,截断不再是静默的。
        // 未截止的排在前面,所以 items 装不下的一定是未截止里剩下的那些。
        data.put("count", openRecruitCount);
        data.put("openCount", openRecruitCount);
        data.put("shownCount", items.size());
        data.put("hasMoreOpen", openRecruitCount > items.size());
        data.put("items", items);
        return data;
    }

    /** 招商是否还开着:没有截止日 = 长期开放,有截止日 = 还没到点。 */
    private static boolean isRecruitOpenAt(CmsTopic topic, Date now) {
        Date deadline = topic == null ? null : topic.getMerchantSignUpEndDate();
        return deadline == null || deadline.after(now);
    }

    /** 该主题下**开放招商**章节的档位集合(去重、保序)。没有开放招商的章节就是空集合。 */
    private List<String> recruitTermsModes(Long topicId) {
        if (topicId == null) return Collections.emptyList();
        CmsTopicChapter query = new CmsTopicChapter();
        query.setTopicId(topicId);
        query.setRecruitEnabled(1);
        Set<String> modes = new LinkedHashSet<>();
        for (CmsTopicChapter chapter : safe(chapterMapper.selectCmsTopicChapterList(query))) {
            String mode = chapter.getTermsMode();
            if (mode != null && !mode.trim().isEmpty()) modes.add(mode.trim());
        }
        return new ArrayList<>(modes);
    }

    /**
     * 招商公示的**钱**:稿 234:276 的卡片档位那行后半截。
     *
     * ⚠️ 稿上写的是「分成 15%」,仓里没有这个数 —— sharing_rate 在 cms_registration_merchant 上,
     *    是承接**之后**才有的,招商阶段编不出来。但「招商阶段一个数都给不出」这个结论是错的:
     *    章节上有两个**发布时就定好、且本来就承诺要公示**的口径:
     *      PERK     → perk_min_value「权益不低于 ¥N」(质量门槛,空=不设门槛)
     *      REVSHARE → max_per_head_fee「人头费上限 ¥N/人次」(平台成本上界,空=拒绝计酬入驻)
     *    这两个数商家申请前就该看得到 —— CmsTopicChapter 上 maxPerHeadFee 的注释自己写着
     *    「边界在**招商时公示**:商家申请前就看得到,不是做完了才被告知超标」。
     *    取值一律取**最宽的那一档**(perk 取最低门槛、计酬取最高上限):混档主题上
     *    卡片只有一行,给最严的那个会让商家以为自己够不着而不来。
     * 混档或取不到就不下发对应键,前端缺就不写这半截。
     */
    private Map<String, Object> recruitTermsEconomics(Long topicId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (topicId == null) return out;
        CmsTopicChapter query = new CmsTopicChapter();
        query.setTopicId(topicId);
        query.setRecruitEnabled(1);
        java.math.BigDecimal minPerkValue = null;
        java.math.BigDecimal maxHeadFee = null;
        for (CmsTopicChapter chapter : safe(chapterMapper.selectCmsTopicChapterList(query))) {
            java.math.BigDecimal perk = chapter.getPerkMinValue();
            if (perk != null && (minPerkValue == null || perk.compareTo(minPerkValue) < 0)) minPerkValue = perk;
            java.math.BigDecimal fee = chapter.getMaxPerHeadFee();
            if (fee != null && (maxHeadFee == null || fee.compareTo(maxHeadFee) > 0)) maxHeadFee = fee;
        }
        if (minPerkValue != null) out.put("perkMinValue", minPerkValue);
        if (maxHeadFee != null) out.put("maxPerHeadFee", maxHeadFee);
        return out;
    }

    /**
     * 营销页的优惠券卡。计数范围与**点开卡片落到的那份商家券列表**同源
     * (`ApiCouponController.myPublishCouponList`:member_id + merchant_id + scope_type=1
     * + del_flag=0),否则就是「卡片说 1 张在投放、列表说还没有优惠券」(CU-M-126)。
     *
     * <p>原来这里只按 member_id 查、再在 Java 里剔 del_flag=2:同一位主理人名下**别的
     * 商家节点**的券、以及不是商家券(scope_type≠1)的券都进了计数,而删除口径又只挡了
     * 2(SQL 根本不认 del_flag,列表会把已删除的券列出来)。归属和删除两处都收在查询条件里,
     * Java 不再自己判一遍。
     */
    private Map<String, Object> couponStats(Long memberId, Long merchantId) {
        SmsCoupon query = new SmsCoupon();
        query.setMemberId(memberId);
        query.setMerchantId(merchantId);
        query.setScopeType(1);
        query.setDelFlag(0);
        long received = 0L;
        long verified = 0L;
        int onAir = 0;
        int total = 0;
        Date now = new Date();
        for (SmsCoupon coupon : safe(couponService.selectSmsCouponList(query))) {
            total++;
            // CU-M-51/M-86(2026-09-24):卡面写的是「N 张在投放」,不能把已停发(4)/
            // 未开始(0)也算进去 —— 数字与文案两张皮。领取/核销仍是这叠券的累计数
            // (停发不影响已领的照常核销),另出 couponTotal 供前端判「有没有券」。
            if (coupon.onAir(now)) onAir++;
            received += coupon.getReceiveCount() == null ? 0L : coupon.getReceiveCount();
            verified += coupon.getUseCount() == null ? 0L : coupon.getUseCount();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("couponCount", onAir);
        data.put("couponTotal", total);
        data.put("received", received);
        data.put("verified", verified);
        return data;
    }

    private Map<String, Object> contentStats(Long memberId) {
        CmsTopic topicQuery = new CmsTopic();
        topicQuery.setMemberId(memberId);
        topicQuery.setDelFlag(0);
        topicQuery.setActiveClubOnly(true);
        int topics = 0;
        int freeExplore = 0;
        for (CmsTopic topic : safe(topicService.selectCmsTopicList(topicQuery))) {
            if (Integer.valueOf(2).equals(topic.getProductType())) freeExplore++;
            else topics++;
        }
        CmsActivity activityQuery = new CmsActivity();
        activityQuery.setMemberId(memberId);
        activityQuery.setDelFlag(0);
        activityQuery.setActiveClubOnly(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topicCount", topics);
        data.put("freeExploreCount", freeExplore);
        data.put("activityCount", safe(activityService.selectCmsActivityList(activityQuery)).size());
        return data;
    }

    @Override
    public Map<String, Object> funnel(Long memberId, int windowDays) {
        return marketingAnalytics(memberId, windowDays);
    }

    private Map<String, Object> marketingAnalytics(Long memberId, int windowDays) {
        if (windowDays != 7 && windowDays != 30) {
            throw new IllegalArgumentException("windowDays仅支持7或30");
        }
        MarketingWindow marketingWindow = resolveMarketingWindow(
                windowDays, Clock.system(MARKETING_TIMEZONE));
        Date previousStart = marketingWindow.getPreviousStart();
        Date currentStart = marketingWindow.getCurrentStart();
        Date endExclusive = marketingWindow.getEndExclusive();
        MerchantMarketingAnalyticsFact fact = marketingAnalyticsMapper.selectAnalytics(
                memberId, previousStart, currentStart, endExclusive);
        if (fact == null) fact = new MerchantMarketingAnalyticsFact();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("semantics", "INDEPENDENT_EVENT_COUNTS");
        result.put("windowDays", windowDays);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("timezone", MARKETING_TIMEZONE.getId());
        window.put("basis", "COMPLETE_CALENDAR_DAYS");
        window.put("previousStartInclusive", previousStart.getTime());
        window.put("currentStartInclusive", currentStart.getTime());
        window.put("endExclusive", endExclusive.getTime());
        result.put("window", window);

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(eventStage("BROWSE", "浏览", fact.getCurrentBrowse(), fact.getPreviousBrowse()));
        stages.add(eventStage("SIGNUP", "报名", fact.getCurrentSignup(), fact.getPreviousSignup()));
        stages.add(eventStage("PAYMENT", "支付", fact.getCurrentPayment(), fact.getPreviousPayment()));
        stages.add(eventStage("VERIFY", "核销", fact.getCurrentVerify(), fact.getPreviousVerify()));
        stages.add(eventStage("REFUND_APPLY", "退款申请",
                fact.getCurrentRefundApply(), fact.getPreviousRefundApply()));
        result.put("stages", stages);

        Map<String, Object> touchpoints = new LinkedHashMap<>();
        touchpoints.put("coverage", "PARTIAL");
        touchpoints.put("attributed", false);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(touchpoint("IN_APP_TOPIC", "主题入口", fact.getCurrentTopicTouchpoint()));
        items.add(touchpoint("IN_APP_ACTIVITY", "活动入口", fact.getCurrentActivityTouchpoint()));
        items.add(touchpoint("UNATTRIBUTED", "未归因", fact.getCurrentUnattributedTouchpoint()));
        touchpoints.put("items", items);
        result.put("touchpoints", touchpoints);
        return result;
    }

    static MarketingWindow resolveMarketingWindow(int windowDays, Clock clock) {
        if (windowDays != 7 && windowDays != 30) {
            throw new IllegalArgumentException("windowDays仅支持7或30");
        }
        LocalDate today = clock.instant().atZone(MARKETING_TIMEZONE).toLocalDate();
        ZonedDateTime endExclusive = today.atStartOfDay(MARKETING_TIMEZONE);
        ZonedDateTime currentStart = endExclusive.minusDays(windowDays);
        ZonedDateTime previousStart = currentStart.minusDays(windowDays);
        return new MarketingWindow(
                Date.from(previousStart.toInstant()),
                Date.from(currentStart.toInstant()),
                Date.from(endExclusive.toInstant()));
    }

    private Map<String, Object> eventStage(String code, String label, long current, long previous) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        item.put("currentCount", current);
        item.put("previousCount", previous);
        item.put("changeRate", previous == 0L ? null
                : new BigDecimal(current - previous)
                .divide(new BigDecimal(previous), 4, BigDecimal.ROUND_HALF_UP).toPlainString());
        return item;
    }

    private Map<String, Object> touchpoint(String code, String label, long count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        item.put("eventCount", count);
        return item;
    }

    private Map<String, Object> publicMerchant(MmsMerchant merchant) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", merchant.getId());
        // memberId:前端只拿得到 memberId 时要能定位统一主页(/api/merchant/public-home)
        item.put("memberId", merchant.getMemberId());
        item.put("name", merchant.getName());
        item.put("logo", merchant.getLogo());
        item.put("categoryId", merchant.getCategoryId());
        item.put("address", merchant.getAddress());
        item.put("cityRole", merchant.getCityRole());
        // 2026-08-20 商家卡重设计消费的公开展示字段(/api/merchant/list 公开脱敏响应里同一批):
        // 封面头图 / 营业状态芯片 / 坐标可信度(verified=0 时前端禁渲染距离)
        item.put("coverImage", merchant.getCoverImage());
        item.put("businessStatus", merchant.getBusinessStatus());
        item.put("locationVerified", merchant.getLocationVerified());
        item.put("businessTime", merchant.getBusinessTime());
        item.put("availableTime", merchant.getAvailableTime());
        item.put("tags", merchant.getTags());
        item.put("capacity", merchant.getCapacity());
        item.put("chargeType", merchant.getChargeType());
        item.put("suitActivityTypes", merchant.getSuitActivityTypes());
        item.put("coopOpen", merchant.getCoopOpen());
        return item;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) target.put(key, value);
    }

    private static <T> List<T> safe(List<T> source) {
        return source == null ? Collections.<T>emptyList() : source;
    }

    static final class MarketingWindow {
        private final Date previousStart;
        private final Date currentStart;
        private final Date endExclusive;

        private MarketingWindow(Date previousStart, Date currentStart, Date endExclusive) {
            this.previousStart = previousStart;
            this.currentStart = currentStart;
            this.endExclusive = endExclusive;
        }

        Date getPreviousStart() {
            return previousStart;
        }

        Date getCurrentStart() {
            return currentStart;
        }

        Date getEndExclusive() {
            return endExclusive;
        }
    }

    private static final class Partner {
        private final String type;
        private final Long id;

        private Partner(String type, Long id) {
            this.type = type;
            this.id = id;
        }
    }
}

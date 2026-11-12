package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.domain.dto.PublicContentCatalogRow;
import com.chengyinhub.business.domain.vo.PublicContentCatalogDetailVO;
import com.chengyinhub.business.domain.vo.PublicContentCatalogItemVO;
import com.chengyinhub.business.domain.vo.PublicContentCatalogMapPointVO;
import com.chengyinhub.business.domain.vo.PublicContentCatalogPreviewVO;
import com.chengyinhub.business.domain.vo.PublishRevisionVO;
import com.chengyinhub.business.mapper.PublicContentCatalogMapper;
import com.chengyinhub.business.service.IPublicContentCatalogService;
import com.chengyinhub.business.service.support.PublicContentCatalogLocale;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M7 公开内容目录读面实现。
 *
 * <p><b>不写任何表</b>：目录是既有事实的只读投影，没有第二份内容真源，也没有缓存表。
 * 价格只是聚合展示，真源仍是 {@code cms_topic.self_play_price} / {@code oms_ticket.price}；
 * 本类不写回、不改费率、不改报价与退款规则。
 *
 * <p>性能约束：分页查询只取窄列；章节数、票种、售卖快照、译文语言、城市名全部
 * <b>按页批量</b>补取(每类一次)，不做逐主题查询，也不在列表里拉 blocks/正文。
 */
@Service
public class PublicContentCatalogServiceImpl implements IPublicContentCatalogService {

    static final int PREVIEW_DEFAULT_STOPS = 3;
    /** 与 TopicSaleFinalizationServiceImpl / 小程序章节卡同一占位口径。 */
    static final String UNNAMED_CHAPTER = "未命名章节";
    /** 列表摘要兜底长度：作者没写公开摘要时截 description，不外泄全文。 */
    static final int SUMMARY_FALLBACK_LENGTH = 120;

    static final List<Integer> PRODUCT_TYPES = Arrays.asList(1, 2);
    static final List<String> DELIVERY_MODES = Arrays.asList("SELF_PLAY", "GUIDED_SESSION", "CLUB_HOSTED");
    static final List<String> SORTS = Arrays.asList("NEWEST", "DURATION_ASC", "DURATION_DESC");

    private static final String STATE_ON_SALE = "ON_SALE";
    private static final String STATE_SALE_ENDED = "SALE_ENDED";
    private static final String STATE_NOT_ON_SALE = "NOT_ON_SALE";

    @Autowired
    private PublicContentCatalogMapper catalogMapper;

    @Override
    public List<PublicContentCatalogItemVO> list(PublicCatalogQuery query) {
        PublicCatalogQuery normalized = normalize(query);
        String locale = normalized.getLocale();
        // 分页第一条 SQL：必须先是它，PageHelper 才会把 count 与 rows 绑在同一公开闸上。
        List<PublicContentCatalogRow> rows = catalogMapper.selectCatalogItems(normalized);
        return project(rows, locale, null);
    }

    @Override
    public void validate(PublicCatalogQuery query) {
        normalize(query);
    }

    @Override
    public PublicContentCatalogDetailVO detail(Long topicId, String locale) {
        if (topicId == null || topicId <= 0) {
            throw new ServiceException("id 参数不合规", 400);
        }
        String normalizedLocale = PublicContentCatalogLocale.normalize(locale);
        PublicContentCatalogRow row = catalogMapper.selectCatalogItemById(topicId, normalizedLocale);
        if (row == null) {
            // 未公开/已下架/模板/会员专属一律与「不存在」同码，避免用错误码探测内容状态。
            throw new ServiceException("作品不存在或未公开", 404);
        }
        List<PublicContentCatalogItemVO> projected =
                project(Collections.singletonList(row), normalizedLocale, null);
        PublicContentCatalogDetailVO detail = new PublicContentCatalogDetailVO();
        copy(projected.get(0), detail);
        detail.setChapterNames(chapterNames(topicId));
        detail.setPreview(preview(topicId, row.getLocationCount()));
        return detail;
    }

    @Override
    public List<PublicContentCatalogMapPointVO> mapPoints(PublicCatalogQuery query) {
        PublicCatalogQuery normalized = normalize(query);
        // 地图形态先按同一公开闸取当页 id(窄列)，再补坐标；两次查询都受同一分页。
        List<PublicContentCatalogRow> rows = catalogMapper.selectCatalogItems(normalized);
        List<Long> topicIds = new ArrayList<Long>();
        for (PublicContentCatalogRow row : rows) {
            if (row.getId() != null) topicIds.add(row.getId());
        }
        if (topicIds.isEmpty()) {
            return new ArrayList<PublicContentCatalogMapPointVO>();
        }
        Map<Long, PublicContentCatalogMapPointVO> byId = new HashMap<Long, PublicContentCatalogMapPointVO>();
        for (PublicContentCatalogMapPointVO point : nullToEmpty(catalogMapper.selectMapPoints(topicIds))) {
            byId.put(point.getId(), point);
        }
        List<PublicContentCatalogMapPointVO> out = new ArrayList<PublicContentCatalogMapPointVO>();
        for (PublicContentCatalogRow row : rows) {
            PublicContentCatalogMapPointVO point = byId.get(row.getId());
            if (point == null) {
                // 没有公开坐标：仍然返回该作品(卡片可点)，只是没有落点，不用城市中心猜。
                point = new PublicContentCatalogMapPointVO();
                point.setId(row.getId());
                point.setName(row.getName());
                point.setCityCode(row.getCityCode());
                point.setImgUrl(row.getImgUrl());
                point.setLocationCount(row.getLocationCount());
                point.setUpdatedAt(row.getUpdateTime());
            }
            point.setCanonicalPath(canonicalPath(row.getCityCode(), row.getId()));
            out.add(point);
        }
        return out;
    }

    @Override
    public List<PublicContentCatalogItemVO> byIds(List<Long> topicIds, String locale) {
        if (topicIds == null || topicIds.isEmpty()) {
            return new ArrayList<PublicContentCatalogItemVO>();
        }
        if (topicIds.size() > 100) {
            throw new ServiceException("topicIds 一次最多 100 个", 400);
        }
        String normalizedLocale = PublicContentCatalogLocale.normalize(locale);
        List<Long> ordered = new ArrayList<Long>();
        for (Long id : topicIds) {
            if (id != null && id > 0 && !ordered.contains(id)) ordered.add(id);
        }
        if (ordered.isEmpty()) {
            throw new ServiceException("topicIds 参数不合规", 400);
        }
        Map<Long, PublicContentCatalogRow> byId = new HashMap<Long, PublicContentCatalogRow>();
        for (PublicContentCatalogRow row : nullToEmpty(catalogMapper.selectCatalogItemsByIds(ordered, normalizedLocale))) {
            byId.put(row.getId(), row);
        }
        List<PublicContentCatalogRow> picked = new ArrayList<PublicContentCatalogRow>();
        for (Long id : ordered) {
            PublicContentCatalogRow row = byId.get(id);
            // 不在结果里 = 该 id 未公开；直接略过，不报错也不返回占位卡。
            if (row != null) picked.add(row);
        }
        return project(picked, normalizedLocale, ordered);
    }

    // ------------------------------------------------------------------ 投影

    private List<PublicContentCatalogItemVO> project(List<PublicContentCatalogRow> rows, String locale,
                                                     List<Long> order) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<PublicContentCatalogItemVO>();
        }
        List<Long> topicIds = new ArrayList<Long>();
        Set<String> cityCodes = new LinkedHashSet<String>();
        for (PublicContentCatalogRow row : rows) {
            topicIds.add(row.getId());
            if (row.getCityCode() != null && !row.getCityCode().isEmpty()) cityCodes.add(row.getCityCode());
        }
        Map<Long, Integer> chapterCounts = chapterCounts(topicIds);
        Map<Long, TicketAggregate> tickets = ticketAggregates(topicIds);
        Map<Long, Map<String, Object>> revisions = saleRevisions(topicIds);
        Map<Long, List<String>> locales = approvedLocales(topicIds);
        Map<String, String> cityNames = cityNames(new ArrayList<String>(cityCodes));
        Date now = new Date();

        Map<Long, PublicContentCatalogItemVO> byId = new LinkedHashMap<Long, PublicContentCatalogItemVO>();
        for (PublicContentCatalogRow row : rows) {
            byId.put(row.getId(), toItem(row, locale, now, chapterCounts, tickets, revisions, locales, cityNames));
        }
        if (order == null || order.isEmpty()) {
            return new ArrayList<PublicContentCatalogItemVO>(byId.values());
        }
        List<PublicContentCatalogItemVO> sorted = new ArrayList<PublicContentCatalogItemVO>();
        for (Long id : order) {
            PublicContentCatalogItemVO item = byId.get(id);
            if (item != null) sorted.add(item);
        }
        return sorted;
    }

    private PublicContentCatalogItemVO toItem(PublicContentCatalogRow row, String locale, Date now,
                                              Map<Long, Integer> chapterCounts,
                                              Map<Long, TicketAggregate> tickets,
                                              Map<Long, Map<String, Object>> revisions,
                                              Map<Long, List<String>> locales,
                                              Map<String, String> cityNames) {
        PublicContentCatalogItemVO item = new PublicContentCatalogItemVO();
        item.setId(row.getId());
        item.setCanonicalPath(canonicalPath(row.getCityCode(), row.getId()));
        item.setCityCode(row.getCityCode());
        item.setCityName(cityNames.get(row.getCityCode()));
        item.setImgUrl(row.getImgUrl());
        item.setProductType(row.getProductType());
        item.setTotalTime(row.getTotalTime());
        item.setTotalMileage(row.getTotalMileage());
        item.setLocationCount(row.getLocationCount());
        Integer chapterCount = chapterCounts.get(row.getId());
        item.setChapterCount(chapterCount == null ? 0 : chapterCount);
        item.setUpdatedAt(row.getUpdateTime());

        applyLocalizedText(item, row, locale);

        TicketAggregate ticket = tickets.get(row.getId());
        item.setDeliveryModes(deliveryModes(row, ticket));
        applyPrice(item, row, ticket);
        applySaleState(item, row, now);

        // 落地元信息：无行或停用 ⇒ 全部为 null(未声明)，不用 false 冒充「不接待」。
        item.setGroupEligible(intToBoolean(row.getGroupEligible()));
        item.setSchoolEligible(intToBoolean(row.getSchoolEligible()));
        item.setGroupMinPeople(row.getGroupMinPeople());
        item.setGroupMaxPeople(row.getGroupMaxPeople());
        item.setAgeMin(row.getAgeMin());
        item.setAgeMax(row.getAgeMax());
        item.setAccessibilityNote(row.getAccessibilityNote());

        item.setAvailableLocales(locales.get(row.getId()));
        item.setRevision(revision(row, revisions.get(row.getId())));
        return item;
    }

    /**
     * 公开文案本地化：先精确语言、再主语言；都不可用就回退原文并逐字段标注。
     * 只有 APPROVED 且确有内容才算译好 —— PENDING 与缺译同等回退。
     */
    private void applyLocalizedText(PublicContentCatalogItemVO item, PublicContentCatalogRow row, String locale) {
        String requested = PublicContentCatalogLocale.normalize(locale);
        item.setRequestedLocale(PublicContentCatalogLocale.display(requested));

        boolean source = PublicContentCatalogLocale.isSource(requested);
        String translatedTitle = row.getTranslatedTitle();
        String translatedSubtitle = row.getTranslatedSubtitle();
        String translatedSummary = row.getTranslatedSummary();
        // SQL 已按 requested locale + APPROVED 过滤；这里再按字段判空，缺一字段就是该字段回退。
        boolean titleTranslated = !source && PublicContentCatalogLocale.hasText(translatedTitle);
        boolean subtitleTranslated = !source && PublicContentCatalogLocale.hasText(translatedSubtitle);
        boolean summaryTranslated = !source && PublicContentCatalogLocale.hasText(translatedSummary);

        item.setName(titleTranslated ? translatedTitle : row.getName());
        item.setSubtitle(subtitleTranslated ? translatedSubtitle : row.getSubtitle());
        item.setSummary(pickSummary(summaryTranslated ? translatedSummary : null,
                row.getLandingSummary(), row.getDescription()));

        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("title", titleTranslated ? "TRANSLATED" : "FALLBACK");
        fields.put("subtitle", subtitleTranslated ? "TRANSLATED" : "FALLBACK");
        fields.put("summary", summaryTranslated ? "TRANSLATED" : "FALLBACK");
        item.setTranslationFields(fields);

        if (source) {
            item.setActualLocale(PublicContentCatalogLocale.SOURCE_LOCALE_DISPLAY);
            item.setTranslationState("SOURCE");
            return;
        }
        int translated = (titleTranslated ? 1 : 0) + (subtitleTranslated ? 1 : 0) + (summaryTranslated ? 1 : 0);
        item.setActualLocale(PublicContentCatalogLocale.display(requested));
        if (translated == 3) {
            item.setTranslationState("TRANSLATED");
        } else if (translated == 0) {
            // 回退到原文：实际返回语言必须是原文语言，不能报成请求语言。
            item.setActualLocale(PublicContentCatalogLocale.SOURCE_LOCALE_DISPLAY);
            item.setTranslationState("FALLBACK");
        } else {
            // 只有部分字段译好：仍是混合内容，实际语言按原文标注，状态明确为 PARTIAL。
            item.setTranslationState("PARTIAL");
        }
    }

    private String pickSummary(String translatedSummary, String landingSummary, String description) {
        if (PublicContentCatalogLocale.hasText(translatedSummary)) return translatedSummary;
        if (PublicContentCatalogLocale.hasText(landingSummary)) return landingSummary;
        if (PublicContentCatalogLocale.hasText(description)) {
            String trimmed = description.trim();
            return trimmed.length() > SUMMARY_FALLBACK_LENGTH
                    ? trimmed.substring(0, SUMMARY_FALLBACK_LENGTH) : trimmed;
        }
        return null;
    }

    /**
     * 交付形态由真实配置推导，不新建枚举表：
     * SELF_PLAY = 开放自玩票；GUIDED_SESSION = 有 live「有人带」票种；CLUB_HOSTED = 归属俱乐部。
     */
    private List<String> deliveryModes(PublicContentCatalogRow row, TicketAggregate ticket) {
        List<String> modes = new ArrayList<String>();
        if (Integer.valueOf(1).equals(row.getSelfPlay())) modes.add("SELF_PLAY");
        if (ticket != null && ticket.guidedCount > 0) modes.add("GUIDED_SESSION");
        if (row.getClubId() != null && row.getClubId() > 0) modes.add("CLUB_HOSTED");
        return modes;
    }

    /**
     * 价格只做聚合展示：自玩价与 live 票种最低价取小，并标明是哪种口径，
     * 避免 UI 把票种价当通行证价。两者都不可读时为 UNKNOWN，不用旧价兜底。
     */
    private void applyPrice(PublicContentCatalogItemVO item, PublicContentCatalogRow row, TicketAggregate ticket) {
        BigDecimal selfPlayPrice = row.getSelfPlayPrice();
        boolean hasSelfPlayPrice = Integer.valueOf(1).equals(row.getSelfPlay())
                && selfPlayPrice != null && selfPlayPrice.signum() >= 0;
        BigDecimal ticketPrice = ticket == null ? null : ticket.minPrice;

        if (hasSelfPlayPrice && (ticketPrice == null || selfPlayPrice.compareTo(ticketPrice) <= 0)) {
            item.setPriceFrom(selfPlayPrice);
            item.setPriceUnit("SELF_PLAY_PASS");
            item.setPriceState("KNOWN");
        } else if (ticketPrice != null) {
            item.setPriceFrom(ticketPrice);
            item.setPriceUnit("TICKET");
            item.setPriceState("KNOWN");
        } else {
            item.setPriceFrom(null);
            item.setPriceUnit(null);
            item.setPriceState("UNKNOWN");
        }
    }

    /**
     * 售卖状态只读投影，复用既有生命周期真源 {@code TopicLifecycleState} 的语义：
     * 仅在 SELLING 且未过售止时为 ON_SALE；已进入履约/结算/关闭或过了售止为 SALE_ENDED；
     * 其余(草稿/招募/待定价/立项待审/未知)为 NOT_ON_SALE。
     * <b>不是</b>下单闸 —— 真正售卖仍由 SaleAvailabilityPolicy 与免费主题在架判定把关。
     */
    private void applySaleState(PublicContentCatalogItemVO item, PublicContentCatalogRow row, Date now) {
        Integer lifecycle = row.getLifecycle();
        Date saleEnd = row.getSaleEndAt();
        boolean sellingOpen = Integer.valueOf(3).equals(lifecycle) || Integer.valueOf(4).equals(lifecycle)
                || Integer.valueOf(5).equals(lifecycle) || Integer.valueOf(6).equals(lifecycle);
        if (Integer.valueOf(3).equals(lifecycle)) {
            if (saleEnd == null) {
                // 售止缺失：既有售卖闸会拒绝售卖，目录也不能声称在售。
                item.setSaleState(STATE_NOT_ON_SALE);
            } else {
                item.setSaleState(now.before(saleEnd) ? STATE_ON_SALE : STATE_SALE_ENDED);
            }
        } else if (sellingOpen) {
            item.setSaleState(STATE_SALE_ENDED);
        } else {
            item.setSaleState(STATE_NOT_ON_SALE);
        }
        item.setFreeSelfPlay(freeSelfPlayAvailable(row, now));
    }

    /** 免费自玩入口事实：开放自玩 + 价格确为 0 + 未过主题截止(saleEndAt，缺省 endDate)。 */
    private Boolean freeSelfPlayAvailable(PublicContentCatalogRow row, Date now) {
        if (!Integer.valueOf(1).equals(row.getSelfPlay())) return Boolean.FALSE;
        BigDecimal price = row.getSelfPlayPrice();
        if (price == null || price.signum() != 0) return Boolean.FALSE;
        Date deadline = row.getSaleEndAt() != null ? row.getSaleEndAt() : row.getEndDate();
        return deadline == null || now.before(deadline);
    }

    private PublishRevisionVO revision(PublicContentCatalogRow row, Map<String, Object> snapshot) {
        PublishRevisionVO revision = new PublishRevisionVO();
        revision.setPublishedConfigVersion(row.getPublishedConfigVersion());
        if (snapshot != null && snapshot.get("saleSnapshotId") != null) {
            revision.setState("SALE_FROZEN");
            revision.setSaleSnapshotId(toLong(snapshot.get("saleSnapshotId")));
            revision.setFrozenAt((Date) snapshot.get("frozenAt"));
            return revision;
        }
        boolean publishedConfig = (row.getPublishedConfigVersion() != null && row.getPublishedConfigVersion() > 0)
                || row.getPublishedThemeRuntimeConfigJson() != null;
        revision.setState(publishedConfig ? "PUBLISHED_CONFIG" : "NONE");
        return revision;
    }

    /** 稳定路由：原 topic id 为主键，city code 只做前缀上下文；不建路由映射表。 */
    public static String canonicalPath(String cityCode, Long topicId) {
        if (topicId == null) return null;
        if (cityCode == null || cityCode.isEmpty()) return "/quest/" + topicId;
        return "/city/" + cityCode + "/quest/" + topicId;
    }

    // ------------------------------------------------------------------ 批量补取

    private Map<Long, Integer> chapterCounts(List<Long> topicIds) {
        Map<Long, Integer> out = new HashMap<Long, Integer>();
        for (Map<String, Object> row : nullToEmpty(catalogMapper.selectChapterCounts(topicIds))) {
            Long topicId = toLong(row.get("topicId"));
            Object count = row.get("chapterCount");
            if (topicId != null && count != null) out.put(topicId, Integer.valueOf(String.valueOf(count)));
        }
        return out;
    }

    private Map<Long, TicketAggregate> ticketAggregates(List<Long> topicIds) {
        Map<Long, TicketAggregate> out = new HashMap<Long, TicketAggregate>();
        for (Map<String, Object> row : nullToEmpty(catalogMapper.selectTicketAggregates(topicIds))) {
            Long topicId = toLong(row.get("topicId"));
            if (topicId == null) continue;
            out.put(topicId, new TicketAggregate(decimal(row.get("minTicketPrice")), intOf(row.get("guidedCount"))));
        }
        return out;
    }

    private Map<Long, Map<String, Object>> saleRevisions(List<Long> topicIds) {
        Map<Long, Map<String, Object>> out = new HashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : nullToEmpty(catalogMapper.selectSaleRevisions(topicIds))) {
            Long topicId = toLong(row.get("topicId"));
            if (topicId != null) out.put(topicId, row);
        }
        return out;
    }

    private Map<Long, List<String>> approvedLocales(List<Long> topicIds) {
        Map<Long, List<String>> out = new HashMap<Long, List<String>>();
        for (Map<String, Object> row : nullToEmpty(catalogMapper.selectApprovedLocales(topicIds))) {
            Long topicId = toLong(row.get("topicId"));
            Object locale = row.get("locale");
            if (topicId == null || locale == null) continue;
            List<String> list = out.get(topicId);
            if (list == null) {
                list = new ArrayList<String>();
                out.put(topicId, list);
            }
            list.add(PublicContentCatalogLocale.display(String.valueOf(locale)));
        }
        return out;
    }

    private Map<String, String> cityNames(List<String> cityCodes) {
        Map<String, String> out = new HashMap<String, String>();
        if (cityCodes.isEmpty()) return out;
        for (Map<String, Object> row : nullToEmpty(catalogMapper.selectCityNames(cityCodes))) {
            Object code = row.get("cityCode");
            Object name = row.get("cityName");
            if (code == null || name == null) continue;
            out.put(String.valueOf(code), String.valueOf(name));
        }
        return out;
    }

    // ------------------------------------------------------------------ 详情补取

    private List<String> chapterNames(Long topicId) {
        // 章节名一次取回(标题很短)；正文 blocks 不在这条查询里。
        // name 为 NULL 时 MyBatis 对这一整行返回 null map：不能 row.get，按既有「未命名章节」口径占位，章节数不丢。
        List<String> names = new ArrayList<String>();
        for (Map<String, Object> row : nullToEmpty(catalogMapper.selectChapterNames(topicId))) {
            Object name = row == null ? null : row.get("chapterName");
            if (name == null) {
                names.add(UNNAMED_CHAPTER);
            } else if (!String.valueOf(name).trim().isEmpty()) {
                names.add(String.valueOf(name));
            }
        }
        return names;
    }

    private PublicContentCatalogPreviewVO preview(Long topicId, Long stopCount) {
        PublicContentCatalogPreviewVO preview = new PublicContentCatalogPreviewVO();
        preview.setStopCount(stopCount);
        preview.setMaxStops(PREVIEW_DEFAULT_STOPS);
        Map<String, Object> firstChapter = catalogMapper.selectFirstChapter(topicId);
        if (firstChapter != null) {
            Object chapterName = firstChapter.get("chapterName");
            preview.setFirstChapterName(chapterName == null ? null : String.valueOf(chapterName));
        }
        List<PublicContentCatalogPreviewVO.PreviewStopVO> stops =
                new ArrayList<PublicContentCatalogPreviewVO.PreviewStopVO>();
        int order = 0;
        for (Map<String, Object> row : nullToEmpty(
                catalogMapper.selectPreviewStops(topicId, PREVIEW_DEFAULT_STOPS))) {
            PublicContentCatalogPreviewVO.PreviewStopVO stop = new PublicContentCatalogPreviewVO.PreviewStopVO();
            stop.setOrder(++order);
            Object name = row.get("name");
            Object hook = row.get("hookTeaser");
            stop.setName(name == null ? null : String.valueOf(name));
            stop.setHookTeaser(hook == null ? null : String.valueOf(hook));
            stops.add(stop);
        }
        preview.setStops(stops);
        return preview;
    }

    // ------------------------------------------------------------------ 参数

    /** 严格枚举：未知值 400，不静默退化为「全部」。 */
    PublicCatalogQuery normalize(PublicCatalogQuery query) {
        PublicCatalogQuery normalized = query == null ? new PublicCatalogQuery() : query;
        String cityCode = trimToNull(normalized.getCityCode());
        if (cityCode != null && !cityCode.matches("^[0-9]{6}$")) {
            throw new ServiceException("city_code 必须为 6 位城市编码", 400);
        }
        normalized.setCityCode(cityCode);

        String locale = PublicContentCatalogLocale.normalize(normalized.getLocale());
        normalized.setLocale(locale);

        String keyword = trimToNull(normalized.getKeyword());
        if (keyword != null && keyword.length() > 50) {
            throw new ServiceException("keyword 最长 50 个字符", 400);
        }
        normalized.setKeyword(keyword);

        Integer productType = normalized.getProductType();
        if (productType != null && !PRODUCT_TYPES.contains(productType)) {
            throw new ServiceException("product_type 枚举无效，支持值: " + PRODUCT_TYPES, 400);
        }

        String deliveryMode = trimToNull(normalized.getDeliveryMode());
        if (deliveryMode != null) {
            deliveryMode = deliveryMode.toUpperCase(java.util.Locale.ROOT);
            if (!DELIVERY_MODES.contains(deliveryMode)) {
                throw new ServiceException("delivery_mode 枚举无效，支持值: " + DELIVERY_MODES, 400);
            }
        }
        normalized.setDeliveryMode(deliveryMode);
        normalized.setGroupEligible(flag(normalized.getGroupEligible(), "group_eligible"));
        normalized.setSchoolEligible(flag(normalized.getSchoolEligible(), "school_eligible"));

        Integer audienceAge = normalized.getAudienceAge();
        if (audienceAge != null && (audienceAge < 0 || audienceAge > 120)) {
            throw new ServiceException("audience_age 取值范围 0-120", 400);
        }
        Integer minMinutes = normalized.getMinMinutes();
        Integer maxMinutes = normalized.getMaxMinutes();
        if ((minMinutes != null && minMinutes < 0) || (maxMinutes != null && maxMinutes < 0)) {
            throw new ServiceException("时长筛选不能为负数", 400);
        }
        if (minMinutes != null && maxMinutes != null && minMinutes > maxMinutes) {
            throw new ServiceException("min_minutes 不能大于 max_minutes", 400);
        }

        String sort = trimToNull(normalized.getSort());
        if (sort != null) {
            sort = sort.toUpperCase(java.util.Locale.ROOT);
            if (!SORTS.contains(sort)) {
                throw new ServiceException("sort 枚举无效，支持值: " + SORTS, 400);
            }
        } else {
            sort = "NEWEST";
        }
        normalized.setSort(sort);
        return normalized;
    }

    private static Integer flag(Integer value, String field) {
        if (value == null) return null;
        if (value != 0 && value != 1) {
            throw new ServiceException(field + " 只接受 0 或 1", 400);
        }
        return value == 1 ? Integer.valueOf(1) : null;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Boolean intToBoolean(Integer value) {
        return value == null ? null : Boolean.valueOf(value == 1);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
    }

    private static int intOf(Object value) {
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        return value instanceof Number ? Long.valueOf(((Number) value).longValue())
                : Long.valueOf(String.valueOf(value));
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? Collections.<T>emptyList() : values;
    }

    private static void copy(PublicContentCatalogItemVO from, PublicContentCatalogDetailVO to) {
        to.setId(from.getId());
        to.setCanonicalPath(from.getCanonicalPath());
        to.setCityCode(from.getCityCode());
        to.setCityName(from.getCityName());
        to.setName(from.getName());
        to.setSubtitle(from.getSubtitle());
        to.setSummary(from.getSummary());
        to.setImgUrl(from.getImgUrl());
        to.setProductType(from.getProductType());
        to.setTotalTime(from.getTotalTime());
        to.setTotalMileage(from.getTotalMileage());
        to.setLocationCount(from.getLocationCount());
        to.setChapterCount(from.getChapterCount());
        to.setDeliveryModes(from.getDeliveryModes());
        to.setPriceUnit(from.getPriceUnit());
        to.setPriceFrom(from.getPriceFrom());
        to.setPriceState(from.getPriceState());
        to.setSaleState(from.getSaleState());
        to.setFreeSelfPlay(from.getFreeSelfPlay());
        to.setGroupEligible(from.getGroupEligible());
        to.setSchoolEligible(from.getSchoolEligible());
        to.setGroupMinPeople(from.getGroupMinPeople());
        to.setGroupMaxPeople(from.getGroupMaxPeople());
        to.setAgeMin(from.getAgeMin());
        to.setAgeMax(from.getAgeMax());
        to.setAccessibilityNote(from.getAccessibilityNote());
        to.setRequestedLocale(from.getRequestedLocale());
        to.setActualLocale(from.getActualLocale());
        to.setTranslationState(from.getTranslationState());
        to.setTranslationFields(from.getTranslationFields());
        to.setAvailableLocales(from.getAvailableLocales());
        to.setRevision(from.getRevision());
        to.setUpdatedAt(from.getUpdatedAt());
    }

    /** 票种聚合只在本类内部用于推导交付形态与最低价。 */
    private static final class TicketAggregate {
        private final BigDecimal minPrice;
        private final int guidedCount;

        private TicketAggregate(BigDecimal minPrice, int guidedCount) {
            this.minPrice = minPrice;
            this.guidedCount = guidedCount;
        }
    }

}

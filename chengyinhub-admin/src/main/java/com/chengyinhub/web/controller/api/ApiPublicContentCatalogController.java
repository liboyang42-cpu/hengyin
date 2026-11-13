package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.service.ICityDiscoveryCatalogService;
import com.chengyinhub.business.service.IPublicContentCatalogService;
import com.chengyinhub.business.domain.vo.PublicContentCatalogItemVO;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.Set;

/**
 * M7 公开内容目录 / 城市目录 / 落地数据(匿名只读)。
 *
 * <p>与既有 {@code /api/topic/list} 的分工：既有接口是主题(内容)视角的列表；
 * 本组接口是<b>分发/落地</b>视角，提供城市聚合、有限售前预览、稳定路由与语言回退事实。
 * 两者共用同一公开闸，不存在「这边看得见、那边看不见」的第二套可见性。
 *
 * <p>只读：没有任何写接口，没有幂等键需求；缓存与陈旧策略见各方法注释
 * (目录行由源表实时读取，不加本地缓存，也不需要失效通知)。
 *
 * <p>未公开/下架/会员专属/模板本体的作品在这里一律等价于「不存在」(404)，
 * 不用错误码差异暴露内容状态。
 */
@RestController
@Tag(name = "M7 公开内容目录")
@RequestMapping("/api/public-catalog")
public class ApiPublicContentCatalogController extends BaseController {

    @Autowired
    private IPublicContentCatalogService catalogService;

    @Autowired
    private ICityDiscoveryCatalogService cityCatalogService;

    @Autowired
    private PlatformPassService platformPassService;

    /**
     * 城市目录：只由真实已公开作品聚合，没有供给的城市不出现。
     *
     * <p>缓存：不缓存。城市计数直接来自源表聚合，下架即刻生效；不做预热/陈旧窗口。
     */
    @Anonymous
    @Operation(summary = "城市目录(只含真实有作品的城市)")
    @PostMapping("/city/list")
    public AjaxResult cityList() {
        try {
            startPage();
            return success(getDataTable(cityCatalogService.cities()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } finally {
            clearPage();
        }
    }

    /**
     * 目录卡片列表(完整卡投影)。返回 data.rows / data.total，与既有列表同一约定。
     *
     * <p>分页在 SQL 完成；摘要只在服务端截断，不下发正文。数量参数由调用方的
     * pageNum / pageSize 控制(与既有列表一致)，这里不另设容量上限冒充业务限制。
     */
    @Anonymous
    @Operation(summary = "公开目录卡片列表")
    @Parameters({
            @Parameter(name = "city_code", description = "6 位城市编码(可选)"),
            @Parameter(name = "locale", description = "请求语言，如 zh-CN / en(可选，缺省 zh-CN)"),
            @Parameter(name = "keyword", description = "标题/副标题关键词，最长 50"),
            @Parameter(name = "product_type", description = "1 经典定向 / 2 自由探索"),
            @Parameter(name = "delivery_mode", description = "SELF_PLAY / GUIDED_SESSION / CLUB_HOSTED"),
            @Parameter(name = "group_eligible", description = "1=只看已声明可承接团体"),
            @Parameter(name = "school_eligible", description = "1=只看已声明可承接学校班级"),
            @Parameter(name = "audience_age", description = "只看年龄声明覆盖该年龄的作品"),
            @Parameter(name = "min_minutes", description = "最短时长(分钟)"),
            @Parameter(name = "max_minutes", description = "最长时长(分钟)"),
            @Parameter(name = "sort", description = "NEWEST / DURATION_ASC / DURATION_DESC")
    })
    @PostMapping("/list")
    public AjaxResult list(String city_code, String locale, String keyword, Integer product_type,
                           String delivery_mode, Integer group_eligible, Integer school_eligible,
                           Integer audience_age, Integer min_minutes, Integer max_minutes, String sort) {
        try {
            PublicCatalogQuery query = query(city_code, locale, keyword, product_type, delivery_mode,
                    group_eligible, school_eligible, audience_age, min_minutes, max_minutes, sort);
            // 参数先校验再分页：校验失败不能把 PageHelper 线程变量留给下一条 SQL。
            catalogService.validate(query);
            startPage();
            List<PublicContentCatalogItemVO> rows = catalogService.list(query);
            Object principal = SecurityUtils.getAuthentication() == null ? null
                    : SecurityUtils.getAuthentication().getPrincipal();
            Long memberId = principal instanceof LoginAppUser user && user.getAppUser() != null
                    ? user.getAppUser().getUserId() : null;
            Set<Long> covered = platformPassService.coveredTopics(memberId,
                    rows.stream().map(PublicContentCatalogItemVO::getId).toList(), new Date());
            for (PublicContentCatalogItemVO item : rows) {
                boolean selfPlay = item.getDeliveryModes() != null && item.getDeliveryModes().contains("SELF_PLAY");
                boolean available = "ON_SALE".equals(item.getSaleState()) || Boolean.TRUE.equals(item.getFreeSelfPlay());
                item.setPassCovered(covered.contains(item.getId()) && selfPlay && available);
            }
            return success(getDataTable(rows));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } finally {
            clearPage();
        }
    }

    /** 地图轻投影：同筛选，仅返回落点与最少卡片字段；无公开坐标的作品不带坐标(不用城市中心猜)。 */
    @Anonymous
    @Operation(summary = "公开目录地图轻投影")
    @PostMapping("/map")
    public AjaxResult map(String city_code, String locale, String keyword, Integer product_type,
                          String delivery_mode, Integer group_eligible, Integer school_eligible,
                          Integer audience_age, Integer min_minutes, Integer max_minutes, String sort) {
        try {
            PublicCatalogQuery query = query(city_code, locale, keyword, product_type, delivery_mode,
                    group_eligible, school_eligible, audience_age, min_minutes, max_minutes, sort);
            catalogService.validate(query);
            startPage();
            return success(getDataTable(catalogService.mapPoints(query)));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } finally {
            clearPage();
        }
    }

    /** ID 批量补取：一次最多 100 个；未公开的 id 直接略过(不返回占位卡，也不报错)。 */
    @Anonymous
    @Operation(summary = "公开目录 ID 批量补取")
    @Parameters({
            @Parameter(name = "ids", description = "逗号分隔的主题 id，最多 100 个"),
            @Parameter(name = "locale", description = "请求语言")
    })
    @PostMapping("/ids")
    public AjaxResult byIds(String ids, String locale) {
        List<Long> topicIds = new ArrayList<Long>();
        if (ids != null) {
            for (String part : ids.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    topicIds.add(Long.valueOf(trimmed));
                } catch (NumberFormatException e) {
                    return new AjaxResult(400, "ids 含非法 id: " + trimmed);
                }
            }
        }
        try {
            return success(catalogService.byIds(topicIds, locale));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    /** 落地详情：完整卡 + 章节名 + 有限售前预览(首章前 3 站钩子，不含答案/正文/碎片)。 */
    @Anonymous
    @Operation(summary = "公开落地详情(含有限售前预览)")
    @Parameters({
            @Parameter(name = "id", description = "主题 id"),
            @Parameter(name = "locale", description = "请求语言")
    })
    @PostMapping("/info")
    public AjaxResult info(String id, String locale) {
        try {
            return success(catalogService.detail(parseId(id), locale));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    /** 合法筛选项：城市/玩法/交付形态/语言全部来自真实数据；空集合即空集合。 */
    @Anonymous
    @Operation(summary = "公开目录合法筛选项")
    @PostMapping("/filters")
    public AjaxResult filters() {
        try {
            return success(cityCatalogService.filterOptions());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    private static PublicCatalogQuery query(String cityCode, String locale, String keyword, Integer productType,
                                            String deliveryMode, Integer groupEligible, Integer schoolEligible,
                                            Integer audienceAge, Integer minMinutes, Integer maxMinutes, String sort) {
        PublicCatalogQuery query = new PublicCatalogQuery();
        query.setCityCode(cityCode);
        query.setLocale(locale);
        query.setKeyword(keyword);
        query.setProductType(productType);
        query.setDeliveryMode(deliveryMode);
        query.setGroupEligible(groupEligible);
        query.setSchoolEligible(schoolEligible);
        query.setAudienceAge(audienceAge);
        query.setMinMinutes(minMinutes);
        query.setMaxMinutes(maxMinutes);
        query.setSort(sort);
        return query;
    }

    private static Long parseId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new ServiceException("id 参数不合规", 400);
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("id 参数不合规", 400);
        }
    }
}

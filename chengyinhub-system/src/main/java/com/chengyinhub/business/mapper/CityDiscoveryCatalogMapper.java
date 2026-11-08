package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.CityDiscoveryCatalogItemVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * M7 城市目录与合法筛选项(只读)。
 *
 * <p>城市只能来自真实已公开主题的 {@code cms_topic.city_code} 聚合：没有作品的城市不出现，
 * 不补城市字典、不造空页。{@code selectCityCatalog} 可被 PageHelper 分页
 * (配套 XML {@code selectCityCatalog_COUNT})。
 */
public interface CityDiscoveryCatalogMapper {

    List<CityDiscoveryCatalogItemVO> selectCityCatalog();

    /** 真实存在的 product_type 值。 */
    List<Integer> selectProductTypes();

    /** 真实存在 APPROVED 译文的语言。 */
    List<String> selectApprovedLocales();

    /** 原文语言声明集合(落地元信息，缺省 zh-CN 由服务层补)。 */
    List<String> selectSourceLocales();

    /** 是否存在已声明可承接团体/学校的作品：{groupEligibleCount, schoolEligibleCount}。 */
    Map<String, Object> selectEligibilityAvailability();

    /** 自玩/俱乐部承接形态计数：{selfPlayCount, clubHostedCount}。 */
    Map<String, Object> selectSelfAndClubModeCounts();

    /** 有人带队场次的公开主题数。 */
    Long countGuidedSessionTopics();

    /** 该 cityCode 下的公开主题数(用于判断筛选值是否真实存在)。 */
    Long countPublicTopicsInCity(@Param("cityCode") String cityCode);
}

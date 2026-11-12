package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.domain.dto.PublicContentCatalogRow;
import com.chengyinhub.business.domain.vo.PublicContentCatalogMapPointVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * M7 公开内容目录读面(只读)。全部查询共用 {@code PublicContentCatalogMapper.catalogPublicGate}
 * —— 与 {@code CmsTopicMapper.publicTopicListWhere} 同一组固定条件，并 include 既有
 * {@code CmsTopicMapper.primaryCirclePublicGate}。没有第二个更宽松的公开闸。
 *
 * <p>分页：调用方先 {@code startPage()}，本 mapper 的第一条语句就是分页查询；
 * 章节数、票种价格、修订身份全部用<b>批量</b>补取(一次一条)，不逐条查(N+1)。
 */
public interface PublicContentCatalogMapper {

    List<PublicContentCatalogRow> selectCatalogItems(PublicCatalogQuery query);

    PublicContentCatalogRow selectCatalogItemById(@Param("topicId") Long topicId,
                                                  @Param("locale") String locale);

    List<PublicContentCatalogRow> selectCatalogItemsByIds(@Param("topicIds") List<Long> topicIds,
                                                          @Param("locale") String locale);

    /** 章节名清单(详情用；只取名字，不取 blocks_json)。 */
    List<Map<String, Object>> selectChapterNames(@Param("topicId") Long topicId);
    List<PublicContentCatalogMapPointVO> selectMapPoints(@Param("topicIds") List<Long> topicIds);

    /** topicId → 公开章节数(批量)。 */
    List<Map<String, Object>> selectChapterCounts(@Param("topicIds") List<Long> topicIds);

    /** topicId → 首个公开章节名 + 章节 id(详情预览用，单主题)。 */
    Map<String, Object> selectFirstChapter(@Param("topicId") Long topicId);

    /** 首章前 N 个公开站点的钩子(单主题；不含答案/正文/碎片)。 */
    List<Map<String, Object>> selectPreviewStops(@Param("topicId") Long topicId,
                                                 @Param("limit") int limit);

    /** topicId → live 票种聚合(最低价、有人带/无人带票种数)(批量)。 */
    List<Map<String, Object>> selectTicketAggregates(@Param("topicIds") List<Long> topicIds);

    /** topicId → 已冻结售卖快照身份(批量)。 */
    List<Map<String, Object>> selectSaleRevisions(@Param("topicIds") List<Long> topicIds);

    /** topicId → 作者已 APPROVED 的译文语言(批量，供 availableLocales)。 */
    List<Map<String, Object>> selectApprovedLocales(@Param("topicIds") List<Long> topicIds);

    /** cityCode → 唯一一致的城市名(批量)；冲突或不一致时 cityName 为 null。 */
    List<Map<String, Object>> selectCityNames(@Param("cityCodes") List<String> cityCodes);
}

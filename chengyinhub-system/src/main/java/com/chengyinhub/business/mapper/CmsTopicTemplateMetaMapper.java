package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.CmsTopicTemplateMeta;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 主题模板详情页元数据(07-23 P0 字段字典)读取与章节容量配置。
 *
 * <p>附表「没有行」= 该模板尚未填元数据,是合法状态,调用方必须能接住 null / 空列表,
 * 不能当异常。服务容量通过同一行原子写入,避免三项配置出现半套状态。
 */
public interface CmsTopicTemplateMetaMapper {

    /** 某主题的模板元数据;未填则 null。 */
    CmsTopicTemplateMeta selectByTopicId(@Param("topicId") Long topicId);

    /** 列表页批量读取,避免 N+1;未填的主题不出现在结果里。 */
    List<CmsTopicTemplateMeta> selectByTopicIds(@Param("topicIds") List<Long> topicIds);

    /** 某主题下所有章节的元数据;未填的章节不出现在结果里。 */
    List<CmsTopicChapterTemplateMeta> selectChapterMetaByTopicId(@Param("topicId") Long topicId);

    /** 某章节的元数据；未填则 null。 */
    CmsTopicChapterTemplateMeta selectChapterMetaByChapterId(@Param("chapterId") Long chapterId);

    /** 一章一行原子写入完整容量三元组；唯一键冲突时整组覆盖。 */
    int upsertChapterServiceCapacity(@Param("chapterId") Long chapterId,
                                     @Param("topicId") Long topicId,
                                     @Param("batchSize") Integer batchSize,
                                     @Param("batchIntervalMinutes") Integer batchIntervalMinutes,
                                     @Param("effortMaxConcurrent") Integer effortMaxConcurrent);

    /** F05 一章一行原子写入商家工作包六项(roleNeed + 人力/场地/材料/时段/资质),不碰服务容量三数。 */
    int upsertChapterWorkPackage(CmsTopicChapterTemplateMeta meta);
}

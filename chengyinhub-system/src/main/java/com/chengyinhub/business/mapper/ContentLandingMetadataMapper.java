package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ContentLandingMetadata;
import com.chengyinhub.business.domain.ContentLandingTranslation;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * M7 落地元信息与公开落地译文(写面)。
 *
 * <p>写入契约是「自然键 + 乐观锁」：
 * <ul>
 *   <li>新建用 {@code expectedVersion=0}，插入成功即 row_version=1；</li>
 *   <li>更新必须命中 {@code where row_version = expectedVersion}，影响 0 行即返回冲突；</li>
 *   <li>并发下只有一个写成功，另一个拿到 0 行后由服务层抛 409，不做静默覆盖。</li>
 * </ul>
 * 因此同一 payload 重试不会产生两行，也不会把别人的写入盖掉。
 */
public interface ContentLandingMetadataMapper {

    ContentLandingMetadata selectByTopicId(@Param("topicId") Long topicId);

    int insertMetadata(@Param("row") ContentLandingMetadata row);

    int updateMetadataVersioned(@Param("row") ContentLandingMetadata row,
                                @Param("expectedVersion") Long expectedVersion,
                                @Param("updateTime") Date updateTime);

    List<ContentLandingTranslation> selectTranslationsByTopicId(@Param("topicId") Long topicId);

    ContentLandingTranslation selectTranslation(@Param("topicId") Long topicId,
                                                @Param("locale") String locale);

    int insertTranslation(@Param("row") ContentLandingTranslation row);

    int updateTranslationVersioned(@Param("row") ContentLandingTranslation row,
                                   @Param("expectedVersion") Long expectedVersion,
                                   @Param("updateTime") Date updateTime);

    /** 后台审校列表：只返回已公开主题的落地元信息(未公开主题不进公开分发面)。 */
    List<ContentLandingMetadata> selectReviewable(@Param("keyword") String keyword);
}

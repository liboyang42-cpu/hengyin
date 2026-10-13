package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopReview;

/**
 * 合作双向互评 Mapper 接口(F4-2)
 */
public interface CoopReviewMapper {

    public int insertCoopReview(CoopReview r);

    /** 是否已评过(topic+from+to 唯一) */
    public int countExisting(@Param("topicId") Long topicId, @Param("fromId") Long fromId, @Param("toId") Long toId);

    /** 某被评人的评价列表 */
    public List<CoopReview> selectByToId(@Param("toId") Long toId);

    /** 某被评人的均分 + 条数 */
    public Map<String, Object> summaryByToId(@Param("toId") Long toId);
}

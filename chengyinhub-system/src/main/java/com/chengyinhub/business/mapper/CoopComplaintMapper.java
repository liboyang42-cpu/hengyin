package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopComplaint;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CoopComplaintMapper {
    List<com.chengyinhub.business.domain.vo.CoopComplaintProgressVO> selectMine(@Param("memberId") Long memberId);
    int insertCoopComplaint(CoopComplaint c);

    CoopComplaint selectCoopComplaintById(Long id);

    int updateCoopComplaint(CoopComplaint c);

    /**
     * 抢占「可垫付」态：只有 status=ACCEPTED 且未删的那一行会被改到。
     *
     * <p>★必须在动钱之前调用：原来的做法是普通 SELECT 判状态、再按 id 无条件 UPDATE，
     * 两个并发 advance 都读到 ACCEPTED ⇒ 双付真金。现在输的那一方拿到 rows=0，
     * 在任何余额变动发生之前就出局。
     */
    int claimAcceptedForAdvance(@Param("id") Long id,
                                @Param("amount") java.math.BigDecimal amount,
                                @Param("handlerId") Long handlerId,
                                @Param("updateTime") java.util.Date updateTime);

    /** 按已读取的开放状态抢占结案；必须与扣款及结案结果写入处于同一事务。 */
    int claimOpenForClose(@Param("id") Long id, @Param("expectedStatus") Integer expectedStatus,
                          @Param("updateTime") java.util.Date updateTime);

    /** 某主题未结工单数(status < 3),>0 即冻结该主题结算放款。 */
    int countOpenByTopicId(@Param("topicId") Long topicId);

    List<CoopComplaint> selectCoopComplaintList(CoopComplaint q);
}

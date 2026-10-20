package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TopicCompletionCouponReceiptMapper {
    final class Row {
        public Long logId, topicId, memberId, couponId, historyId;
        public Date grantedAt, validFrom, validUntil, usedAt;
        public Integer useStatus;
    }
    final class CurrentLink {
        public Long logId, topicId, memberId, couponId, receiptLogId, receiptTopicId, receiptMemberId, receiptCouponId, historyId;
        public Integer receiptVersion;
        public Date grantedAt;
    }
    int markExpectedReceipt(@Param("log") long log, @Param("topic") long topic, @Param("member") long member, @Param("coupon") long coupon, @Param("history") long history);
    CurrentLink lockCurrentLink(@Param("topic") long topic, @Param("member") long member, @Param("coupon") long coupon);
    SmsCouponHistory lockLinkedHistory(@Param("id") long id);
    Long lockLog(@Param("topic") long topic, @Param("member") long member, @Param("coupon") long coupon);
    SmsCoupon lockDefinition(@Param("id") long id);
    int appendExactHistory(@Param("log") long log, @Param("topic") long topic, @Param("member") long member,
                          @Param("coupon") long coupon, @Param("history") long history);
    Row owned(@Param("member") long member, @Param("log") long log);
    List<Row> ownedPage(@Param("member") long member, @Param("cursor") Long cursor, @Param("limit") int limit);
}

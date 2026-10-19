package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * CL-39：媒体机审 PASS 事实的只读查询。真源是 wx_content_callback_inbox(state=2 已落地, verdict=0 pass)，
 * 回调 inbox 的写侧不在这里，本 mapper 不写任何表。
 */
public interface ReviewedMediaMapper {
    /** 返回 biz_id + media_hash(送检 URL 的 SHA-256 原始字节)；bizIds 为空时调用方不进 SQL。 */
    List<Map<String, Object>> selectPassedMedia(@Param("bizType") int bizType,
                                                @Param("bizIds") Collection<Long> bizIds);
}

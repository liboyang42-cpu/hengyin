package com.chengyinhub.business.mapper.friend;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 黑名单合同（App Core/NativeBlacklist*.swift）的读写原语，真源仍是既有 im_block。
 * block_id = im_block.id（自增行的这一次拉黑），取消后再拉黑是新的 id。
 */
public interface NativeBlacklistMapper
{
    /** 本人拉黑的人，按 block_id 倒序；cursor=0 从头开始，否则只取 id &lt; cursor。 */
    List<Map<String, Object>> selectPage(@Param("viewer") long viewer, @Param("cursor") long cursor, @Param("limit") int limit);

    Long selectBlockId(@Param("viewer") long viewer, @Param("peer") long peer);

    Long lockBlockId(@Param("viewer") long viewer, @Param("peer") long peer);

    int deleteBlock(@Param("id") long id, @Param("viewer") long viewer, @Param("peer") long peer);
}

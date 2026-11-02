package com.chengyinhub.business.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AuthorWriteReceiptMapper {
    int insert(@Param("actor") Long actor, @Param("operation") String operation,
               @Param("requestId") String requestId, @Param("hash") String hash);
    Map<String, Object> lock(@Param("actor") Long actor, @Param("operation") String operation,
                             @Param("requestId") String requestId);
    Map<String, Object> read(@Param("actor") Long actor, @Param("operation") String operation,
                             @Param("requestId") String requestId);
    int complete(@Param("actor") Long actor, @Param("operation") String operation,
                 @Param("requestId") String requestId, @Param("result") String result);
}

package com.chengyinhub.business.appleiap.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.Map;

public interface AppleIapMapper {
    int insertOriginal(@Param("id") String id, @Param("identity") String identity);
    Map<String, Object> lockOriginal(@Param("id") String id);
    int bindOriginal(@Param("id") String id, @Param("order") String order);
    int insertRevocation(@Param("id") String id, @Param("date") Long date);
    int revokeOriginal(@Param("id") String id, @Param("date") Long date);
    int insertTransaction(@Param("id") String id, @Param("original") String original, @Param("hash") String hash);
    Map<String, Object> lockTransaction(@Param("id") String id);
    int insertNotification(@Param("uuid") String uuid, @Param("hash") String hash, @Param("payload") String payload);
    Map<String, Object> lockNotification(@Param("uuid") String uuid);
    int notificationState(@Param("uuid") String uuid, @Param("state") String state, @Param("reason") String reason);
}

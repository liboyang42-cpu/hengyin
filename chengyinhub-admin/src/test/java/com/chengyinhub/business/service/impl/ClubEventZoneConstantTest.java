package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B:P1-073 / C:U-017:俱乐部排期时区只有一个口径 CLUB_ZONE。
 * club_event_series.timezone 写入值必须和计算用的时区是同一个常量,不能各写各的字面量。
 */
class ClubEventZoneConstantTest {

    @Test
    void clubZoneIsShanghai() {
        assertEquals(ZoneId.of("Asia/Shanghai"), ClubEventOpsServiceImpl.CLUB_ZONE);
        assertEquals(ClubEventOpsServiceImpl.CLUB_ZONE_ID, ClubEventOpsServiceImpl.CLUB_ZONE.getId());
    }

    @Test
    void sourceUsesConstantForWriteAndMath() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubEventOpsServiceImpl.java")),
                StandardCharsets.UTF_8);
        assertFalse(src.contains("ZoneId.of(\"Asia/Shanghai\")"), "日期计算必须用 CLUB_ZONE,不要再写时区字面量");
        assertTrue(src.contains("series.setTimezone(CLUB_ZONE_ID)"), "timezone 列写入必须与计算口径同源");
    }
}

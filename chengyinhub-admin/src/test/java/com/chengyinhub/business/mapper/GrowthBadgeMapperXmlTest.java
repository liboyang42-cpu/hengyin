package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** B5:平台徽章墙 selectAllActive 不得带出俱乐部专属徽章(club_id 非空)。 */
class GrowthBadgeMapperXmlTest
{
    private static final String XML = "chengyinhub-system/src/main/resources/mapper/business/GrowthBadgeMapper.xml";

    @Test
    void selectAllActiveExcludesClubBadges() throws Exception
    {
        Path path = Paths.get(XML);
        if (!Files.exists(path)) path = Paths.get("..").resolve(XML);
        String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<select id=\"selectAllActive\".*?</select>", Pattern.DOTALL).matcher(xml);
        assertTrue(m.find(), "selectAllActive 必须存在");
        String sql = m.group().replaceAll("\\s+", " ");
        assertTrue(sql.contains("status = '0'") && sql.contains("club_id is null"),
                "平台墙只取 club_id 为空的徽章定义: " + sql);
    }

    @Test
    void ownedClubQueryOnlyReturnsClubBadgesByCode() throws Exception
    {
        Path path = Paths.get(XML);
        if (!Files.exists(path)) path = Paths.get("..").resolve(XML);
        String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<select id=\"selectActiveClubByCodes\".*?</select>", Pattern.DOTALL).matcher(xml);
        assertTrue(m.find(), "selectActiveClubByCodes 必须存在");
        String sql = m.group().replaceAll("\\s+", " ");
        assertTrue(sql.contains("club_id is not null") && sql.contains("badge_code in") && sql.contains("status = '0'"),
                "只取启用中、编码在已获得集合内的俱乐部徽章: " + sql);
    }
}

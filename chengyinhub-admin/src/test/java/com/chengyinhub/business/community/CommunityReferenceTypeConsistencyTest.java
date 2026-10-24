package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityReferenceType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * C:U-042:帖文关联类型的三处真相(服务白名单 / Mapper choose 分支 / DDL CHECK)必须是同一集合。
 * 以前 MEMBER 只在可选项里、CLUB 不在可选项里,选得出发不出;P0-24 修了值,本测试钉住以后不再分叉。
 */
class CommunityReferenceTypeConsistencyTest {

    private static final Set<String> EXPECTED = CommunityReferenceType.CODES;

    @Test
    void enumIsTheFiveFirstClassTypesWithoutMember() {
        assertEquals(Set.of("ACTIVITY", "TOPIC", "ROUTE", "CLUB", "POI"), EXPECTED);
        assertFalse(EXPECTED.contains("MEMBER"));
    }

    @Test
    void ddlCheckConstraintMatchesEnum() throws Exception {
        String sql = Files.readString(Paths.get("sql/migration_community_post_platform_20260909.sql"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("chk_community_post_reference_type CHECK \\(reference_type IN \\(([^)]*)\\)\\)").matcher(sql);
        if (!m.find()) throw new AssertionError("找不到 chk_community_post_reference_type");
        assertEquals(EXPECTED, quoted(m.group(1), "'([A-Z_]+)'"));
    }

    @Test
    void everyMapperReferenceChooseCoversExactlyTheEnum() throws Exception {
        String xml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/CommunityPostMapper.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String id : new String[] {"countReferenceTarget", "selectReferenceSnapshot", "selectReferenceOptions"}) {
            int start = xml.indexOf("<select id=\"" + id + "\"");
            if (start < 0) throw new AssertionError("找不到 " + id);
            String select = xml.substring(start, xml.indexOf("</select>", start));
            assertEquals(EXPECTED, quoted(select, "referenceType == '([A-Z_]+)'"), id + " 的分支与 CommunityReferenceType 不一致");
        }
    }

    private static Set<String> quoted(String text, String regex) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}

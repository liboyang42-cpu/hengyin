package com.chengyinhub.web.controller.business;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 8-07 阻断#20-A2: 后台首页「帖文审核」卡片的数字必须是待审口径。
 *
 * <p>原实现只数 {@code del_flag=0} 的全部帖文, 与卡片点进去的广场列表
 * ({@code check_status=0}) 对不上, 运营会把存量帖文量当成待处理积压量。</p>
 *
 * <p>负控: 把 {@code AND check_status=0} 去掉 → 第一条红。</p>
 */
class HomeDataCountContractTest {

    private static String homeDataSql() throws Exception {
        Path p = Paths.get("../chengyinhub-system/src/main/resources/mapper/business/UmsMemberMapper.xml");
        if (!Files.exists(p)) p = Paths.get("chengyinhub-system/src/main/resources/mapper/business/UmsMemberMapper.xml");
        String xml = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        int at = xml.indexOf("<select id=\"selectHomeDataList\"");
        assertTrue(at >= 0, "UmsMemberMapper.xml 缺 selectHomeDataList");
        return xml.substring(at, xml.indexOf("</select>", at));
    }

    @Test
    void 首页帖文数只数待审核帖文() throws Exception {
        String sql = homeDataSql();
        assertTrue(sql.contains("FROM ums_creative_square WHERE del_flag=0 AND check_status=0"),
                "creativeSquareNum 必须与广场列表同口径: del_flag=0 AND check_status=0");
        assertFalse(sql.contains("订单总数"),
                "注释不得再写成「订单总数」, 那是别的实体");
    }

    @Test
    void 其余待审计数保持check_status口径() throws Exception {
        String sql = homeDataSql();
        for (String table : new String[]{"cms_topic", "cms_activity", "cms_member_template"}) {
            assertTrue(sql.contains("FROM " + table + " WHERE del_flag=0 and check_status=0"),
                    table + " 的待审计数必须保持 del_flag=0 and check_status=0");
        }
    }
}

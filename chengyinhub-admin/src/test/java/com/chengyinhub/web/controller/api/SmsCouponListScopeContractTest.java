package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 营销页券卡与它点进去的那份商家券列表,必须是同一组 where(CU-M-126)。
 *
 * <p>列表侧的查询形状(带 merchantId + scopeType=1 + delFlag=0)由
 * {@code ApiCouponControllerMerchantAccessTest} 逐字段断言;本测试补 SQL 那一半 ——
 * 调用方设了 {@code delFlag} 而 XML 不认,等于删除口径两张皮:已删除的券照样列回列表,
 * 卡片却已经不数它了。
 *
 * <p>负控:删掉 XML 里的 {@code del_flag} 那条 {@code <if>} ⇒ 本测试红;
 * 把 {@code couponStats} 的 merchantId 去掉 ⇒
 * {@code MerchantAggregateReadServiceTest#marketingHomeCountsOnlyCouponsThatAreOnAir} 红。
 */
class SmsCouponListScopeContractTest {

    private static String selectSmsCouponList() throws Exception {
        Path p = Paths.get("../chengyinhub-system/src/main/resources/mapper/business/SmsCouponMapper.xml");
        if (!Files.exists(p)) {
            p = Paths.get("chengyinhub-system/src/main/resources/mapper/business/SmsCouponMapper.xml");
        }
        String xml = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        int at = xml.indexOf("<select id=\"selectSmsCouponList\"");
        assertTrue(at >= 0, "selectSmsCouponList 不见了,商家券列表和营销页券卡都没有取数入口");
        return xml.substring(at, xml.indexOf("</select>", at));
    }

    @Test
    void 归属与删除四个条件都在where里() throws Exception {
        String sel = selectSmsCouponList();
        assertTrue(sel.contains("and member_id = #{memberId}"), "归属人");
        assertTrue(sel.contains("and merchant_id = #{merchantId}"), "商家节点");
        assertTrue(sel.contains("and scope_type = #{scopeType}"), "券范围(1=商家券)");
        assertTrue(sel.contains("and del_flag = #{delFlag}"),
                "调用方一直在设 delFlag=0,这里不认就把已删除的券列回去");
    }

    @Test
    void delFlag条件只在显式传值时生效() throws Exception {
        // RuoYi 后台的券列表不传 delFlag,那条 if 不能变成无条件 where,否则后台看不到已删除的券。
        String sel = selectSmsCouponList();
        int at = sel.indexOf("del_flag = #{delFlag}");
        assertTrue(at > 0 && sel.lastIndexOf("<if", at) > sel.indexOf("<where>", at),
                "del_flag 必须是 <where> 内的一个 <if>,不是硬编码条件");
    }
}

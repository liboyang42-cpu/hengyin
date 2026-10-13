package com.chengyinhub.business.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CRM 客户画像的手机号快照:落库即脱敏,不存明文。
 *
 * <p>背景:merchant_customer_profile.member_phone 是客户画像快照。旧 aggregate 曾从购物车重灌，
 * 现已删除；唯一写入源为 MerchantCrmAppMapper 的报名真源增量 upsert，且必须落库即脱敏。
 *
 * <p>SQL 掩码逻辑脱库跑不了单测,故用两条:①结构断言钉死源头不再写明文;②验证读时兜底幂等。
 */
class CrmPhoneSnapshotMaskTest {

    /** 从 chengyinhub-admin 模块根指向 chengyinhub-system 的新 CRM mapper。 */
    private static final Path APP_MAPPER = Paths.get(
            "../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmAppMapper.xml");

    /**
     * ★ 钉源头,不钉工具方法。旧 aggregate 写入口已移除，当前批量 upsert 必须持续禁止
     * 「u.phone as member_phone」这种裸明文快照。
     */
    @Test
    void 增量画像不得把明文手机号快照进画像表() throws Exception {
        assertTrue(Files.exists(APP_MAPPER),
                "mapper 路径变了就得改这条,别让它静默失效:" + APP_MAPPER.toAbsolutePath());
        String xml = new String(Files.readAllBytes(APP_MAPPER), StandardCharsets.UTF_8);

        // 归一化空白,避免换行/缩进影响匹配
        String flat = xml.replaceAll("\\s+", " ");

        assertFalse(flat.contains("u.phone as member_phone"),
                "owner-scoped 增量画像不得把 u.phone 明文快照进 member_phone;"
                        + "必须用 SQL 掩码(concat(left(...),'****',right(...)))落库即脱敏。");

        assertTrue(flat.contains("'****'") && flat.contains("as member_phone"),
                "member_phone 必须经掩码表达式落库(concat + '****')");
        assertTrue(flat.contains("upsertOwnedCustomerProfiles"), "必须钉住当前唯一批量画像写入口");
    }

    /**
     * 落库存的是掩码值、读路径又 maskPhone 一次,双重脱敏必须幂等,否则会二次损坏显示。
     * 138****1234(11 位掩码)再 maskPhone 应仍是 138****1234。
     */
    @Test
    void 存掩码值再读一次脱敏必须幂等() {
        String plain = "13812341234";
        String stored = plain.substring(0, 3) + "****" + plain.substring(7);   // 模拟 SQL 掩码
        assertEquals(stored, PiiCryptoService.maskPhone(stored),
                "对 11 位掩码值再 maskPhone 必须原样返回,否则读路径的兜底会把显示二次弄坏");
    }
}

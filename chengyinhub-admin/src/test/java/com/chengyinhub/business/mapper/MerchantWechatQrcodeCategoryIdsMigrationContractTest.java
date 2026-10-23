package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * owner 2026-10-22 门店信息:门店微信拆成「微信号 + 二维码」两列、行业类型多选。
 * 两份迁移必须可重复执行(列存在就跳过、同名异形 fail-closed),搬家/回填 UPDATE 重跑不再命中,
 * 并带回读断言 —— 否则重跑会把商家新传的二维码覆盖掉,或在漏跑时零告警。
 */
class MerchantWechatQrcodeCategoryIdsMigrationContractTest {

    private static String sql(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("sql", name)), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void wechatQrcodeMigrationIsIdempotentAndMovesUrlValuesOnce() throws Exception {
        String sql = sql("migration_merchant_wechat_qrcode_20261022.sql");
        assertTrue(sql.contains("from information_schema.columns")
                && sql.contains("column_name='wechat_qrcode'"), "ALTER 前必须查列是否已存在");
        assertTrue(sql.contains("add column wechat_qrcode varchar(512)"));
        assertTrue(sql.contains("not (data_type='varchar' and character_maximum_length=512"),
                "同名但形状不符的列必须 fail-closed");
        assertTrue(sql.contains("set wechat_qrcode = wechat")
                && sql.contains("(wechat_qrcode is null or wechat_qrcode = '')"),
                "搬家只填空的 wechat_qrcode,不覆盖商家新上传的二维码");
        assertTrue(sql.contains("set wechat = ''") && sql.contains("wechat like 'https://%'"),
                "搬完要清掉 wechat 里的 URL,前端才不会把网址当微信号显示");
        assertTrue(sql.contains("runner: assert=scalar-true"), "必须带回读断言");
    }

    @Test
    void categoryIdsMigrationIsIdempotentAndBackfillsLegacySingleCategory() throws Exception {
        String sql = sql("migration_merchant_category_ids_20261022.sql");
        assertTrue(sql.contains("from information_schema.columns")
                && sql.contains("column_name='category_ids'"));
        assertTrue(sql.contains("add column category_ids varchar(255)"));
        assertTrue(sql.contains("set category_ids = cast(category_id as char)")
                && sql.contains("(category_ids is null or category_ids = '')"),
                "回填只命中空的 category_ids,重跑不改已多选的数据");
        assertTrue(sql.contains("runner: assert=scalar-true"));
    }
}

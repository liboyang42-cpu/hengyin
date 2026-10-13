package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF-34 昵称唯一性迁移的契约。
 *
 * ⚠️ 2026-08-28 用户裁决改写了这份契约,正向断言从「加唯一索引」变成了「**不许**加唯一索引」。
 * 起因是执行前的生产只读预检:ums_member 共 54 人、空昵称 0 人,其中 43 人昵称都是
 * **默认的**「城瘾用户」。原迁移会给其中 42 个真实用户的显示名追加 16 位十六进制后缀
 * (「城瘾用户#a3f91c2e」),不可逆,且原名没有留存。
 *
 * 重名的成因是默认值撞车,不是用户各自起了同名 —— 对这种重复施加唯一约束,
 * 等于拿存量用户的身份去偿还一个还不存在的问题。裁决:唯一性交给应用层
 * (NicknameSuggestionServiceImpl 查重 + 给建议名),DB 唯一索引留到用户量真起来
 * 且大家都改过昵称之后再加。
 *
 * 所以下面的 assertFalse 不是「暂时没做」,是**闸门**:谁要把这三样加回来,
 * 得先拿到新的裁决,并同时更新这份测试和它上面的理由。
 */
class NicknameMigrationContractTest {
    private static final Path MIGRATION = Paths.get("sql", "migration_nickname_uniqueness_20260828.sql");

    @Test
    void keepsComparisonKeyButMustNotRewriteAnyExistingNicknameOrConstrainThem() throws Exception {
        // ⚠️ 必须先剥 `--` 注释再断言。这份迁移的注释里逐条写着「删掉了哪些东西、为什么删」,
        // 里面自然出现 CREATE TRIGGER / uk_ums_member_nickname_key 这些字样;不剥注释的话
        // assertFalse 会被自己的说明文字打红 —— 实测踩到过。契约管的是**会被执行的 SQL**。
        String sql = stripSqlComments(new String(Files.readAllBytes(MIGRATION), StandardCharsets.UTF_8))
                .replaceAll("\\s+", " ");

        // 保留:纯派生的比较键,countActiveNickname 查的就是它。不改任何人的数据。
        assertTrue(sql.contains("GENERATED ALWAYS AS (LOWER(TRIM(nickname))) STORED"),
                "nickname_key 派生列必须保留 —— UmsMemberMapper.countActiveNickname 依赖它");

        // 删除项 ①:唯一索引。加了它就必须先把 42 个真实用户改名。
        // 断的是 DDL 形状而不是裸索引名 —— 迁移末尾的 assert 块有一条**负向回读**
        // (information_schema.statistics ... index_name = 'uk_ums_member_nickname_key' 必须为 0),
        // 它是可执行 SQL、剥不掉,按裸名字断会把这条自证判成违规。
        assertFalse(sql.contains("ADD UNIQUE KEY"),
                "不许加昵称唯一索引:43/54 的重名全是默认昵称,加索引的前提是改写 42 个真实用户的显示名");

        // 删除项 ②:后缀改写。这是真正动用户数据、且不可逆的那一步。
        assertFalse(sql.contains("duplicate_no"),
                "不许改写存量昵称:原迁移会把真实用户的名字变成「城瘾用户#a3f91c2e」且不可逆");

        // 删除项 ③:触发器。生产账号无 SUPER/SET_USER_ID,log_bin_trust_function_creators=0,
        // CREATE TRIGGER 会直接 1419(2026-08-28 实测);删掉 ①② 之后它要兜的场景也不存在了。
        assertFalse(sql.contains("CREATE TRIGGER"),
                "生产库账号建不了触发器(1419),且删掉唯一索引后它已无用");

        // MySQL 8 不支持,建表门禁也拦
        assertFalse(sql.contains("ADD COLUMN IF NOT EXISTS"));
    }

    private static String stripSqlComments(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (String line : raw.split("\n", -1)) {
            int marker = line.indexOf("--");
            out.append(marker >= 0 ? line.substring(0, marker) : line).append('\n');
        }
        return out.toString();
    }
}

package com.chengyinhub.business.mapper;

import com.chengyinhub.business.service.support.PlayHelpCostApplication;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-16 提示收费所依赖的**既有**账本闸必须在源码里真实存在，而不是靠应用层「先查再写」或
 * 靠把 {@code event_id} 置 NULL 冒充幂等。
 *
 * <p>本测试是静态契约（解析 mapper XML 与 migration SQL + 反射注解），不连库、不跑 SQL。
 * 它证明的是「这些语句/索引/事务语义在源码里确实这么写」；真实 MySQL 上的行锁、RR 快照、
 * 并发撞键与父事务回滚仍必须由 I 在批准 VPS 执行。
 */
class PlayHelpLedgerContractTest {

    private static final Path ROOT = Paths.get("..");
    private static final Path MAPPER = ROOT.resolve(Paths.get("chengyinhub-system", "src", "main", "resources",
            "mapper", "business"));
    private static final Path MIGRATIONS = ROOT.resolve(Paths.get("chengyinhub-admin", "sql"));

    private static String squashed(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 取出 {@code <insert|select|update|delete id="...">} 到对应闭合标签之间的原文。 */
    private static String statement(String mapperFile, String id) throws Exception {
        String xml = new String(Files.readAllBytes(MAPPER.resolve(mapperFile)),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String lower = xml.toLowerCase(Locale.ROOT);
        String loweredId = id.toLowerCase(Locale.ROOT);
        for (String tag : Arrays.asList("insert", "select", "update", "delete")) {
            String opener = "<" + tag + " id=\"" + loweredId + "\"";
            int start = lower.indexOf(opener);
            if (start < 0) continue;
            int stop = lower.indexOf("</" + tag + ">", start);
            assertTrue(stop > start, "unterminated statement: " + id);
            return lower.substring(start, stop);
        }
        throw new AssertionError("statement missing: " + mapperFile + "#" + id);
    }

    /**
     * 钱这一层：通用写入口必须自己填 {@code reward_business_key}。
     * 若它不填，唯一键永远为 NULL，MySQL 视 NULL 互不相等 ⇒ 去重彻底失效。
     */
    @Test
    void genericPointsInsertWritesRewardBusinessKey() throws Exception {
        String insert = statement("UmsMemberPointsDetailMapper.xml", "insertUmsMemberPointsDetail")
                .toLowerCase(Locale.ROOT);
        assertTrue(insert.contains("reward_business_key"), "列名必须出现");
        assertTrue(insert.contains("#{rewardbusinesskey}"), "值必须绑定 rewardBusinessKey");
    }

    /** 每局每级这一层：既有尝试日志写入口必须自己填幂等键。 */
    @Test
    void attemptLogInsertWritesIdempotencyKey() throws Exception {
        String insert = statement("PlayAttemptLogMapper.xml", "insertPlayAttemptLog")
                .toLowerCase(Locale.ROOT);
        assertTrue(insert.contains("idempotency_key"));
        assertTrue(insert.contains("#{idempotencykey}"));
    }

    /** 替代业务唯一键真实存在，且有回读校验；不是「理论上可以用」。 */
    @Test
    void rewardBusinessUniqueKeyIsCreatedAndVerifiedByMigration() throws Exception {
        String sql = squashed(MIGRATIONS.resolve("migration_p1_funds_core_uniqueness_20260724.sql"));
        assertTrue(sql.contains("add unique key uk_member_reward_business (member_id, reward_business_key)"));
        assertTrue(sql.contains("'0:member_id,reward_business_key'"), "必须以 information_schema 回读定义");
        assertTrue(sql.contains("drop index uk_member_reward_business"), "定义不符时先删再建");
        assertTrue(sql.contains("add column reward_business_key varchar(128)"));
    }

    @Test
    void perRunIdempotencyUniqueIndexIsCreatedByMigration() throws Exception {
        String sql = squashed(MIGRATIONS.resolve("migration_20260820_new_city_preference_chain.sql"));
        assertTrue(sql.contains("add unique index uk_play_attempt_idempotency (idempotency_key)"));
        assertTrue(sql.contains("column_name='idempotency_key'"));
    }

    /**
     * 旧唯一键 {@code uk_member_event_once(member, event_type, event_id)} 的 event_id 必须可为 NULL，
     * 否则本类写 {@code event_id = NULL} 会被拒；同时这条**不能**替代表达每局语义。
     */
    @Test
    void legacyEventOnceKeyIsNullableAndNotThePerRunKey() throws Exception {
        String schema = squashed(ROOT.resolve(Paths.get("sql", "base_schema_20260818.sql")));
        assertTrue(schema.contains("unique key `uk_member_event_once` (`member_id`,`event_type`,`event_id`)"));
        assertTrue(schema.contains("`event_id` bigint default null"));
        assertTrue(schema.contains("unique key `uk_member_reward_business` (`member_id`,`reward_business_key`)"));
    }

    /** 余额判定必须在一条 UPDATE 里完成，不能「先读再减」——否则并发下会扣成负数。 */
    @Test
    void pointsDebitIsConditionalInOneStatement() throws Exception {
        String update = statement("UmsMemberMapper.xml", "deductPoints").toLowerCase(Locale.ROOT);
        assertTrue(update.contains("set point = point - #{point}"));
        assertTrue(update.contains("and point >= #{point}"), "余额不足必须影响 0 行而不是扣成负数");
    }

    /** 锁内读积分：普通 select 会读 RR 快照与 MyBatis 一级缓存，不能用来定扣款额。 */
    @Test
    void pointsAreReadUnderLock() throws Exception {
        String select = statement("UmsMemberMapper.xml", "selectPointsForUpdate").toLowerCase(Locale.ROOT);
        assertTrue(select.contains("for update"));
        assertTrue(select.contains("point"));
    }

    /**
     * 父事务是唯一原子边界：本类入口必须是 MANDATORY（没有外层事务就当场失败，
     * 不允许「悄悄各自提交」），且回滚范围必须覆盖运行期异常。
     */
    @Test
    void costEntryPointsRequireAnOuterTransaction() throws Exception {
        assertEquals(PlayHelpCostApplication.ATTEMPT_TYPE_HELP, 5,
                "复用既有谜题提示类型，v_node_quality 才继续把它排除在通过率之外");
        for (String name : Arrays.asList("use", "release")) {
            java.lang.reflect.Method method = Arrays.stream(PlayHelpCostApplication.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElse(null);
            assertNotNull(method, "missing method: " + name);
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertNotNull(transactional, name + " 必须声明事务语义");
            assertEquals(Propagation.MANDATORY, transactional.propagation(),
                    name + " 不能自己开事务：原子边界在 G4 外层");
            assertTrue(Arrays.asList(transactional.rollbackFor()).contains(Exception.class),
                    name + " 必须回滚运行期异常，否则已写事实不撤销");
        }
    }

    /**
     * 复用 attempt_type=5 的前提：质量视图确实排除它。若有人放开这条，帮助事实会进入
     * 通过率/放弃率分母，本节必须重新选类型并补 DDL。
     */
    @Test
    void nodeQualityViewStillExcludesHelpFacts() throws Exception {
        String sql = squashed(MIGRATIONS.resolve("migration_20260820_questo_puzzle_atmosphere.sql"));
        assertTrue(sql.contains("create or replace view v_node_quality"));
        assertTrue(sql.contains("where attempt_type is null or attempt_type <> 5"));
    }
}

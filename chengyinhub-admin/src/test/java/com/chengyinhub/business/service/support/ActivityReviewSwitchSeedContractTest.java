package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest.ReviewMode;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #376:活动分级审核总闸的两个 sys_config 键必须有落种 migration,且落种值 = 代码现行默认
 * (键缺失时的行为),落种前后行为不变;只在缺失时插入,不覆盖运维已改过的值。
 */
class ActivityReviewSwitchSeedContractTest {
    private static final String MIGRATION = "migration_20261022_audit_activity_switch_seed.sql";
    private static final Pattern SEED_ROW = Pattern.compile(
            "select\\s+'[^']*',\\s*'([^']+)',\\s*'([^']*)',\\s*'n'", Pattern.CASE_INSENSITIVE);

    private static String sql() throws Exception {
        return new String(Files.readAllBytes(Paths.get("sql", MIGRATION)), StandardCharsets.UTF_8);
    }

    private static Map<String, String> seededValues(String sql) {
        Map<String, String> values = new HashMap<>();
        Matcher m = SEED_ROW.matcher(sql);
        while (m.find()) values.put(m.group(1), m.group(2));
        return values;
    }

    @Test
    void migrationSeedsBothPolicyKeysIdempotentlyWithoutOverwriting() throws Exception {
        String sql = sql();
        String flat = sql.toLowerCase().replaceAll("\\s+", " ");
        Map<String, String> seeded = seededValues(sql);

        assertEquals(2, seeded.size(), "只落种总闸与高风险类目两个键: " + seeded);
        assertTrue(seeded.containsKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL));
        assertTrue(seeded.containsKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES));
        for (String key : seeded.keySet()) {
            assertTrue(flat.contains("where not exists (select 1 from sys_config where config_key = '" + key + "')"),
                    key + " 必须只在缺失时插入(可重复执行)");
        }
        assertFalse(flat.matches(".*\\bupdate\\s+sys_config\\b.*"), "不得覆盖运维已配置的值");
        assertFalse(flat.matches(".*\\bdelete\\s+from\\b.*"), "落种不删行");
        assertTrue(flat.contains("runner: assert=scalar-true"), "必须带回读断言");
    }

    @Test
    void seededValuesKeepCurrentDefaultBehaviour() throws Exception {
        Map<String, String> seeded = seededValues(sql());
        assertEquals("false", seeded.get(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL));
        assertEquals("", seeded.get(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES));

        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setName("周末徒步");
        request.setCategoryIds("3,7");
        ActivityReviewPolicy.Publisher club = new ActivityReviewPolicy.Publisher(false, false);

        // 键缺失(落种前生产现状):RuoYi selectConfigByKey 回空串
        assertEquals(ReviewMode.POST_REVIEW, modeWith("", "", request, club));
        assertEquals(ReviewMode.POST_REVIEW, modeWith(null, null, request, club));
        // 落种后:同样先发后审
        assertEquals(ReviewMode.POST_REVIEW, modeWith(seeded.get(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL),
                seeded.get(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES), request, club));
        // 总闸行落种后改 true 即生效(回滚阀可用)
        assertEquals(ReviewMode.PRE_REVIEW, modeWith("true",
                seeded.get(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES), request, club));
    }

    private static ReviewMode modeWith(String preReviewAll, String highRisk,
                                       CmsActivityPublishRequest request, ActivityReviewPolicy.Publisher publisher) {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn(preReviewAll);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn(highRisk);
        ActivityReviewPolicy policy = new ActivityReviewPolicy();
        ReflectionTestUtils.setField(policy, "configService", config);
        return policy.modeOf(request, publisher);
    }
}

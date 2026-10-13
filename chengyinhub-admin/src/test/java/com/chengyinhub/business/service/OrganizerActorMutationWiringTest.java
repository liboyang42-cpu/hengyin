package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 4-3 写入口矩阵：单条入口、mapper 绕路与新期复制都不能绕开 organizer 深模块。 */
class OrganizerActorMutationWiringTest {

    @Test
    void controllerUsesServerOperatorAndDeepMutationSeam() throws Exception {
        String source = source("src/main/java/com/chengyinhub/web/controller/business/CmsTopicController.java");
        int endpoint = source.indexOf("@PutMapping(\"/{id}/organizer-actor\")");
        assertTrue(endpoint >= 0, "ORGANIZER actor 缺后台修改入口");
        int audit = source.lastIndexOf("@Log(", endpoint);
        String annotations = source.substring(Math.max(0, audit), endpoint);
        assertTrue(audit >= 0
                        && annotations.contains("title = \"主题组织方 actor 改判\"")
                        && annotations.contains("businessType = BusinessType.UPDATE"),
                "幂等返回也必须经过可回读的 sys_oper_log 审计切面:" + annotations);
        int nextMethod = source.indexOf("\n    /**", endpoint + 20);
        String body = source.substring(endpoint, nextMethod < 0 ? source.length() : nextMethod);
        assertTrue(body.contains("productRoleService.declareRequiredOrganizerSlot("), body);
        assertTrue(body.contains("getUserId()"), "声明人必须取服务端登录账号，不能信请求体:" + body);
        assertFalse(body.contains("@RequestBody"), "探店日 organizer 恒为 PLATFORM，不接受客户端主体:" + body);
    }

    @Test
    void noMainServiceWritesOrganizerSlotOutsideProductRoleDeepModule() throws Exception {
        Path root = Paths.get("../chengyinhub-system/src/main/java").normalize();
        if (!Files.exists(root)) root = Paths.get("chengyinhub-system/src/main/java");
        List<String> bypasses = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    if (source.contains("setSlot(ProductSlot.ORGANIZER)")
                            && !path.endsWith("ProductRoleServiceImpl.java")) {
                        bypasses.add(path.toString());
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertEquals(java.util.Collections.emptyList(), bypasses,
                "发现绕开 lifecycle/scope 闸的 ORGANIZER 写 service");

        String reopen = source("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicEditionReopenServiceImpl.java");
        assertTrue(reopen.contains("role.setSlot(ProductSlot.CONTENT)"), "新期复制只应新建 CONTENT 槽");
        assertFalse(reopen.contains("role.setSlot(ProductSlot.ORGANIZER)"),
                "新期不得从历史期偷偷复制 ORGANIZER；必须重新声明 actor");
    }

    @Test
    void migrationOnlyExtendsExistingRoleTruthAndDoesNotReintroduceH11() throws Exception {
        String sql = source("sql/migration_20260815_shop_day_organizer_actor.sql").toUpperCase();
        assertTrue(sql.contains("ALTER TABLE PRODUCT_ROLE"));
        assertTrue(sql.contains("ADD COLUMN DECLARED_BY BIGINT"));
        assertFalse(sql.contains("CREATE TABLE"), "4-3 不得另建第二套 organizer 真源");
        for (String forbidden : new String[] {"TIER_K", "K_MIN", "UNLOCKED_AT", "OPEN_LINE", "MIN_PEOPLE"}) {
            assertFalse(sql.contains(forbidden), "4-3 不得捡回 H11:" + forbidden);
        }
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get(relative).normalize();
        if (!Files.exists(path) && relative.startsWith("../chengyinhub-system/")) {
            path = Paths.get(relative.substring(3));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

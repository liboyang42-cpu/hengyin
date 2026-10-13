package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容/票款归属主体的库级底线。
 *
 * <p>Controller 与 Service 只保护新请求；历史版本或直连写入留下的行仍会被
 * {@code ProductRoleMapper.selectActiveByTopicAndSlot} 读到。因此生效 CONTENT 槽必须由
 * 数据库拒绝 MERCHANT，失效行仍保留为归属变更审计。
 */
class ProductRoleContentOwnerSchemaGuardTest {

    private static final String MIGRATION =
            "chengyinhub-admin/sql/migration_product_role_content_owner_guard_20260723.sql";

    @Test
    void activeContentSlotOnlyAllowsContentOwners() throws Exception {
        String executableSql = WiringSource.read(MIGRATION)
                .replaceAll("(?m)^\\s*--.*$", "")
                .replaceAll("\\s+", " ")
                .replaceAll("\\( ", "(")
                .replaceAll(" \\)", ")")
                .trim();

        assertTrue(executableSql.contains("ALTER TABLE product_role ADD CONSTRAINT "
                        + "ck_role_active_content_owner CHECK (slot <> 'CONTENT' OR status <> 1 "
                        + "OR entity_type IN ('PLATFORM', 'MEMBER', 'CLUB'))"),
                "生效 CONTENT 槽必须由库级 CHECK 排除 MERCHANT，避免旧接口或直连写入绕过应用层");
    }
}

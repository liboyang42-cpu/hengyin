package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 未支付报名取消的积分/库存回补必须由数据库 CAS 唯一领取。 */
class RegistrationCancelExactlyOnceContractTest {

    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    @Test
    void cancellationClaimsOnceAndPersistsReturnMarkerBeforeSideEffects() throws Exception {
        String service = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        String mapper = source("chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CmsRegistrationMapper.java");
        String xml = source("chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml");

        int cancel = service.indexOf("boolean cancelRegistration");
        int claim = service.indexOf("cancelUnpaidIfActive", cancel);
        int points = service.indexOf("umsMemberMapper.addPoints", cancel);
        int inventory = service.indexOf("restoreTicketInventory", cancel);

        assertTrue(mapper.contains("int cancelUnpaidIfActive("));
        assertTrue(xml.contains("<update id=\"cancelUnpaidIfActive\">"));
        assertTrue(xml.contains("points_returned = 1"));
        assertTrue(xml.contains("member_id = #{memberId}"), "owner 必须下推 SQL");
        assertTrue(claim > cancel && points > claim && inventory > claim,
                "积分和库存回补只能发生在 CAS 获权后");
    }
}

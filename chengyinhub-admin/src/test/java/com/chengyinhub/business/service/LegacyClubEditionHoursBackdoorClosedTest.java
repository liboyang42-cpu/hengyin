package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.ClubEditionCompensationMapper;
import com.chengyinhub.business.mapper.TopicEditionFundsMapper;
import com.chengyinhub.web.controller.business.TopicEditionFundsController;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 的硬要求：旧的 mutable 工时写链必须整条消失，不是「新服务正确、旧入口还在」。
 *
 * <p>旧链是 Controller.saveHours → ITopicEditionFundsService.saveHours →
 * TopicEditionFundsServiceImpl.saveHours → TopicEditionFundsMapper.upsertHours →
 * XML 的 ON DUPLICATE KEY UPDATE（覆盖 planned/reported/confirmed/status/audit 全套）。
 * 它能一把改掉冻结计划与确认状态，等于绕开预审批与专用确认权限。
 *
 * <p>这些断言全是结构性的：谁把任何一环加回来，这里立刻红。
 */
class LegacyClubEditionHoursBackdoorClosedTest {

    @Test
    void noWriteHoursMethodSurvivesOnTheOldServiceSeam() {
        assertFalse(hasMethod(ITopicEditionFundsService.class, "saveHours"),
                "ITopicEditionFundsService.saveHours 必须删除，不是保留后拒绝");
        assertFalse(hasMethod(TopicEditionFundsController.class, "saveHours"),
                "TopicEditionFundsController.saveHours 必须删除");
        assertFalse(hasMethod(TopicEditionFundsMapper.class, "upsertHours"),
                "TopicEditionFundsMapper.upsertHours 必须删除");
    }

    @Test
    void readOnlyHoursQueryIsDeliberatelyKept() {
        assertTrue(hasMethod(TopicEditionFundsMapper.class, "selectHours"),
                "只读 selectHours 是财务总览要用的，别一起删掉");
    }

    @Test
    void theOldMapperXmlNoLongerTouchesClubEditionHoursForWriting() {
        String xml = resource("mapper/business/TopicEditionFundsMapper.xml").toLowerCase(Locale.ROOT);

        assertFalse(xml.contains("insert into club_edition_hours"),
                "旧 mapper XML 不得再写 club_edition_hours");
        assertFalse(xml.contains("update club_edition_hours"),
                "旧 mapper XML 不得再改 club_edition_hours");
        assertTrue(xml.contains("from club_edition_hours"), "只读查询应保留");
    }

    /**
     * 全仓唯一残存的 club_edition_hours upsert 只允许改 planned_hours。
     * 一旦有人把 confirm_status / confirmed_* / reported_* 加回 update 子句，
     * 冻结计划与「一次确认」就同时失守 —— 这条断言正是钉死那个形状。
     */
    @Test
    void theOnlySurvivingUpsertMayOnlyOverwriteThePlannedHoursColumn() {
        List<String> offenders = new ArrayList<String>();
        for (String xmlPath : new String[]{"mapper/business/TopicEditionFundsMapper.xml",
                "mapper/business/ClubEditionCompensationMapper.xml"}) {
            String xml = resource(xmlPath).toLowerCase(Locale.ROOT);
            int at = xml.indexOf("on duplicate key update");
            while (at >= 0) {
                String clause = xml.substring(at, Math.min(xml.length(), at + 400));
                int end = clause.indexOf("</insert>");
                if (end > 0) clause = clause.substring(0, end);
                for (String forbidden : new String[]{"confirm_status", "confirmed_actual_hours", "confirmed_by",
                        "confirmed_at", "reported_actual_hours", "reported_by", "reported_at", "club_id"}) {
                    if (clause.contains(forbidden)) offenders.add(xmlPath + " -> " + forbidden);
                }
                at = xml.indexOf("on duplicate key update", at + 1);
            }
        }
        assertEquals(new ArrayList<String>(), offenders,
                "upsert 只许覆盖 planned_hours；这些列被加回了覆盖子句:" + offenders);
    }

    @Test
    void hoursConfirmationIsGuardedByAnOldStatusCasNotByARequestBodyField() {
        String xml = resource("mapper/business/ClubEditionCompensationMapper.xml").toLowerCase(Locale.ROOT);
        int at = xml.indexOf("<update id=\"confirmactualhourscas\">");

        assertTrue(at >= 0, "确认工时必须是带旧状态条件的 CAS 语句");
        String statement = xml.substring(at, xml.indexOf("</update>", at));
        assertTrue(statement.contains("confirm_status = 'reported'"),
                "确认必须以 REPORTED 为旧状态条件，否则请求体直传 CONFIRMED 又能生效");
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) return true;
        }
        return false;
    }

    private static String resource(String path) {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("找不到 " + path);
            Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + path, e);
        }
    }
}

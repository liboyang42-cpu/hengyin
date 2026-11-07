package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第十轮 f2 第 2 项(j2 F-01a):开了商家承接的主题,商家报名窗口从主题自身日期补齐,存量值不动。 */
class MerchantSignUpWindowDefaultTest {

    private static final long DAY = 86_400_000L;
    private final Date now = new Date(1_793_000_000_000L);

    private CmsTopic open(Date recruitDeadline, Date endDate) {
        CmsTopic t = new CmsTopic();
        t.setMerchantStatus(1);
        t.setRecruitDeadline(recruitDeadline);
        t.setEndDate(endDate);
        return t;
    }

    @Test
    void newMerchantTopic_windowRunsFromNowToRecruitDeadline() {
        Date deadline = new Date(now.getTime() + 3 * DAY);
        CmsTopic t = open(deadline, new Date(now.getTime() + 60 * DAY));
        CmsTopicServiceImpl.fillMerchantSignUpWindow(t, null, now);
        assertEquals(now, t.getMerchantSignUpStartDate());
        assertEquals(deadline, t.getMerchantSignUpEndDate());
    }

    @Test
    void withoutRecruitDeadline_windowEndsWithTheTheme() {
        Date end = new Date(now.getTime() + 60 * DAY);
        CmsTopic t = open(null, end);
        CmsTopicServiceImpl.fillMerchantSignUpWindow(t, null, now);
        assertEquals(end, t.getMerchantSignUpEndDate());
    }

    @Test
    void existingWindow_isNeverOverwritten() {
        CmsTopic exist = new CmsTopic();
        exist.setMerchantSignUpStartDate(new Date(now.getTime() - 10 * DAY));
        CmsTopic patch = open(new Date(now.getTime() + 3 * DAY), new Date(now.getTime() + 60 * DAY));
        CmsTopicServiceImpl.fillMerchantSignUpWindow(patch, exist, now);
        assertNull(patch.getMerchantSignUpStartDate());
        assertNull(patch.getMerchantSignUpEndDate());
    }

    @Test
    void selfRunTopic_getsNoWindow() {
        CmsTopic t = open(null, new Date(now.getTime() + 60 * DAY));
        t.setMerchantStatus(0);
        CmsTopicServiceImpl.fillMerchantSignUpWindow(t, null, now);
        assertNull(t.getMerchantSignUpStartDate());
    }

    @Test
    void updateFillsFromStoredDates_whenPatchCarriesNone() {
        CmsTopic exist = new CmsTopic();
        Date end = new Date(now.getTime() + 30 * DAY);
        exist.setEndDate(end);
        CmsTopic patch = open(null, null);
        CmsTopicServiceImpl.fillMerchantSignUpWindow(patch, exist, now);
        assertEquals(end, patch.getMerchantSignUpEndDate());
    }

    @Test
    void createAndRebuildBothCallTheDefault() throws Exception {
        Path src = Paths.get("..", "chengyinhub-system", "src", "main", "java", "com", "chengyinhub", "business",
                "service", "impl", "CmsTopicServiceImpl.java");
        String s = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertTrue(s.contains("fillMerchantSignUpWindow(topic, null, new Date());"), "建题必须补报名窗口");
        assertTrue(s.contains("fillMerchantSignUpWindow(patch, exist, new Date());"), "编辑重建必须补报名窗口");
    }
}

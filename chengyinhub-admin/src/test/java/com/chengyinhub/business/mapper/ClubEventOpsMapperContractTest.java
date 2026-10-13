package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubEventOpsMapperContractTest {
    private String xml() throws Exception {
        Path path = Paths.get("../chengyinhub-system/src/main/resources/mapper/business/ClubEventOpsMapper.xml");
        assertTrue(Files.isRegularFile(path), "ClubEventOpsMapper.xml missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String migration() throws Exception {
        Path path = Paths.get("sql/migration_club_role_event_ops_20260823.sql");
        assertTrue(Files.isRegularFile(path), "club event ops migration missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String jobMigration() throws Exception {
        Path path = Paths.get("sql/migration_club_role_event_ops_job_20260825.sql");
        assertTrue(Files.isRegularFile(path), "club event ops post-deploy job migration missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    void futureEditUsesHalfOpenWindowAndProtectsRegistrationAndSettlementHistory() throws Exception {
        String xml = xml();
        assertTrue(xml.contains("a.start_date &gt; #{now}"));
        assertFalse(xml.contains("a.start_date &gt;= #{now}"));
        assertTrue(xml.contains("ifnull(a.is_settlement, 0) &lt;&gt; 0"));
        assertTrue(xml.contains("r.owner_type = 2 and r.owner_id = #{activityId}"));
        assertTrue(xml.contains("w.activity_id = #{activityId} and w.state in ('WAITING','OFFERED','CLAIMED')"));
        assertTrue(xml.contains("status = 'CANCELLED'"));
    }

    @Test
    void waitlistLocksFifoAndAllMutationsAreStateAndVersionScoped() throws Exception {
        String xml = xml();
        assertTrue(xml.contains("order by joined_at asc, id asc"));
        assertTrue(xml.contains("limit 1 for update"));
        assertTrue(xml.contains("state = 'WAITING' and version = #{expectedVersion}"));
        assertTrue(xml.contains("state = 'OFFERED' and version = #{expectedVersion}"));
        assertTrue(xml.contains("activity_id = #{activityId} and ticket_id = #{ticketId}"));
    }

    @Test
    void notificationAndRosterQueriesStayClubAndActivityScopedWithoutPhoneProjection() throws Exception {
        String xml = xml();
        assertTrue(xml.contains("a.club_id = #{clubId}"));
        assertTrue(xml.contains("w.club_id = #{clubId} and w.activity_id = #{activityId}"));
        assertTrue(xml.contains("uk_club_event_delivery_recipient") || xml.contains("insertDelivery"));
        assertFalse(xml.toLowerCase().contains("phone as"));
        assertFalse(xml.toLowerCase().contains("mobile as"));
    }

    @Test
    void cancellationReadbackSupportsOpenSessionActivityWithoutSeriesOccurrence() throws Exception {
        String xml = xml();
        int start = xml.indexOf("<select id=\"selectOccurrenceCancellationState\"");
        int end = xml.indexOf("</select>", start);
        assertTrue(start >= 0 && end > start, "cancellation state query missing");
        String query = xml.substring(start, end);

        assertTrue(query.contains("from cms_activity a"),
                "open-session activity is the standalone occurrence truth and must anchor readback");
        assertTrue(query.contains("left join club_event_occurrence o"),
                "series occurrence is optional for open-session activities");
        assertTrue(query.contains("a.id = #{activityId} and a.club_id = #{clubId}"),
                "standalone readback must remain activity-and-club scoped");
        assertTrue(query.contains("when a.cancel_time is not null then 'CANCELLED' else 'ACTIVE' end"),
                "without an occurrence row the real activity state must drive the readback status");
    }

    @Test
    void migrationOnlyAdvertisesMaterializableRecurrenceTypes() throws Exception {
        String sql = migration();
        assertTrue(sql.contains("recurrence_type IN ('ONCE','WEEKLY','CUSTOM_DATES')"));
        assertFalse(sql.contains("CUSTOM_RRULE"));
    }

    @Test
    void ineligibleNotificationRecipientHasDurableTerminalReceipt() throws Exception {
        String xml = xml();
        String sql = migration();
        assertTrue(xml.contains("isEligibleNotificationRecipient"));
        assertTrue(xml.contains("markDeliverySkippedIneligibleCas"));
        assertTrue(xml.contains("notificationRecipientNotBanned"));
        assertTrue(xml.contains("ban.status = 'ACTIVE'"));
        assertTrue(sql.contains("status VARCHAR(24) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(sql.contains("'SKIPPED_INELIGIBLE'"));
    }

    @Test
    void quartzRegistrationIsSplitAfterJarAndReadBackExactlyOnce() throws Exception {
        String schema = migration().toLowerCase();
        String job = jobMigration().toLowerCase().replaceAll("\\s+", " ");

        assertFalse(schema.contains("sys_job"),
                "pre-deploy schema 不得提前注册依赖新 jar 的 Quartz bean");
        assertTrue(job.contains("post-deploy"));
        assertTrue(job.contains("clubeventopstask.run()"));
        assertTrue(job.contains("'3', '1', '0'"));
        assertTrue(job.contains("select count(*) = 1")
                && job.contains("job_name = '俱乐部活动运营维护'")
                && job.contains("job_group = 'default'")
                && job.contains("cron_expression = '0 * * * * ?'")
                && job.contains("misfire_policy = '3'")
                && job.contains("concurrent = '1'")
                && job.contains("status = '0'"),
                "post-deploy job 必须 exact-one 回读全部调度配置");
    }
}

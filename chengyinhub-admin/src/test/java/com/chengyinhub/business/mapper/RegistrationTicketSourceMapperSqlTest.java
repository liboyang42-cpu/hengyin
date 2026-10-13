package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付冻结票源的跨栈 SQL 合同；真实写入行为由 RegistrationFundsMybatisIntegrationTest 覆盖。
 *
 * <p>⚠️ 这几条钉的是【纵深防御】判据，不是信任边界：冻结闸比的 r.source_club_id 若来自客户端自报，
 * 「等于执行俱乐部」这个条件本身就是被伪造的那个。信任边界在写入侧
 * （{@code CmsRegistrationServiceImpl.serverVerifiedTicketSourceClubId}），
 * 由 {@code TicketSourceAttributionTrustBoundaryTest} 承重。
 */
class RegistrationTicketSourceMapperSqlTest {

    @Test
    void platformTopicValidatesTicketSourceAgainstFrozenExecutingClub() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml")),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        int start = source.indexOf("<update id=\"freezeTicketSourceOnPayment\">");
        int end = source.indexOf("</update>", start);
        assertTrue(start >= 0 && end > start, "必须存在支付冻结票源 SQL");
        String freeze = source.substring(start, end);

        assertTrue(freeze.contains("left join club_edition_compensation_terms cet on cet.topic_id = t.id"),
                "平台建期的 topic/activity club_id 都可能为空，必须读取开售冻结的执行俱乐部真源");
        assertTrue(freeze.contains("(cet.executing_club_id is null or cet.executing_club_id = r.source_club_id)"),
                "冻结执行俱乐部与来源冲突时必须归平台，不能挑另一侧");
        assertTrue(freeze.contains("and (a.club_id = r.source_club_id or t.club_id = r.source_club_id "
                        + "or cet.executing_club_id = r.source_club_id)"),
                "topic/activity club_id 均为空时，合法 executingClubId 仍必须能保留来源");
    }
}

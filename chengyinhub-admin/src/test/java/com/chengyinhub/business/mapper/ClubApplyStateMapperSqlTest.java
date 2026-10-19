package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.Club;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V5a SC-CL-APPROVE:通过/重新提交清掉旧驳回原因;「我的申请」按最新在前。 */
class ClubApplyStateMapperSqlTest {
    private static final String XML = "mapper/business/ClubMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.ClubMapper.";
    private Configuration configuration;

    @BeforeEach
    void parse() throws Exception {
        configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Club", Club.class);
        try (InputStream input = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(input, configuration, XML, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void approvalClearsTheOldRejectReason() {
        String sql = updateSql(status(1L, null));
        assertTrue(sql.contains("reject_reason = null"), sql);
    }

    @Test
    void resubmitBackToPendingClearsTheOldRejectReason() {
        String sql = updateSql(status(0L, null));
        assertTrue(sql.contains("reject_reason = null"), sql);
    }

    @Test
    void rejectionStillWritesItsReasonAndAPlainProfileEditLeavesItAlone() {
        String reject = updateSql(status(2L, "简介不完整"));
        assertTrue(reject.contains("reject_reason = ?"), reject);
        assertFalse(reject.contains("reject_reason = null"), reject);
        String edit = updateSql(status(null, null));
        assertFalse(edit.contains("reject_reason"), edit);
    }

    @Test
    void myApplySourceListsNewestFirst() {
        String sql = configuration.getMappedStatement(NS + "selectClubsByMemberId").getBoundSql(42L)
                .getSql().replaceAll("\\s+", " ").trim().toLowerCase();
        assertTrue(sql.endsWith("order by create_time desc, id desc"), sql);
    }

    private Club status(Long status, String reason) {
        Club club = new Club();
        club.setId(12L);
        club.setName("x");
        club.setStatus(status);
        club.setRejectReason(reason);
        return club;
    }

    private String updateSql(Club club) {
        return configuration.getMappedStatement(NS + "updateClub").getBoundSql(club)
                .getSql().replaceAll("\\s+", " ").trim().toLowerCase();
    }
}

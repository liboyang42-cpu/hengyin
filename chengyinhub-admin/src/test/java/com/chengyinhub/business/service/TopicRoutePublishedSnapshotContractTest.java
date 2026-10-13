package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicRoutePublishedSnapshotContractTest {

    @Test
    void publishStatementOnlyAdvancesThePublishedSnapshotVersion() throws Exception {
        String xml = WiringSource.read("chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml")
                .replaceAll("\\s+", "");
        int start = xml.indexOf("<updateid=\"publishRouteSnapshot\">");
        int end = xml.indexOf("</update>", start);
        String statement = xml.substring(start, end);

        assertTrue(statement.contains("published_route_graph_json=#{publishedRouteGraphJson}"));
        assertTrue(statement.contains("published_config_version=published_config_version+1"));
        assertTrue(statement.contains("whereid=#{id}andis_draft=0anddel_flag=0"));
        assertFalse(statement.contains("config_version=config_version+1"),
                "发布只能递增 published_config_version，不能改草稿 CAS 版本");
    }

    @Test
    void migrationBackfillsOnlyPreviouslyPublishedBranchGraphs() throws Exception {
        String sql = WiringSource.read(
                "chengyinhub-admin/sql/migration_20260823_topic_route_published_snapshot.sql")
                .replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("add column published_route_graph_json json null"));
        assertTrue(sql.contains("add column published_config_version bigint not null default 0"));
        assertTrue(sql.contains("if(@published_route_graph_exists=0"));
        assertTrue(sql.contains("if(@published_route_version_exists=0"));
        assertTrue(sql.contains("where is_draft = 0"));
        assertTrue(sql.contains("and route_mode = 'branch_graph'"));
        assertFalse(sql.contains("where is_draft = 1\n"));
    }
}

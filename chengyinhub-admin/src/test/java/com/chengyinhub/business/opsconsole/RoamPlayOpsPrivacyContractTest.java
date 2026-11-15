package com.chengyinhub.business.opsconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import com.chengyinhub.common.exception.ServiceException;

/** BE-25 只读运营视图不得读出个人轨迹、坐标、揭雾格位置或局内状态正文。 */
class RoamPlayOpsPrivacyContractTest
{
    private static final List<String> FORBIDDEN = List.of("track_json", "lat", "lng", "cell_key", "cell_prefix5", "geohash7", "state_json",
            "config_snapshot_json", "location");

    @Test
    void everyStatementOnlyReadsAggregatesAndStates() throws Exception
    {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/opsconsole/RoamPlayOpsMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        int checked = 0;
        for (String id : List.of("sessions", "sessionTotals", "fogByMember", "fogTotals", "teams", "playRuns", "runtimeSessions"))
        {
            MappedStatement statement = configuration.getMappedStatement("com.chengyinhub.business.mapper.opsconsole.RoamPlayOpsMapper." + id);
            Map<String, Object> params = new HashMap<>();
            params.put("memberId", 1L);
            params.put("status", 1);
            params.put("since", "2026-11-01");
            params.put("until", "2026-11-15");
            params.put("ownerType", 1);
            params.put("ownerId", 2L);
            params.put("topicId", 3L);
            params.put("runState", "PAUSED");
            params.put("limit", 200);
            String sql = statement.getBoundSql(params).getSql().toLowerCase().replaceAll("\\s+", " ");
            for (String column : FORBIDDEN)
                assertFalse(sql.matches(".*\\b" + column + "\\b.*"), id + " 读了禁止列 " + column + "：" + sql);
            assertFalse(sql.contains("select *") || sql.contains(".*"), id + " 不得 select *");
            checked++;
        }
        assertEquals(7, checked);
    }

    @Test
    void dayFilterAcceptsOnlyIsoDates()
    {
        assertNull(RoamPlayOpsService.day(" "));
        assertEquals("2026-11-15", RoamPlayOpsService.day("2026-11-15"));
        assertThrows(ServiceException.class, () -> RoamPlayOpsService.day("2026-11-15' or 1=1"));
    }
}

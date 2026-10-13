package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoamTileMemoryPageTest
{
    @Test
    void tilePageUsesCurrentPlayerAndReturnsAdvancingCursor()
    {
        ApiRoamController controller = spy(new ApiRoamController());
        RoamMapper roamMapper = mock(RoamMapper.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        doReturn(77L).when(controller).getAppUserId();
        when(roamMapper.selectTilePageByMember(77L, 0L, 3)).thenReturn(Arrays.asList(
                tile(11L, "wtw3sjq"), tile(12L, "wtw3sjr"), tile(13L, "wtw3sjs")));

        AjaxResult result = controller.tilePage(0L, 2);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(Arrays.asList("wtw3sjq", "wtw3sjr"), data.get("tiles"));
        assertEquals(12L, data.get("nextAfterId"));
        assertEquals(Boolean.TRUE, data.get("hasMore"));
        verify(roamMapper).selectTilePageByMember(77L, 0L, 3);
    }

    @Test
    void finalTilePageKeepsLastCursorAndStops()
    {
        ApiRoamController controller = spy(new ApiRoamController());
        RoamMapper roamMapper = mock(RoamMapper.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        doReturn(88L).when(controller).getAppUserId();
        when(roamMapper.selectTilePageByMember(88L, 20L, 3)).thenReturn(Arrays.asList(
                tile(21L, "wtw3sjt"), tile(22L, "wtw3sju")));

        AjaxResult result = controller.tilePage(20L, 2);

        assertTrue(result.get(AjaxResult.DATA_TAG) instanceof Map);
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(22L, data.get("nextAfterId"));
        assertEquals(Boolean.FALSE, data.get("hasMore"));
    }

    @Test
    void tilePageSqlIsScopedToPlayerAndAdvancingCursor() throws Exception
    {
        String xml = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml")), StandardCharsets.UTF_8);
        int start = xml.indexOf("<select id=\"selectTilePageByMember\"");
        int end = xml.indexOf("</select>", start);
        String select = xml.substring(start, end).replaceAll("\\s+", " ");

        assertTrue(select.contains("where member_id = #{memberId} and id &gt; #{afterId}"));
        assertTrue(select.contains("order by id asc"));
        assertTrue(select.contains("limit #{limit}"));
    }

    private static Map<String, Object> tile(Long id, String geohash7)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("geohash7", geohash7);
        return row;
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商家待办核销计数(P1-020):一条分组 SQL 查完全部项目,口径同 selectCmsRegistrationList(...).size()。 */
class CmsRegistrationVerificationCountTest {

    private static Map<String, Object> row(long ownerId, Object status, long cnt) {
        Map<String, Object> m = new HashMap<>();
        m.put("owner_id", ownerId);
        m.put("verification_status", status);
        m.put("cnt", cnt);
        return m;
    }

    @Test
    void groupsRowsIntoPendingAndVerifiedPerOwner() {
        CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mapper);
        when(mapper.countPaidVerificationByOwners(1L, Arrays.asList(7L, 8L))).thenReturn(Arrays.asList(
                row(7L, 0, 3L), row(7L, 1, 5L), row(8L, Boolean.TRUE, 2L)));

        Map<Long, long[]> out = service.countPaidVerificationByOwners(1L, Arrays.asList(7L, 8L));

        assertArrayEquals(new long[] {3L, 5L}, out.get(7L));
        assertArrayEquals(new long[] {0L, 2L}, out.get(8L));
    }

    @Test
    void emptyOwnerListDoesNotHitDatabase() {
        CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mapper);

        assertEquals(0, service.countPaidVerificationByOwners(1L, Collections.<Long>emptyList()).size());
        verify(mapper, never()).countPaidVerificationByOwners(anyLong(), any());
    }

    @Test
    void sqlIsOneGroupedCountWithTheListQueryFilters() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system",
                "src/main/resources/mapper/business/CmsRegistrationMapper.xml")), StandardCharsets.UTF_8);
        int begin = xml.indexOf("id=\"countPaidVerificationByOwners\"");
        assertTrue(begin >= 0);
        String stmt = xml.substring(begin, xml.indexOf("</select>", begin)).replaceAll("\\s+", " ");
        assertTrue(stmt.contains("count(*)"));
        assertTrue(stmt.contains("payment_status = 2"));
        assertTrue(stmt.contains("owner_type = #{ownerType}"));
        assertTrue(stmt.contains("owner_id in"));
        assertTrue(stmt.contains("verification_status in (0, 1)"));
        assertTrue(stmt.contains("group by owner_id, verification_status"));
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.web.runner.MemberLegacyPasswordHashRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemberLegacyPasswordHashServiceTest {

    private final UmsMemberMapper mapper = mock(UmsMemberMapper.class);
    private final MemberLegacyPasswordHashService service = new MemberLegacyPasswordHashService();

    MemberLegacyPasswordHashServiceTest() {
        ReflectionTestUtils.setField(service, "memberMapper", mapper);
    }

    private static UmsMember row(long id, String password) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setPassword(password);
        return member;
    }

    @Test
    void hashesEachPlaintextWithCasAndKeepsTheSameSecret() {
        when(mapper.selectLegacyPlaintextPasswords(0L, 100))
                .thenReturn(Arrays.asList(row(3L, "fixture-plain-a"), row(9L, "fixture-plain-b")));
        when(mapper.selectLegacyPlaintextPasswords(9L, 98)).thenReturn(Collections.emptyList());
        when(mapper.hashLegacyPasswordIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(1);

        MemberLegacyPasswordHashService.Result result = service.hashLegacyPasswords(100);

        assertEquals(2, result.getScanned());
        assertEquals(2, result.getHashed());
        assertEquals(0, result.getSkipped());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).hashLegacyPasswordIfUnchanged(eq(3L), eq("fixture-plain-a"), hash.capture());
        assertTrue(hash.getValue().startsWith("$2"));
        assertTrue(SecurityUtils.matchesPassword("fixture-plain-a", hash.getValue()),
                "the stored secret must stay the same so existing users can still log in");
        assertFalse(hash.getValue().contains("fixture-plain-a"));
    }

    @Test
    void concurrentChangeLosesCasAndIsCountedAsSkippedNeverOverwritten() {
        when(mapper.selectLegacyPlaintextPasswords(0L, 10)).thenReturn(Collections.singletonList(row(5L, "old-plain")));
        when(mapper.selectLegacyPlaintextPasswords(5L, 9)).thenReturn(Collections.emptyList());
        when(mapper.hashLegacyPasswordIfUnchanged(eq(5L), eq("old-plain"), anyString())).thenReturn(0);

        MemberLegacyPasswordHashService.Result result = service.hashLegacyPasswords(10);

        assertEquals(0, result.getHashed());
        assertEquals(1, result.getSkipped());
        verify(mapper, times(1)).hashLegacyPasswordIfUnchanged(anyLong(), anyString(), anyString());
    }

    @Test
    void alreadyHashedBlankAndOverlongValuesAreNotRewritten() {
        String overlong = String.join("", Collections.nCopies(80, "x"));
        List<UmsMember> page = Arrays.asList(row(1L, "$2a$10$alreadyhashedvalue"), row(2L, ""), row(4L, overlong));
        when(mapper.selectLegacyPlaintextPasswords(0L, 100)).thenReturn(page);
        when(mapper.selectLegacyPlaintextPasswords(4L, 97)).thenReturn(Collections.emptyList());

        MemberLegacyPasswordHashService.Result result = service.hashLegacyPasswords(100);

        assertEquals(3, result.getSkipped());
        verify(mapper, never()).hashLegacyPasswordIfUnchanged(anyLong(), anyString(), anyString());
    }

    @Test
    void limitBoundsTheScanAcrossPages() {
        when(mapper.selectLegacyPlaintextPasswords(0L, 1)).thenReturn(Collections.singletonList(row(7L, "p")));
        when(mapper.hashLegacyPasswordIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(1);

        assertEquals(1, service.hashLegacyPasswords(1).getScanned());
        verify(mapper, times(1)).selectLegacyPlaintextPasswords(anyLong(), anyInt());
    }

    @Test
    void runnerIsOptInAndNeverBreaksStartup() {
        MemberLegacyPasswordHashService hashService = mock(MemberLegacyPasswordHashService.class);
        MemberLegacyPasswordHashRunner runner = new MemberLegacyPasswordHashRunner();
        ReflectionTestUtils.setField(runner, "hashService", hashService);

        ReflectionTestUtils.setField(runner, "hashLimit", 0);
        runner.run(null);
        verifyNoInteractions(hashService);

        ReflectionTestUtils.setField(runner, "hashLimit", 500);
        ReflectionTestUtils.setField(runner, "jobsEnabled", false);
        runner.run(null);
        verifyNoInteractions(hashService);

        ReflectionTestUtils.setField(runner, "jobsEnabled", true);
        when(hashService.hashLegacyPasswords(500)).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> runner.run(null));
        verify(hashService).hashLegacyPasswords(500);
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayTeamShortInviteTest {
    private PlayTeamMapper mapper;
    private PlayTeamServiceImpl service;
    private PlayTeam team;
    @BeforeEach void setup() {
        mapper = mock(PlayTeamMapper.class);
        service = spy(new PlayTeamServiceImpl());
        ReflectionTestUtils.setField(service, "teamMapper", mapper);
        ReflectionTestUtils.setField(service, "imService", mock(IImService.class));
        team = new PlayTeam(); team.setId(1L); team.setLeaderMemberId(7L);
        team.setStatus(0); team.setInviteCode("OLDXYZ"); team.setExpireTime(new Date(123456789L));
        when(mapper.selectPlayTeamByIdForUpdate(1L)).thenReturn(team);
    }
    @Test void generationUsesSixUnambiguousCharactersAndQrSceneAcceptsIt() {
        for (int i = 0; i < 200; i++) {
            String code = service.newInviteCode();
            assertTrue(code.matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}"));
            assertEquals("tm." + code, PlayTeamServiceImpl.teamScene(code));
        }
    }
    @Test void rotationRetriesAnAtomicUniqueKeyCollisionWithoutExtendingExpiry() {
        doReturn("AB2XYZ", "CD3XYZ").when(service).newInviteCode();
        when(mapper.rotateInviteCode(1L, 7L, "AB2XYZ")).thenThrow(new DuplicateKeyException("collision"));
        when(mapper.rotateInviteCode(1L, 7L, "CD3XYZ")).thenReturn(1);
        Date original = team.getExpireTime();
        assertEquals("CD3XYZ", service.rotateInviteCode(1L, 7L));
        assertEquals(original, team.getExpireTime());
        verify(mapper).rotateInviteCode(1L, 7L, "AB2XYZ");
        verify(mapper).rotateInviteCode(1L, 7L, "CD3XYZ");
    }
    @Test void rotationRefusesNonOwnerAndFinishedTeamsBeforeAnyWrite() {
        assertThrows(IllegalStateException.class, () -> service.rotateInviteCode(1L, 8L));
        team.setStatus(3);
        assertThrows(IllegalStateException.class, () -> service.rotateInviteCode(1L, 7L));
        verify(mapper, never()).rotateInviteCode(anyLong(), anyLong(), anyString());
    }
    @Test void collisionRetriesAreBoundedAndNeverReportAnUnwrittenCode() {
        doReturn("AB2XYZ").when(service).newInviteCode();
        when(mapper.rotateInviteCode(anyLong(), anyLong(), anyString())).thenThrow(new DuplicateKeyException("collision"));
        assertThrows(DuplicateKeyException.class, () -> service.rotateInviteCode(1L, 7L));
        verify(mapper, times(16)).rotateInviteCode(1L, 7L, "AB2XYZ");
    }
    @Test void creationAlsoRetriesUniqueIndexCollisionsBeforeAddingLeaderMembership() {
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity(); activity.setTopicId(20L); activity.setStartDate(new Date());
        when(activities.selectCmsActivityById(9L)).thenReturn(activity);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic(); topic.setTeamMode(1); topic.setTeamMaxMembers(4);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        when(mapper.hasPaidRegistration(7L, 2, 9L)).thenReturn(1);
        doReturn("AB2XYZ", "CD3XYZ").when(service).newInviteCode();
        when(mapper.insertPlayTeam(any())).thenThrow(new DuplicateKeyException("collision")).thenAnswer(call -> {
            ((PlayTeam) call.getArgument(0)).setId(1L); return 1;
        });
        assertEquals("CD3XYZ", service.create(7L, 2, 9L, null, 4, 1).getInviteCode());
        verify(mapper, times(2)).insertPlayTeam(any());
        verify(mapper).insertPlayTeamMember(any());
    }
}

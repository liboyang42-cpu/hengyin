package com.chengyinhub.business.service.impl;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.TeamEventCommand;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OfficialEventAttendanceServiceTest {
 OfficialEventMapper events; OfficialEventV2Mapper participants; OfficialEventAttendanceMapper records;
 OfficialEventV2ServiceImpl authority; ISysConfigService settings; OfficialEventAttendanceService service;OfficialEvent event;OfficialEventParticipation person;
 @BeforeEach void setup(){
  events=mock(OfficialEventMapper.class);participants=mock(OfficialEventV2Mapper.class);records=mock(OfficialEventAttendanceMapper.class);authority=mock(OfficialEventV2ServiceImpl.class);settings=mock(ISysConfigService.class);
  service=new OfficialEventAttendanceService(events,participants,records,authority,settings);
  // MyBatis yields null for absent attendance rows; Mockito's default would be an empty map.
  when(records.config(any())).thenReturn(null);when(records.mine(any(),any())).thenReturn(null);when(records.credential(any(),any())).thenReturn(null);
  event=new OfficialEvent();event.setId(19L);event.setContractVersion(2);event.setStatus(2);event.setSignupStart(new Date(1));event.setActivityEnd(new Date(System.currentTimeMillis()+100000));
  when(events.selectOfficialEventByIdForUpdate(19L)).thenReturn(event);when(events.selectOfficialEventById(19L)).thenReturn(event);
  person=new OfficialEventParticipation();person.setId(42L);person.setMemberId(7L);person.setState("ACTIVE");when(participants.selectParticipation(19L,7L)).thenReturn(person);
  when(settings.selectConfigByKey("official_publish_whitelist")).thenReturn("8");when(authority.canOperateAttendance(event,8L)).thenReturn(true);
 }
 TeamEventCommand command(String action){TeamEventCommand c=new TeamEventCommand();c.setAction(action);c.setTargetId(19L);c.setCredentialCode("a".repeat(64));return c;}
 Map<String,Object> row(boolean checked){Map<String,Object> r=new HashMap<>();r.put("participation_id",42L);r.put("member_id",7L);r.put("version",1L);r.put("credential","a".repeat(64));if(checked)r.put("checked_at",new Date(12));return r;}
 @Test void wrongOrOtherEventCodeDoesNotWrite(){assertThrows(ServiceException.class,()->service.execute(8L,command("attendance-verify")));verify(records,never()).checkin(any(),any(),any());}
 @Test void coOrganizerCannotVerifyEvenIfWhitelisted(){when(authority.canOperateAttendance(event,8L)).thenReturn(false);assertThrows(ServiceException.class,()->service.execute(8L,command("attendance-verify")));verifyNoInteractions(records);}
 @Test void invalidParticipationAndPausedWindowCannotCreateAttendance(){
  person.setState("LEFT");assertThrows(ServiceException.class,()->service.execute(7L,command("attendance-claim")));verify(records,never()).issue(any(),any(),any(),any());
  person.setState("ACTIVE");event.setControlState("PAUSED");assertThrows(ServiceException.class,()->service.execute(7L,command("attendance-claim")));verify(records,never()).issue(any(),any(),any(),any());
 }
 @Test void repeatVerificationReadsOriginalEvenAfterEnd(){when(records.credential(19L,"a".repeat(64))).thenReturn(row(true));event.setStatus(5);Map<String,Object> result=service.execute(8L,command("attendance-verify"));assertEquals("CHECKED_IN",result.get("state"));verify(records,never()).checkin(any(),any(),any());}
 @Test void pausedClaimReturnsExistingCodeWithoutRotation(){event.setControlState("PAUSED");when(records.mine(19L,7L)).thenReturn(row(false));assertEquals("42",service.execute(7L,command("attendance-claim")).get("bib"));verify(records,never()).rotate(any(),any());}
 @Test void historicalReadKeepsResultButHidesInvalidHolderCode(){person.setState("BANNED");when(records.mine(19L,7L)).thenReturn(row(true));Map<?,?> mine=(Map<?,?>)service.read(19L,7L,false).get("mine");assertEquals("CHECKED_IN",mine.get("state"));assertFalse(mine.containsKey("credential"));}
 @Test void publicReadDoesNotRequireParticipationOrReadMemberPhone(){Map<String,Object> out=service.read(19L,0L,false);assertEquals("UNCONFIGURED",out.get("dutyState"));verify(participants,never()).selectParticipation(any(),any());}
 @Test void configRequiresPublicConsentBeforeAnyWrite(){TeamEventCommand c=command("attendance-config");c.setConfigVersion(0L);c.setAttendanceConfig("{\"publicConfirmed\":false}");assertThrows(ServiceException.class,()->service.execute(8L,c));verify(records,never()).saveConfig(any(),any(),any(),anyLong());}
 @Test void issuedCodeReadProducesPrivateInlineQrOnlyForHolder(){when(records.mine(19L,7L)).thenReturn(row(false));Map<?,?> mine=(Map<?,?>)service.read(19L,7L,false).get("mine");assertTrue(String.valueOf(mine.get("qrImage")).startsWith("data:image/png;base64,"));assertEquals("a".repeat(64),mine.get("credential"));}
}

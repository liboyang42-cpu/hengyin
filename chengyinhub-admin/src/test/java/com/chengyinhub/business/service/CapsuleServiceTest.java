package com.chengyinhub.business.service;
import com.chengyinhub.business.domain.CapsuleRecord;
import com.chengyinhub.business.mapper.CapsuleMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CapsuleServiceTest {
    private CapsuleMapper mapper;
    private long current;
    private int verified, signed;
    private CapsuleService service;
    private final String request="capsule-request-0001";
    @BeforeEach void setup() {
        mapper=mock(CapsuleMapper.class); current=Instant.parse("2026-10-12T00:00:00Z").toEpochMilli(); verified=0; signed=0;
        service=new CapsuleService(mapper) {
            @Override protected long now(){return current;}
            @Override protected void verifyPhoto(String url,Long owner){verified++; if(!owner.equals(7L))throw new ServiceException("owner");}
            @Override protected String signPhoto(String key){signed++;return "signed:"+key;}
        };
    }
    private CapsuleRecord row() {
        CapsuleRecord row=new CapsuleRecord();row.setId(11L);row.setMemberId(7L);row.setDeleted(0);
        row.setCreatedAtMs(current);row.setUnlockAtMs(CapsuleService.unlockMillis("2027-01-01"));
        row.setBody("secret");row.setPhotoKey("photos/private.jpg");return row;
    }
    @Test void shanghaiMidnightIsStoredAsUtcEpoch(){assertEquals(Instant.parse("2026-12-31T16:00:00Z").toEpochMilli(),CapsuleService.unlockMillis("2027-01-01"));}
    @Test void invalidCalendarDatesAreRejected(){assertThrows(ServiceException.class,()->CapsuleService.unlockMillis("2027-02-29"));assertThrows(ServiceException.class,()->CapsuleService.unlockMillis("2027-1-1"));}
    @Test void loggedOutNeverReadsOrWrites(){assertThrows(ServiceException.class,()->service.create(0L,request,"a",null,"2027-01-01"));verifyNoInteractions(mapper);}
    @Test void lockedDetailDoesNotReturnOrSignAnyContent(){when(mapper.byId(7L,11L)).thenReturn(row());Map<String,Object> result=service.detail(7L,11L);assertEquals("LOCKED",result.get("state"));assertFalse(result.containsKey("body"));assertFalse(result.containsKey("photoUrl"));assertEquals(0,signed);}
    @Test void exactlyAtBoundaryReturnsShortLivedPhoto(){CapsuleRecord row=row();when(mapper.byId(7L,11L)).thenReturn(row);current=row.getUnlockAtMs();Map<String,Object> result=service.detail(7L,11L);assertEquals("secret",result.get("body"));assertEquals("signed:photos/private.jpg",result.get("photoUrl"));assertEquals(1,signed);}
    @Test void anotherOwnerCannotLookup(){when(mapper.byId(8L,11L)).thenReturn(null);assertThrows(ServiceException.class,()->service.detail(8L,11L));verify(mapper).byId(8L,11L);assertEquals(0,signed);}
    @Test void createOnlyReturnsMetadataAndStoresVerifiedObjectKey(){when(mapper.insert(any())).thenAnswer(i->{((CapsuleRecord)i.getArgument(0)).setId(11L);return 1;});Map<String,Object> result=service.create(7L,request,"private","https://storage/photos/private.jpg?signature=x","2027-01-01");ArgumentCaptor<CapsuleRecord> cap=ArgumentCaptor.forClass(CapsuleRecord.class);verify(mapper).insert(cap.capture());assertEquals("photos/private.jpg",cap.getValue().getPhotoKey());assertEquals(1,verified);assertFalse(result.containsKey("body"));assertFalse(result.containsKey("photoUrl"));assertEquals(64,cap.getValue().getPayloadHash().length());}
    @Test void exactReplayAfterUnlockDoesNotCreateOrReverify(){when(mapper.insert(any())).thenAnswer(i->{((CapsuleRecord)i.getArgument(0)).setId(11L);return 1;});service.create(7L,request,"private",null,"2027-01-01");ArgumentCaptor<CapsuleRecord> cap=ArgumentCaptor.forClass(CapsuleRecord.class);verify(mapper).insert(cap.capture());when(mapper.byRequest(7L,request)).thenReturn(cap.getValue());current=CapsuleService.unlockMillis("2027-01-02");Map<String,Object> result=service.create(7L,request,"private",null,"2027-01-01");assertEquals("OPEN",result.get("state"));assertFalse(result.containsKey("body"));verify(mapper,times(1)).insert(any());}
    @Test void differentContentUnderSameKeyIsRejected(){CapsuleRecord row=row();row.setPayloadHash("different");when(mapper.byRequest(7L,request)).thenReturn(row);assertThrows(ServiceException.class,()->service.create(7L,request,"changed",null,"2027-01-01"));verify(mapper,never()).insert(any());}
    @Test void duplicateRaceReadsCommittedWinner(){final CapsuleRecord[] winner={null};when(mapper.byRequest(7L,request)).thenAnswer(i->winner[0]);when(mapper.insert(any())).thenAnswer(i->{winner[0]=i.getArgument(0);winner[0].setId(22L);throw new DuplicateKeyException("collision");});assertEquals("22",service.create(7L,request,"private",null,"2027-01-01").get("id"));verify(mapper,times(2)).byRequest(7L,request);}
    @Test void unrelatedUniqueFailureIsNotReportedAsSuccess(){when(mapper.insert(any())).thenThrow(new DuplicateKeyException("other"));assertThrows(DuplicateKeyException.class,()->service.create(7L,request,"private",null,"2027-01-01"));}
    @Test void requestRecoveryNeverReturnsBodyEvenWhenOpen(){CapsuleRecord row=row();current=row.getUnlockAtMs();when(mapper.byRequest(7L,request)).thenReturn(row);assertFalse(service.receipt(7L,request).containsKey("body"));}
    @Test void deletedReceiptIsTombstone(){CapsuleRecord row=row();row.setDeleted(1);when(mapper.byRequest(7L,request)).thenReturn(row);assertEquals("DELETED",service.receipt(7L,request).get("state"));}
    @Test void pageUsesLookaheadAndStringCursorWithoutContents(){CapsuleRecord a=row(),b=row();b.setId(10L);when(mapper.page(7L,null,2)).thenReturn(Arrays.asList(a,b));Map<String,Object> result=service.list(7L,null,1);assertEquals(true,result.get("hasMore"));assertEquals("11",result.get("nextBeforeId"));assertFalse(((Map<?,?>)((List<?>)result.get("items")).get(0)).containsKey("body"));}
    @Test void invalidLimitsDoNotQuery(){assertThrows(ServiceException.class,()->service.list(7L,null,51));verifyNoInteractions(mapper);}
    @Test void deletionIsOwnerScopedAndIdempotent(){when(mapper.delete(7L,11L)).thenReturn(1,0);assertEquals("DELETED",service.delete(7L,11L).get("state"));assertEquals("DELETED",service.delete(7L,11L).get("state"));verify(mapper,times(2)).delete(7L,11L);}
    @Test void pastAndTooFarDatesDoNotInsert(){assertThrows(ServiceException.class,()->service.create(7L,request,"a",null,"2026-10-01"));assertThrows(ServiceException.class,()->service.create(7L,request,"a",null,"2040-10-01"));verify(mapper,never()).insert(any());}
}

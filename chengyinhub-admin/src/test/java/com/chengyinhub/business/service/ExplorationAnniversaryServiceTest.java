package com.chengyinhub.business.service;
import com.chengyinhub.business.domain.ExplorationAnniversary;
import com.chengyinhub.business.mapper.ExplorationAnniversaryMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ExplorationAnniversaryServiceTest {
    ExplorationAnniversaryMapper mapper; ExplorationAnniversaryService service;
    long clock=Instant.parse("2026-10-12T12:00:00Z").toEpochMilli();
    @BeforeEach void setup(){mapper=mock(ExplorationAnniversaryMapper.class);service=new ExplorationAnniversaryService(mapper){@Override protected long now(){return clock;}};}
    ExplorationAnniversary row(String id,String at){ExplorationAnniversary r=new ExplorationAnniversary();r.setId(id);r.setTopicId("9007199254740999");r.setTitle("当前可见名称");r.setCompletedAt(at);return r;}
    String cursor(){when(mapper.page(7L,"10-12","2026-01-01 00:00:00",null,null,2)).thenReturn(Arrays.asList(row("90","2025-10-12 12:00:00.001"),row("89","2024-10-12 12:00:00.000")));return (String)service.read(7L,"10","12",null,"1").get("nextCursor");}
    @Test void requiresAuthenticatedOwnerBeforeAnyQuery(){assertThrows(ServiceException.class,()->service.read(null,null,null,null,null));verifyNoInteractions(mapper);}
    @Test void defaultIsShanghaiTodayNotUtc(){clock=Instant.parse("2026-10-12T16:00:00Z").toEpochMilli();Map<String,Object> result=service.read(7L,null,null,null,null);assertEquals(10,result.get("month"));assertEquals(13,result.get("day"));verify(mapper).page(7L,"10-13","2026-01-01 00:00:00",null,null,21);}
    @Test void emptyAuthoritativeFactsStayEmpty(){Map<String,Object> result=service.read(7L,"10","12",null,null);assertEquals(Collections.emptyList(),result.get("items"));assertEquals(false,result.get("hasMore"));assertNull(result.get("nextCursor"));}
    @Test void leapDayIsAllowedAsPreviewInNonLeapYearWithoutShifting(){Map<String,Object> result=service.read(7L,"2","29",null,null);assertEquals(29,result.get("day"));assertEquals("EXACT_MONTH_DAY_ONLY",result.get("leapDayRule"));verify(mapper).page(7L,"02-29","2026-01-01 00:00:00",null,null,21);}
    @Test void invalidCalendarAndIncompleteSelectorNeverQuery(){for(String[] md:Arrays.asList(new String[]{"4","31"},new String[]{"2","30"},new String[]{"13","1"},new String[]{"1",null},new String[]{null,"1"}))assertThrows(ServiceException.class,()->service.read(7L,md[0],md[1],null,null));verifyNoInteractions(mapper);}
    @Test void nonCanonicalNumericInputsRejectFloatsExponentWhitespaceOverflowAndBoolean(){for(String raw:Arrays.asList("1.0","1.9","1e1","+1","01"," 1","true","2147483648","0","")){assertThrows(ServiceException.class,()->service.read(7L,raw,"1",null,null));assertThrows(ServiceException.class,()->service.read(7L,"1","1",null,raw));}verifyNoInteractions(mapper);}
    @Test void boundedLimitAndYearCutoffAreServerDerived(){assertThrows(ServiceException.class,()->service.read(7L,"1","1",null,"51"));service.read(7L,"12","31",null,"50");verify(mapper).page(7L,"12-31","2026-01-01 00:00:00",null,null,51);}
    @Test void completionMetadataUsesStringIdsAndElapsedCalendarYears(){when(mapper.page(7L,"10-12","2026-01-01 00:00:00",null,null,21)).thenReturn(Collections.singletonList(row("9007199254740998","2024-10-12 01:00:00.123")));Map<?,?> item=(Map<?,?>)((List<?>)service.read(7L,"10","12",null,null).get("items")).get(0);assertEquals("9007199254740998",item.get("id"));assertEquals(2,item.get("yearsAgo"));assertFalse(item.containsKey("configSnapshot"));assertFalse(item.containsKey("imageUrl"));}
    @Test void paginationUsesTimestampAndIdFromLastReturnedNotLookahead(){String cursor=cursor();service.read(7L,"10","12",cursor,"1");verify(mapper).page(7L,"10-12","2026-01-01 00:00:00","2025-10-12 12:00:00.001",90L,2);}
    @Test void cursorCannotCrossOwnerDateOrReferenceYear(){String c=cursor();clearInvocations(mapper);assertThrows(ServiceException.class,()->service.read(8L,"10","12",c,"1"));assertThrows(ServiceException.class,()->service.read(7L,"10","13",c,"1"));clock=Instant.parse("2027-01-01T00:00:00Z").toEpochMilli();assertThrows(ServiceException.class,()->service.read(7L,"10","12",c,"1"));verifyNoInteractions(mapper);}
    @Test void malformedAndOversizedCursorFailClosed(){for(String c:Arrays.asList("",String.join("",Collections.nCopies(257,"a")),"!!!!","a",Base64.getUrlEncoder().withoutPadding().encodeToString("1|7|10-12|2026|2026-10-12 00:00:00.000|1".getBytes(StandardCharsets.UTF_8))))assertThrows(ServiceException.class,()->service.read(7L,"10","12",c,null));verifyNoInteractions(mapper);}
    @Test void cursorRequiresRealDateExactMonthDayAndPositiveBoundedId(){for(String payload:Arrays.asList("1|7|10-12|2026|2025-02-30 00:00:00.000|1","1|7|10-12|2026|2025-10-13 00:00:00.000|1","1|7|10-12|2026|2025-10-12 00:00:00.000|9223372036854775808","1|7|10-12|2026|2025-10-12 00:00:00.000|0")){String c=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));assertThrows(ServiceException.class,()->service.read(7L,"10","12",c,null));}verifyNoInteractions(mapper);}
}

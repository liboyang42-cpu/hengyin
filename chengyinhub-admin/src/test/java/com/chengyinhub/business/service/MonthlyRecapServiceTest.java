package com.chengyinhub.business.service;
import com.chengyinhub.business.mapper.MonthlyRecapMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class MonthlyRecapServiceTest {
    MonthlyRecapMapper mapper; MonthlyRecapService service;
    @BeforeEach void setup(){mapper=mock(MonthlyRecapMapper.class);service=new MonthlyRecapService(mapper){@Override protected long now(){return Instant.parse("2026-10-12T00:00:00Z").toEpochMilli();}};}
    @Test void requiresCurrentOwnerBeforeReading(){assertThrows(ServiceException.class,()->service.read(null,"2026-10","routes",null,20));verifyNoInteractions(mapper);}
    @Test void invalidOrFutureMonthNeverQueries(){assertThrows(ServiceException.class,()->service.read(7L,"2026-13","routes",null,20));assertThrows(ServiceException.class,()->service.read(7L,"2026-11","routes",null,20));verifyNoInteractions(mapper);}
    @Test void emptyFactsRemainEmptyWithExactZeroCounts(){when(mapper.page(7L,"routes","2026-10-01 00:00:00","2026-11-01 00:00:00",null,21)).thenReturn(Collections.emptyList());Map<String,Object> result=service.read(7L,"2026-10","routes",null,20);assertEquals(Collections.emptyList(),result.get("items"));assertEquals(0L,((Map<?,?>)result.get("counts")).get("routes"));assertEquals(false,result.get("hasMore"));assertNull(result.get("nextBeforeId"));}
    @Test void leapMonthBoundsAreCalendarBasedAndHalfOpen(){when(mapper.page(7L,"places","2024-02-01 00:00:00","2024-03-01 00:00:00",null,21)).thenReturn(Collections.emptyList());service.read(7L,"2024-02","places",null,20);verify(mapper).count(7L,"places","2024-02-01 00:00:00","2024-03-01 00:00:00");}
    @Test void paginationUsesOriginalOwnerKindAndLargeStringId(){Map<String,Object> a=new HashMap<>(),b=new HashMap<>();a.put("id","9007199254740999");b.put("id","8");when(mapper.page(7L,"stamps","2026-10-01 00:00:00","2026-11-01 00:00:00",99L,2)).thenReturn(Arrays.asList(a,b));Map<String,Object> result=service.read(7L,"2026-10","stamps",99L,1);assertEquals(true,result.get("hasMore"));assertEquals("9007199254740999",result.get("nextBeforeId"));}
    @Test void invalidKindAndPageDoNotReachMapper(){assertThrows(ServiceException.class,()->service.read(7L,"2026-10","secret",null,20));assertThrows(ServiceException.class,()->service.read(7L,"2026-10","routes",0L,20));assertThrows(ServiceException.class,()->service.read(7L,"2026-10","routes",null,51));verifyNoInteractions(mapper);}
    @Test void countsAreSeparateSourceFactsNotInferredFromPage(){when(mapper.count(7L,"routes","2026-10-01 00:00:00","2026-11-01 00:00:00")).thenReturn(27L);when(mapper.page(anyLong(),anyString(),anyString(),anyString(),isNull(),anyInt())).thenReturn(Collections.emptyList());Map<?,?> counts=(Map<?,?>)service.read(7L,"2026-10","routes",null,20).get("counts");assertEquals(27L,counts.get("routes"));assertEquals(0L,counts.get("places"));}
}

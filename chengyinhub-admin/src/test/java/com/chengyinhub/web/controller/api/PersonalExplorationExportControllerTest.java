package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.PersonalExplorationExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalExplorationExportControllerTest {
    @Test void tokenOwnerIsAuthoritativeForBothEndpoints(){PersonalExplorationExportService service=mock(PersonalExplorationExportService.class);PersonalExplorationExportController c=spy(new PersonalExplorationExportController(service));doReturn(41L).when(c).getAppUserId();PersonalExplorationExportController.Prepare p=new PersonalExplorationExportController.Prepare();p.startDate="2026-10-01";p.endDateExclusive="2026-10-13";p.pageSize=20;when(service.prepare(41L,p.startDate,p.endDateExclusive,20)).thenReturn(Collections.emptyMap());assertEquals(200,c.prepare(p).get("code"));PersonalExplorationExportController.Page page=new PersonalExplorationExportController.Page();page.cursor="original_cursor";c.page(page);verify(service).page(41L,"original_cursor");}
    @Test void strictIntegerRejectsFloatExponentBooleanOverflowAndLeadingZero(){ObjectMapper mapper=new ObjectMapper();for(String value:new String[]{"1.9","1e1","true","2147483648","\"01\"","\"1.0\"","[]"})assertThrows(IOException.class,()->mapper.readValue("{\"pageSize\":"+value+"}",PersonalExplorationExportController.Prepare.class));}
    @Test void canonicalIntegerAndStringAreAccepted()throws Exception{ObjectMapper mapper=new ObjectMapper();assertEquals(Integer.valueOf(10),mapper.readValue("{\"pageSize\":10}",PersonalExplorationExportController.Prepare.class).pageSize);assertEquals(Integer.valueOf(10),mapper.readValue("{\"pageSize\":\"10\"}",PersonalExplorationExportController.Prepare.class).pageSize);}
    @Test void cursorAndDateDoNotCoerceOtherTypes(){ObjectMapper mapper=new ObjectMapper();assertThrows(IOException.class,()->mapper.readValue("{\"cursor\":123}",PersonalExplorationExportController.Page.class));assertThrows(IOException.class,()->mapper.readValue("{\"startDate\":true}",PersonalExplorationExportController.Prepare.class));}
    @Test void missingBodyDoesNotQuery(){PersonalExplorationExportService service=mock(PersonalExplorationExportService.class);PersonalExplorationExportController c=new PersonalExplorationExportController(service);assertNotEquals(200,c.prepare(null).get("code"));assertNotEquals(200,c.page(null).get("code"));verifyNoInteractions(service);}
}

package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.CapsuleService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CapsuleControllerTest {
    private CapsuleController controller(CapsuleService service){return new CapsuleController(service){@Override Long owner(){return 7L;}};}
    private CapsuleController.Create input(){CapsuleController.Create c=new CapsuleController.Create();c.requestId="capsule-request-0001";c.body="text";c.unlockDate="2027-01-01";return c;}
    @Test void inputRejectionIsStructured400(){CapsuleService s=mock(CapsuleService.class);when(s.create(eq(7L),anyString(),anyString(),isNull(),anyString())).thenThrow(new ServiceException("invalid date"));assertEquals(400,controller(s).create(input()).get("code"));}
    @Test void reusedKeyConflictRemains409(){CapsuleService s=mock(CapsuleService.class);when(s.create(eq(7L),anyString(),anyString(),isNull(),anyString())).thenThrow(new ServiceException("same key conflict",409));assertEquals(409,controller(s).create(input()).get("code"));}
    @Test void uncertainStorageFailureIsNotChangedToDefinitive400(){CapsuleService s=mock(CapsuleService.class);when(s.create(eq(7L),anyString(),anyString(),isNull(),anyString())).thenThrow(new IllegalStateException("storage unavailable"));assertThrows(IllegalStateException.class,()->controller(s).create(input()));}
    @Test void emptyInputIsRejectedBeforeCallingService(){CapsuleService s=mock(CapsuleService.class);assertEquals(400,controller(s).create(null).get("code"));verifyNoInteractions(s);}
}

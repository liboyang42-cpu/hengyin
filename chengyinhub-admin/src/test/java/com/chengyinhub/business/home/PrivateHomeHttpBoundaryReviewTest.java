package com.chengyinhub.business.home;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PrivateHomeHttpBoundaryReviewTest extends PrivateHomeHttpContractTest {
 @Test void malformedCoordinatesDoNotEchoOrCacheBody() throws Exception {
  mvc.perform(put("/api/native/home").contentType("application/json").content("{\"latitude\":\"private-location-value\"}"))
   .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("msg").value("HOME_REQUEST_INVALID"));verifyNoInteractions(service);
 }
 @Test void unauthorizedReadRemainsNoStore() throws Exception {
  when(service.getMyHome()).thenThrow(new ServiceException("HOME_UNAUTHORIZED"));
  mvc.perform(get("/api/native/home")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Pragma","no-cache")).andExpect(jsonPath("msg").value("HOME_UNAUTHORIZED"));
 }
}

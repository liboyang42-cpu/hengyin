package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Same Jackson2 family selected by the existing MVC configuration. No global customization. */
class MerchantReviewIdSerializationTest {
    @Test void listAndCommandResultSerializeExactDecimalStrings() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (long id : new long[]{1L, 9007199254740991L, 9007199254740992L, 9007199254740993L, Long.MAX_VALUE}) {
            MerchantReviewVO review = new MerchantReviewVO(); review.setId(id); review.setVersion(2);
            var row = mapper.readTree(mapper.writeValueAsString(review));
            assertTrue(row.get("id").isTextual()); assertEquals(Long.toString(id), row.get("id").asText());
            assertTrue(row.get("version").isInt());
            MerchantReviewWriteResultVO reply = new MerchantReviewWriteResultVO(); reply.setReviewId(id); reply.setVersion(3);
            var result = mapper.readTree(mapper.writeValueAsString(reply));
            assertTrue(result.get("reviewId").isTextual()); assertEquals(Long.toString(id), result.get("reviewId").asText());
            assertEquals(3, result.get("version").asInt());
        }
    }
    @Test void decimalCommandIdsBindWithoutRounding() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"reviewId\":\"9223372036854775807\"}";
        assertEquals(Long.valueOf(Long.MAX_VALUE), mapper.readValue(json,
                com.chengyinhub.business.domain.dto.MerchantReviewReplyRequest.class).getReviewId());
        assertEquals(Long.valueOf(Long.MAX_VALUE), mapper.readValue(json,
                com.chengyinhub.business.domain.dto.MerchantReviewReplyDeleteRequest.class).getReviewId());
        assertEquals(Long.valueOf(Long.MAX_VALUE), mapper.readValue(json,
                com.chengyinhub.web.controller.api.ApiMerchantReviewReplyReceiptController.Request.class).reviewId());
    }
    @Test void missingIdDoesNotBecomeTheStringNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(mapper.readTree(mapper.writeValueAsString(new MerchantReviewVO())).get("id").isNull());
        assertTrue(mapper.readTree(mapper.writeValueAsString(new MerchantReviewWriteResultVO())).get("reviewId").isNull());
    }
}

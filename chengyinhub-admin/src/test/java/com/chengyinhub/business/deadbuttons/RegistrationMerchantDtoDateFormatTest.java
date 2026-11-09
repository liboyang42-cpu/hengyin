package com.chengyinhub.business.deadbuttons;

import com.chengyinhub.business.domain.dto.RegistrationMerchantDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第十轮 f2 第 3 项(j2 F-01b):商家承接申请 {@code pages/topic/merchantapply} 上送
 * {@code startDate: 'yyyy-MM-dd 00:00:00' / endDate: 'yyyy-MM-dd 23:59:59'}。
 * DTO 的两个 Date 原先没有 @JsonFormat,jar 也没有全局日期格式 ⇒ HttpMessageNotReadableException,
 * 商家看到「系统繁忙」。这里用裸 ObjectMapper 钉住字段级契约,并钉住 GMT+8 口径。
 */
class RegistrationMerchantDtoDateFormatTest {

    private static String fmt(java.util.Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return f.format(d);
    }

    @Test
    void merchantApplyWireFormat_isAccepted() throws Exception {
        String body = "{\"topicId\":1,\"mode\":1,\"nodeId\":2,\"cooperateDate\":\"10:00-18:00\","
                + "\"startDate\":\"2026-11-04 00:00:00\",\"endDate\":\"2026-11-30 23:59:59\"}";
        RegistrationMerchantDTO dto = new ObjectMapper().readValue(body, RegistrationMerchantDTO.class);
        assertEquals("2026-11-04 00:00:00", fmt(dto.getStartDate()));
        assertEquals("2026-11-30 23:59:59", fmt(dto.getEndDate()));
    }
}

package com.chengyinhub.business.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第十轮 f1 item 8(j6 F6):待支付单的支付截止时刻必须带时分上线 —— 只给日期时,订单详情把它读成当天 00:00,
 * 显示「请在 2026-11-04 00:00 前完成支付」而真实截止是 13:15。
 */
class RegistrationPayExpireTimeJsonTest {

    @Test
    void payExpireTimeKeepsHoursAndMinutesOnTheWire() throws Exception {
        TimeZone shanghai = TimeZone.getTimeZone("Asia/Shanghai");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        in.setTimeZone(shanghai);
        Date deadline = in.parse("2026-11-04 13:15:29");
        ObjectMapper mapper = new ObjectMapper().setTimeZone(shanghai);

        CmsRegistration registration = new CmsRegistration();
        registration.setPayExpireTime(deadline);
        String cms = mapper.writeValueAsString(registration);
        assertTrue(cms.contains("\"payExpireTime\":\"2026-11-04 13:15:29\""), cms);

        ViewRegistration view = new ViewRegistration();
        view.setPayExpireTime(deadline);
        String viewJson = mapper.writeValueAsString(view);
        assertTrue(viewJson.contains("\"payExpireTime\":\"2026-11-04 13:15:29\""), viewJson);
    }
}

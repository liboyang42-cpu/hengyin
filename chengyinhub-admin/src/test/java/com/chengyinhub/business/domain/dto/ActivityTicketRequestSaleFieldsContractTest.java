package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.OmsTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-P2-26:小程序发布页票种编辑器提交的五个字段,必须一路接到实体上。
 *
 * <p>字段名以小程序为准({@code pages/publish/fabu/index.js:4464-4483} 的 {@code saveTicket}):
 * {@code meetingPointAddress / saleStartTime / saleEndTime / refundSupported / syncWithTheme}。
 * 时间是完整时间串({@code _doSubmit} 与 startTime/endTime 同口径归一 {@code YYYY-MM-DD HH:mm:ss}),
 * 布尔是真 boolean。这里用与上送体同形的 JSON 走 Jackson 落 DTO,再用
 * {@code CmsTopicServiceImpl#saveTickets} 的同一把 {@code BeanUtils.copyProperties} 落到实体,
 * 最后逐字段比对 —— 任何一处改名/类型不合都会在这里红。
 */
class ActivityTicketRequestSaleFieldsContractTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 fabu.js 上送体同键同形(节选 2-26 相关部分 + 必填兄弟字段)。 */
    private static final String PAGE_PAYLOAD = "{"
            + "\"name\":\"早鸟票\","
            + "\"price\":69,"
            + "\"totalStock\":100,"
            + "\"mode\":1,"
            + "\"meetingPoint\":\"人民广场\","
            + "\"meetingPointAddress\":\"上海市黄浦区人民大道 1 号\","
            + "\"startTime\":\"2026-10-01 00:00:00\","
            + "\"endTime\":\"2026-10-01 23:59:59\","
            + "\"saleStartTime\":\"2026-09-18 00:00:00\","
            + "\"saleEndTime\":\"2026-09-25 23:59:59\","
            + "\"description\":\"早鸟优惠\","
            + "\"refundSupported\":false,"
            + "\"syncWithTheme\":true"
            + "}";

    @Test
    void pagePayloadLandsOnDtoThenEntity() throws Exception
    {
        ActivityTicketRequest dto = MAPPER.readValue(PAGE_PAYLOAD, ActivityTicketRequest.class);

        assertEquals("上海市黄浦区人民大道 1 号", dto.getMeetingPointAddress(),
                "集合点地址必须以小程序字段名 meetingPointAddress 接住");
        assertEquals("2026-09-18 00:00:00", fmt(dto.getSaleStartTime()),
                "售票开始时间必须能吃下小程序发的日期串,口径同 startTime");
        assertEquals("2026-09-25 23:59:59", fmt(dto.getSaleEndTime()));
        assertEquals(Boolean.FALSE, dto.getRefundSupported(),
                "「支持随时退款」是真 boolean:前端用 !== false 判显示,不能收成 0/1 数字");
        assertEquals(Boolean.TRUE, dto.getSyncWithTheme());

        // 与 CmsTopicServiceImpl#saveTickets:5102 同一把映射(BeanUtils.copyProperties)
        OmsTicket entity = new OmsTicket();
        BeanUtils.copyProperties(dto, entity);

        assertEquals("2026-09-18 00:00:00", fmt(entity.getSaleStartTime()),
                "DTO 字段名必须与 OmsTicket 同名,否则 copyProperties 静默丢");
        assertEquals("2026-09-25 23:59:59", fmt(entity.getSaleEndTime()));
        assertEquals(Boolean.FALSE, entity.getRefundSupported());
        assertEquals(Boolean.TRUE, entity.getSyncWithTheme());
        assertEquals("上海市黄浦区人民大道 1 号", entity.getMeetingPointAddress());
    }

    private static String fmt(Date date)
    {
        if (date == null) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return format.format(date);
    }
}

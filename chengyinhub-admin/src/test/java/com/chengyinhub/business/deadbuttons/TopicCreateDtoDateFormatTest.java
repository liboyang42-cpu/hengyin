package com.chengyinhub.business.deadbuttons;

import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * D4:同一个 DTO 里的四个日期字段必须吃同一种格式。
 *
 * <p>病:{@code startDate/endDate/saleEndAt} 三个都带
 * {@code @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")},唯独 {@code recruitDeadline} 没有 ⇒ 它只吃
 * ISO-8601。小程序 {@code pages/publish/fabu/index.js} 恰恰用 {@code _normalizeDateTime(...,'23:59:59')}
 * 把四个日期统一成 {@code "yyyy-MM-dd HH:mm:ss"} 上送 ⇒ Jackson 抛 InvalidFormatException,
 * 请求还没进业务代码就成了一句「主题发布失败,请稍后重试」。
 *
 * <p>而自由探索主题(productType=2)**必须**带 recruitDeadline(CmsTopicServiceImpl 里有硬校验),
 * 所以这条不是边角:小程序发不出任何一个自由探索主题。
 *
 * <p>⚠️ 这里必须自己造 ObjectMapper 而不是注入 Spring 的:@JsonFormat 是字段级注解,
 * 换哪个 mapper 都生效;若哪天有人靠全局 date-format 兜底,这条测试仍钉着字段级契约。
 */
class TopicCreateDtoDateFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String fmt(java.util.Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return f.format(d);
    }

    @Test
    void allFourDateFields_acceptTheSameWireFormat() throws Exception {
        String body = "{\"startDate\":\"2026-09-01 10:00:00\","
                + "\"endDate\":\"2026-09-30 22:00:00\","
                + "\"saleEndAt\":\"2026-08-30 23:00:00\","
                + "\"recruitDeadline\":\"2026-09-06 23:59:59\"}";

        TopicCreateDTO dto = MAPPER.readValue(body, TopicCreateDTO.class);

        assertNotNull(dto.getRecruitDeadline(), "recruitDeadline 必须能吃下与兄弟字段同一种格式");
        assertEquals("2026-09-01 10:00:00", fmt(dto.getStartDate()));
        assertEquals("2026-09-30 22:00:00", fmt(dto.getEndDate()));
        assertEquals("2026-08-30 23:00:00", fmt(dto.getSaleEndAt()));
        // 时区也要一致:少了 timezone="GMT+8" 会按 JVM 默认时区解析,CI 恒 UTC ⇒ 差 8 小时的招募截止
        assertEquals("2026-09-06 23:59:59", fmt(dto.getRecruitDeadline()),
                "recruitDeadline 的时区口径必须与 startDate/endDate/saleEndAt 一致");
    }
}

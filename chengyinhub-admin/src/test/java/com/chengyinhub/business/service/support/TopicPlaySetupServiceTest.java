package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicPlaySetup;
import com.chengyinhub.business.domain.dto.TopicPlaySetupDTO;
import com.chengyinhub.business.mapper.TopicPlaySetupMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档二 L8 可玩时段与开局前准备:校验、跨午夜判定、只对自由探索执行。 */
class TopicPlaySetupServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final class MemoryMapper implements TopicPlaySetupMapper {
        final Map<Long, TopicPlaySetup> rows = new HashMap<Long, TopicPlaySetup>();
        @Override public TopicPlaySetup selectByTopicId(Long topicId) { return rows.get(topicId); }
        @Override public int upsert(TopicPlaySetup setup) { rows.put(setup.getTopicId(), setup); return 1; }
    }

    private static TopicPlaySetupService at(MemoryMapper mapper, int hour, int minute) {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 28, hour, minute, 0, 0, ZONE);
        return new TopicPlaySetupService(mapper, Clock.fixed(now.toInstant(), ZONE));
    }

    private static CmsTopic topic(Long clubId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setClubId(clubId);
        return topic;
    }

    private static TopicPlaySetupDTO dto(String mode, String start, String end, String... items) {
        TopicPlaySetupDTO dto = new TopicPlaySetupDTO();
        TopicPlaySetupDTO.PlayWindow window = new TopicPlaySetupDTO.PlayWindow();
        window.setMode(mode);
        window.setStart(start);
        window.setEnd(end);
        dto.setPlayWindow(window);
        dto.setPrepItems(Arrays.asList(items));
        return dto;
    }

    private static String message(Runnable action) {
        ServiceException error = assertThrows(ServiceException.class, action::run);
        assertEquals(Integer.valueOf(400), error.getCode());
        assertEquals(TopicPlaySetupService.ERR_INVALID, error.getDetailMessage());
        return error.getMessage();
    }

    @Test
    void validationMessagesMatchContract() {
        assertEquals("请选择可玩时段。", message(() -> TopicPlaySetupService.normalize(9L, dto("weekend", null, null))));
        assertEquals("请选择可玩时段。", message(() -> TopicPlaySetupService.normalize(9L, null)));
        assertEquals("请选择开始和结束时间。", message(() -> TopicPlaySetupService.normalize(9L, dto("custom", "21:30", null))));
        assertEquals("请选择开始和结束时间。", message(() -> TopicPlaySetupService.normalize(9L, dto("custom", "24:00", "05:00"))));
        assertEquals("开始和结束不能相同。", message(() -> TopicPlaySetupService.normalize(9L, dto("custom", "05:00", "05:00"))));
        assertEquals("最多 6 项。", message(() -> TopicPlaySetupService.normalize(9L,
                dto("any", null, null, "一", "二", "三", "四", "五", "六", "七"))));
        assertEquals("每项 1–20 字。", message(() -> TopicPlaySetupService.normalize(9L, dto("any", null, null, "  "))));
        assertEquals("每项 1–20 字。", message(() -> TopicPlaySetupService.normalize(9L,
                dto("any", null, null, "一二三四五六七八九十一二三四五六七八九十一"))));
        assertEquals("已有相同的准备项。", message(() -> TopicPlaySetupService.normalize(9L,
                dto("any", null, null, "带手电筒", " 带手电筒 "))));
    }

    @Test
    void savesNormalizedAndReadsBack() {
        MemoryMapper mapper = new MemoryMapper();
        Map<String, Object> view = at(mapper, 12, 0).save(topic(null), dto("custom", " 21:30 ", "05:00", " 手机电量充足 ", "带手电筒"));
        assertEquals(Boolean.FALSE, view.get("clubLed"));
        @SuppressWarnings("unchecked") Map<String, Object> window = (Map<String, Object>) view.get("playWindow");
        assertEquals("custom", window.get("mode"));
        assertEquals("21:30", window.get("start"));
        assertEquals("05:00", window.get("end"));
        assertEquals(Arrays.asList("手机电量充足", "带手电筒"), view.get("prepItems"));
        // day / night 忽略请求里的时间,回固定值
        view = at(mapper, 12, 0).save(topic(null), dto("night", "01:00", "02:00"));
        @SuppressWarnings("unchecked") Map<String, Object> night = (Map<String, Object>) view.get("playWindow");
        assertEquals("20:00", night.get("start"));
        assertEquals("06:00", night.get("end"));
        assertEquals(Collections.emptyList(), view.get("prepItems"));
        assertNull(mapper.rows.get(9L).getWindowStart());
    }

    @Test
    void unsetTopicReadsAsAnyWithoutItems() {
        Map<String, Object> view = at(new MemoryMapper(), 3, 0).authorView(topic(5L));
        assertEquals(Boolean.TRUE, view.get("clubLed"));
        @SuppressWarnings("unchecked") Map<String, Object> window = (Map<String, Object>) view.get("playWindow");
        assertEquals("any", window.get("mode"));
        assertFalse(window.containsKey("start"));
        assertEquals(Collections.emptyList(), view.get("prepItems"));
    }

    @Test
    void windowsIncludeStartExcludeEndAndWrapMidnight() {
        TopicPlaySetup day = TopicPlaySetupService.normalize(9L, dto("day", null, null));
        assertTrue(TopicPlaySetupService.isOpen(day, LocalTime.of(6, 0)));
        assertTrue(TopicPlaySetupService.isOpen(day, LocalTime.of(19, 59)));
        assertFalse(TopicPlaySetupService.isOpen(day, LocalTime.of(20, 0)));
        assertFalse(TopicPlaySetupService.isOpen(day, LocalTime.of(5, 59)));
        TopicPlaySetup night = TopicPlaySetupService.normalize(9L, dto("night", null, null));
        assertTrue(TopicPlaySetupService.isOpen(night, LocalTime.of(20, 0)));
        assertTrue(TopicPlaySetupService.isOpen(night, LocalTime.of(0, 30)));
        assertFalse(TopicPlaySetupService.isOpen(night, LocalTime.of(6, 0)));
        assertFalse(TopicPlaySetupService.isOpen(night, LocalTime.of(12, 0)));
        TopicPlaySetup custom = TopicPlaySetupService.normalize(9L, dto("custom", "09:15", "11:45"));
        assertTrue(TopicPlaySetupService.isOpen(custom, LocalTime.of(9, 15)));
        assertFalse(TopicPlaySetupService.isOpen(custom, LocalTime.of(11, 45)));
        assertTrue(TopicPlaySetupService.isOpen(TopicPlaySetupService.normalize(9L, dto("any", null, null)), LocalTime.of(3, 0)));
    }

    @Test
    void startIsRefusedOnlyForSoloThemesOutsideWindow() {
        MemoryMapper mapper = new MemoryMapper();
        mapper.upsert(TopicPlaySetupService.normalize(9L, dto("night", null, null)));
        TopicPlaySetupService noon = at(mapper, 12, 0);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> noon.requireOpen(topic(null), 0L));
        assertEquals("当前不在可玩时段，暂时不能开局。", refused.getMessage());
        assertThrows(IllegalStateException.class, () -> noon.requireOpen(topic(null), null));
        assertDoesNotThrow(() -> at(mapper, 5, 59).requireOpen(topic(null), 0L));
    }

    @Test
    void clubThemesAndEventsIgnoreWindow() {
        MemoryMapper mapper = new MemoryMapper();
        mapper.upsert(TopicPlaySetupService.normalize(9L, dto("night", null, null)));
        TopicPlaySetupService noon = at(mapper, 12, 0);
        assertDoesNotThrow(() -> noon.requireOpen(topic(5L), 0L));
        assertDoesNotThrow(() -> noon.requireOpen(topic(null), 33L));
        assertDoesNotThrow(() -> at(mapper, 22, 0).requireOpen(topic(null), 0L));
    }

    @Test
    void playerViewHidesWindowForClubThemesAndReportsOpenNow() {
        MemoryMapper mapper = new MemoryMapper();
        mapper.upsert(TopicPlaySetupService.normalize(9L, dto("night", null, null, "带手电筒")));
        Map<String, Object> closed = at(mapper, 12, 0).playerView(topic(null));
        assertEquals(Boolean.FALSE, closed.get("openNow"));
        assertTrue(closed.get("playWindow") instanceof Map);
        assertEquals(Collections.singletonList("带手电筒"), closed.get("prepItems"));
        assertEquals(Boolean.TRUE, at(mapper, 21, 0).playerView(topic(null)).get("openNow"));
        Map<String, Object> club = at(mapper, 12, 0).playerView(topic(5L));
        assertNull(club.get("playWindow"));
        assertEquals(Boolean.TRUE, club.get("openNow"));
        List<?> none = (List<?>) at(new MemoryMapper(), 12, 0).playerView(topic(null)).get("prepItems");
        assertTrue(none.isEmpty());
    }
}

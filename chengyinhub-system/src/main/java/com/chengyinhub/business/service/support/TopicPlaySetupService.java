package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicPlaySetup;
import com.chengyinhub.business.domain.dto.TopicPlaySetupDTO;
import com.chengyinhub.business.mapper.TopicPlaySetupMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题的可玩时段与开局前准备(文档二 UI 实施 L8;原型 A e-time / e-prep,原型 B t-safe)。
 *
 * <p>时段只对自由探索 / 个人票(没有俱乐部的主题、非赛事局)生效,在新建游玩局时由服务端执行;
 * 俱乐部带队(E2:主题有俱乐部)一律不看时段。准备项只给玩家勾,后端不记录勾选。
 * 时间一律按北京时间;跨午夜的时段(开始晚于结束)按「开始到次日结束」算,含开始、不含结束。
 */
@Service
public class TopicPlaySetupService {

    public static final String MODE_ANY = "any";
    public static final String MODE_DAY = "day";
    public static final String MODE_NIGHT = "night";
    public static final String MODE_CUSTOM = "custom";

    /** E3:白天 06:00–20:00,夜间 20:00–06:00。 */
    public static final String DAY_START = "06:00";
    public static final String DAY_END = "20:00";

    public static final int MAX_PREP_ITEMS = 6;
    public static final int MAX_PREP_ITEM_LENGTH = 20;

    public static final String ERR_INVALID = "PLAY_SETUP_INVALID";
    public static final String MSG_CLOSED = "当前不在可玩时段，暂时不能开局。";

    static final String MSG_MODE = "请选择可玩时段。";
    static final String MSG_TIMES = "请选择开始和结束时间。";
    static final String MSG_SAME = "开始和结束不能相同。";
    static final String MSG_TOO_MANY = "最多 6 项。";
    static final String MSG_ITEM_LENGTH = "每项 1–20 字。";
    static final String MSG_DUPLICATE = "已有相同的准备项。";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern HHMM = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TopicPlaySetupMapper mapper;
    private final Clock clock;

    @Autowired
    public TopicPlaySetupService(TopicPlaySetupMapper mapper) {
        this(mapper, Clock.system(ZONE));
    }

    TopicPlaySetupService(TopicPlaySetupMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 创作者读:{clubLed, playWindow, prepItems}。 */
    public Map<String, Object> authorView(CmsTopic topic) {
        TopicPlaySetup setup = mapper.selectByTopicId(topic.getId());
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("clubLed", Boolean.valueOf(clubLed(topic)));
        view.put("playWindow", windowView(setup));
        view.put("prepItems", prepItems(setup));
        return view;
    }

    /** 创作者写:整体覆盖,校验失败抛 400 + {@link #ERR_INVALID}。返回规范化后的创作者视图。 */
    public Map<String, Object> save(CmsTopic topic, TopicPlaySetupDTO dto) {
        TopicPlaySetup next = normalize(topic.getId(), dto);
        mapper.upsert(next);
        return authorView(topic);
    }

    /** 准备项文本(供调用方送内容安全闸)。 */
    public static List<String> prepTexts(TopicPlaySetupDTO dto) {
        return dto == null || dto.getPrepItems() == null ? Collections.<String>emptyList() : dto.getPrepItems();
    }

    /** 玩家读:playWindow 只在非俱乐部且有限制时给出;openNow 按北京时间现在。 */
    public Map<String, Object> playerView(CmsTopic topic) {
        TopicPlaySetup setup = mapper.selectByTopicId(topic.getId());
        boolean applies = windowApplies(topic, setup);
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("playWindow", applies ? windowView(setup) : null);
        view.put("openNow", Boolean.valueOf(!applies || isOpen(setup, LocalTime.now(clock))));
        view.put("prepItems", prepItems(setup));
        return view;
    }

    /**
     * 新建游玩局时调用:自由探索 / 个人票、不在可玩时段 → 抛 {@link IllegalStateException}({@link #MSG_CLOSED})。
     * 赛事局(activityId &gt; 0)与俱乐部主题不看时段。
     */
    public void requireOpen(CmsTopic topic, Long activityId) {
        if (topic == null || topic.getId() == null || (activityId != null && activityId > 0L) || clubLed(topic)) return;
        TopicPlaySetup setup = mapper.selectByTopicId(topic.getId());
        if (!windowApplies(topic, setup)) return;
        if (!isOpen(setup, LocalTime.now(clock))) throw new IllegalStateException(MSG_CLOSED);
    }

    // ==================== 规则 ====================

    static boolean clubLed(CmsTopic topic) {
        return topic.getClubId() != null && topic.getClubId() > 0L;
    }

    private static boolean windowApplies(CmsTopic topic, TopicPlaySetup setup) {
        return !clubLed(topic) && setup != null && setup.getWindowMode() != null && !MODE_ANY.equals(setup.getWindowMode());
    }

    static boolean isOpen(TopicPlaySetup setup, LocalTime now) {
        if (setup == null || setup.getWindowMode() == null || MODE_ANY.equals(setup.getWindowMode())) return true;
        String[] range = range(setup);
        if (range == null) return true;
        LocalTime start = LocalTime.parse(range[0]);
        LocalTime end = LocalTime.parse(range[1]);
        if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
        return !now.isBefore(start) || now.isBefore(end);
    }

    private static String[] range(TopicPlaySetup setup) {
        String mode = setup.getWindowMode();
        if (MODE_DAY.equals(mode)) return new String[] {DAY_START, DAY_END};
        if (MODE_NIGHT.equals(mode)) return new String[] {DAY_END, DAY_START};
        if (MODE_CUSTOM.equals(mode) && setup.getWindowStart() != null && setup.getWindowEnd() != null) {
            return new String[] {setup.getWindowStart(), setup.getWindowEnd()};
        }
        return null;
    }

    private static Map<String, Object> windowView(TopicPlaySetup setup) {
        Map<String, Object> window = new LinkedHashMap<String, Object>();
        String mode = setup == null || setup.getWindowMode() == null ? MODE_ANY : setup.getWindowMode();
        window.put("mode", mode);
        String[] range = MODE_ANY.equals(mode) ? null : range(setup);
        if (range != null) {
            window.put("start", range[0]);
            window.put("end", range[1]);
        }
        return window;
    }

    private static List<String> prepItems(TopicPlaySetup setup) {
        List<String> items = new ArrayList<String>();
        if (setup == null || setup.getPrepItemsJson() == null || setup.getPrepItemsJson().trim().isEmpty()) return items;
        try {
            JsonNode node = JSON.readTree(setup.getPrepItemsJson());
            if (node != null && node.isArray()) for (JsonNode item : node) if (item.isTextual()) items.add(item.asText());
        } catch (Exception unreadable) {
            throw new IllegalStateException("开局前准备数据不可用", unreadable);
        }
        return items;
    }

    static TopicPlaySetup normalize(Long topicId, TopicPlaySetupDTO dto) {
        TopicPlaySetupDTO.PlayWindow window = dto == null ? null : dto.getPlayWindow();
        String mode = window == null ? null : window.getMode();
        if (!MODE_ANY.equals(mode) && !MODE_DAY.equals(mode) && !MODE_NIGHT.equals(mode) && !MODE_CUSTOM.equals(mode)) {
            throw invalid(MSG_MODE);
        }
        TopicPlaySetup setup = new TopicPlaySetup();
        setup.setTopicId(topicId);
        setup.setWindowMode(mode);
        if (MODE_CUSTOM.equals(mode)) {
            String start = trim(window.getStart());
            String end = trim(window.getEnd());
            if (start == null || end == null || !HHMM.matcher(start).matches() || !HHMM.matcher(end).matches()) {
                throw invalid(MSG_TIMES);
            }
            if (start.equals(end)) throw invalid(MSG_SAME);
            setup.setWindowStart(start);
            setup.setWindowEnd(end);
        }
        List<String> source = dto.getPrepItems() == null ? Collections.<String>emptyList() : dto.getPrepItems();
        if (source.size() > MAX_PREP_ITEMS) throw invalid(MSG_TOO_MANY);
        ArrayNode items = JSON.createArrayNode();
        Set<String> seen = new HashSet<String>();
        for (String raw : source) {
            String text = trim(raw);
            if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > MAX_PREP_ITEM_LENGTH) {
                throw invalid(MSG_ITEM_LENGTH);
            }
            if (!seen.add(text)) throw invalid(MSG_DUPLICATE);
            items.add(text);
        }
        setup.setPrepItemsJson(items.isEmpty() ? null : items.toString());
        setup.setUpdateTime(new Date());
        return setup;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST).setDetailMessage(ERR_INVALID);
    }
}

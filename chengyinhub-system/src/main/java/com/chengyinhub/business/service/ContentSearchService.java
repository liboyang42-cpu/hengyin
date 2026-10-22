package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ContentSearchItemVO;
import com.chengyinhub.business.mapper.ContentSearchMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * O-14 统一内容搜索：投影刷新 + 查询。
 * 投影只收对任何人都公开的主题/活动/帖文；查询时每行再按源表实时公开闸复核，投影过期也不泄露。
 */
@Service
public class ContentSearchService {
    public static final List<String> TYPES = Arrays.asList("topic", "activity", "post");
    static final int KEYWORD_MAX_LENGTH = 50;
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");

    @Autowired
    private ContentSearchMapper contentSearchMapper;

    /** 全量幂等对齐：先把公开内容 upsert 为 VISIBLE,再把不再公开的打成 HIDDEN 并清空文本快照。 */
    public Map<String, Integer> refreshProjection() {
        Date now = new Date();
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("topicVisible", contentSearchMapper.upsertVisibleTopics(now));
        result.put("topicHidden", contentSearchMapper.hideStaleTopics(now));
        result.put("activityVisible", contentSearchMapper.upsertVisibleActivities(now));
        result.put("activityHidden", contentSearchMapper.hideStaleActivities(now));
        result.put("postVisible", contentSearchMapper.upsertVisiblePosts(now));
        result.put("postHidden", contentSearchMapper.hideStalePosts(now));
        return result;
    }

    /** 调用前由控制器 startPage();本方法第一条 SQL 就是分页查询。 */
    public List<ContentSearchItemVO> search(String keyword, String types, String cityCode, Long viewerId) {
        return contentSearchMapper.selectUnifiedSearch(
                likeEscape(keyword(keyword)), documentTypes(types), cityCode(cityCode), viewerId);
    }

    /** 只做参数校验(不触库)，失败抛 400 ServiceException。 */
    public static void validate(String keyword, String types, String cityCode) {
        keyword(keyword);
        documentTypes(types);
        cityCode(cityCode);
    }

    static String keyword(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new ServiceException("keyword 不能为空", 400);
        if (normalized.length() > KEYWORD_MAX_LENGTH) {
            throw new ServiceException("keyword 最长 " + KEYWORD_MAX_LENGTH + " 个字符", 400);
        }
        return normalized;
    }

    /** 逗号分隔的 topic/activity/post,缺省为全部；返回投影表里的大写类型。 */
    static List<String> documentTypes(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            for (String type : TYPES) out.add(type.toUpperCase());
            return out;
        }
        for (String part : value.split(",")) {
            String type = part.trim().toLowerCase();
            if (type.isEmpty()) continue;
            if (!TYPES.contains(type)) throw new ServiceException("types 枚举无效，支持值: " + TYPES, 400);
            if (!out.contains(type.toUpperCase())) out.add(type.toUpperCase());
        }
        if (out.isEmpty()) throw new ServiceException("types 枚举无效，支持值: " + TYPES, 400);
        return out;
    }

    static String cityCode(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (!CITY_CODE.matcher(normalized).matches()) throw new ServiceException("city_code 必须为 6 位城市编码", 400);
        return normalized;
    }

    /** LIKE 通配符转义(配合 SQL 里的 escape '\\'):用户输入的 % / _ 按字面匹配。 */
    static String likeEscape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

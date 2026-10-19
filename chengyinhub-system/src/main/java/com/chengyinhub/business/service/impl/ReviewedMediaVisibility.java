package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.ReviewedMediaMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CL-39：与社区帖 selectMedia 同一条规则——他人只看审核 PASS 的媒体，作者看自己全部媒体及其审核态。
 *
 * <p>俱乐部动态 / 旧版创意广场没有逐图审核列；PASS 事实落在 wx_content_callback_inbox
 * (送检时登记 media_hash=SHA-256(url)，回调落地 state=2/verdict=0)。这里按 (bizType, bizId, url 哈希)
 * 读取它，不改回调写侧。待派发、等回调、送检失败、回调永不到达、转人工都不算 PASS ⇒ 对他人隐藏。
 */
@Component
public class ReviewedMediaVisibility
{
    public static final String STATE_PASS = "PASS";
    public static final String STATE_PENDING = "PENDING";

    @Autowired
    private ReviewedMediaMapper reviewedMediaMapper;

    public ReviewedMediaVisibility()
    {
    }

    public ReviewedMediaVisibility(ReviewedMediaMapper reviewedMediaMapper)
    {
        this.reviewedMediaMapper = reviewedMediaMapper;
    }

    /** bizId → 已 PASS 的媒体 URL 哈希(小写 hex)。一次 IN 查询，不随行数增长。 */
    public Map<Long, Set<String>> passedMediaHashes(int bizType, Collection<Long> bizIds)
    {
        Map<Long, Set<String>> out = new HashMap<>();
        if (bizIds == null || bizIds.isEmpty())
        {
            return out;
        }
        List<Map<String, Object>> rows = reviewedMediaMapper.selectPassedMedia(bizType, new LinkedHashSet<>(bizIds));
        if (rows == null)
        {
            return out;
        }
        for (Map<String, Object> row : rows)
        {
            Object id = column(row, "biz_id");
            Object hash = column(row, "media_hash");
            if (!(id instanceof Number) || !(hash instanceof byte[]))
            {
                continue;
            }
            out.computeIfAbsent(((Number) id).longValue(), k -> new HashSet<>()).add(hex((byte[]) hash));
        }
        return out;
    }

    /** 媒体串里每张图都有 PASS 记录。空串视为无媒体(true)。 */
    public static boolean allPassed(String media, Set<String> passed)
    {
        return media(media).size() == passedMedia(media, passed).size();
    }

    /** 只保留 PASS 的图，分隔符沿用原串(逗号或分号)；一张都没过返回空串，正文照常展示。 */
    public static String passedOnly(String media, Set<String> passed)
    {
        if (media == null)
        {
            return null;
        }
        String separator = media.indexOf(';') >= 0 && media.indexOf(',') < 0 ? ";" : ",";
        return String.join(separator, passedMedia(media, passed));
    }

    private static List<String> passedMedia(String media, Set<String> passed)
    {
        List<String> kept = new ArrayList<>();
        Set<String> hashes = passed == null ? Collections.<String>emptySet() : passed;
        for (String url : media(media))
        {
            if (hashes.contains(WxContentCallbackInboxServiceImpl.sha256Hex(url)))
            {
                kept.add(url);
            }
        }
        return kept;
    }

    /** 与送检侧 submitMediaCheck / ClubPostMediaReviewTransactionService.mediaByHash 同一切分：[,;] + trim。 */
    private static List<String> media(String media)
    {
        List<String> urls = new ArrayList<>();
        if (media == null || media.trim().isEmpty())
        {
            return urls;
        }
        for (String raw : media.split("[,;]"))
        {
            String url = raw == null ? "" : raw.trim();
            if (!url.isEmpty())
            {
                urls.add(url);
            }
        }
        return urls;
    }

    private static Object column(Map<String, Object> row, String name)
    {
        Object value = row.get(name);
        return value != null ? value : row.get(name.toUpperCase());
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
        {
            String part = Integer.toHexString(b & 0xff);
            if (part.length() == 1) out.append('0');
            out.append(part);
        }
        return out.toString();
    }
}

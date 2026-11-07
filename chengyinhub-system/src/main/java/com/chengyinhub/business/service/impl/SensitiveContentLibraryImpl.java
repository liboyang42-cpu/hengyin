package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ContentSensitiveHit;
import com.chengyinhub.business.domain.ContentSensitiveImage;
import com.chengyinhub.business.domain.ContentSensitiveWord;
import com.chengyinhub.business.mapper.ContentSensitiveMapper;
import com.chengyinhub.business.service.ISensitiveContentLibrary;
import com.chengyinhub.business.service.safety.ImageFingerprint;
import com.chengyinhub.business.service.safety.SensitiveWordMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Round5D 敏感内容库实现。
 *
 * <p>内存里是一份不可变快照(字典树 + 图片指纹)。每次使用前若距上次核对超过 30 秒,
 * 就读一次三张表的版本指纹(行数 + 最后修改时间);不同才整份重载。多实例之间靠同一张表的指纹同步,
 * 不需要另起消息通道。重载失败:保留上一份好的快照继续用,并记 ERROR;从未成功加载过时同样记 ERROR,
 * 下一次使用会再试 —— 绝不会因为加载失败而悄悄把检查关掉(微信机审仍在后面)。
 */
@Service
public class SensitiveContentLibraryImpl implements ISensitiveContentLibrary {

    private static final Logger log = LoggerFactory.getLogger(SensitiveContentLibraryImpl.class);
    static final long VERSION_CHECK_INTERVAL_MS = 30_000L;

    static final class Snapshot {
        final String version;
        final SensitiveWordMatcher matcher;
        final Map<String, String> imageBySha;
        final List<ContentSensitiveImage> dhashImages;

        Snapshot(String version, SensitiveWordMatcher matcher, Map<String, String> imageBySha,
                 List<ContentSensitiveImage> dhashImages) {
            this.version = version;
            this.matcher = matcher;
            this.imageBySha = imageBySha;
            this.dhashImages = dhashImages;
        }
    }

    @Autowired
    private ContentSensitiveMapper mapper;

    private Clock clock = Clock.systemDefaultZone();
    private volatile Snapshot snapshot;
    private volatile long lastVersionCheck = Long.MIN_VALUE / 2;

    private Snapshot current() {
        long now = clock.millis();
        if (snapshot == null || now - lastVersionCheck >= VERSION_CHECK_INTERVAL_MS) {
            synchronized (this) {
                if (snapshot == null || now - lastVersionCheck >= VERSION_CHECK_INTERVAL_MS) {
                    lastVersionCheck = now;
                    refresh(false);
                }
            }
        }
        return snapshot;
    }

    @Override
    public void reloadNow() {
        synchronized (this) {
            lastVersionCheck = clock.millis();
            refresh(true);
        }
    }

    private void refresh(boolean force) {
        Snapshot previous = snapshot;
        try {
            String version = mapper.selectLibraryVersion();
            if (!force && previous != null && Objects.equals(version, previous.version)) return;
            List<SensitiveWordMatcher.Entry> entries = new ArrayList<SensitiveWordMatcher.Entry>();
            for (ContentSensitiveWord w : mapper.selectEnabledWords()) {
                entries.add(new SensitiveWordMatcher.Entry(w.getWord(), w.getCategory(), w.getAction()));
            }
            List<String> allows = mapper.selectEnabledAllowPhrases();
            Map<String, String> bySha = new HashMap<String, String>();
            List<ContentSensitiveImage> dhash = new ArrayList<ContentSensitiveImage>();
            for (ContentSensitiveImage img : mapper.selectEnabledImages()) {
                if (img.getSha256() != null) bySha.put(img.getSha256().toLowerCase(), img.getCategory());
                if (img.getDhash() != null) dhash.add(img);
            }
            snapshot = new Snapshot(version, SensitiveWordMatcher.build(entries, allows),
                    Collections.unmodifiableMap(bySha), Collections.unmodifiableList(dhash));
            log.info("[敏感内容库] 已加载 words={} allows={} images={}", entries.size(),
                    allows == null ? 0 : allows.size(), bySha.size());
        } catch (RuntimeException e) {
            // 失败不清空:继续用上一份好的;一份都没有时也只记错,微信机审仍在。
            log.error("[敏感内容库] 加载失败,{} exceptionType={}",
                    previous == null ? "尚无可用词库,下一次使用时重试" : "继续使用上一份词库 version=" + previous.version,
                    e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public SensitiveWordMatcher.Hit matchText(String text) {
        Snapshot s = current();
        return s == null ? null : s.matcher.find(text);
    }

    @Override
    public String blockingCategory(String text, String contentType, Long contentId) {
        SensitiveWordMatcher.Hit hit = matchText(text);
        if (hit == null) return null;
        recordHit("TEXT", contentType, contentId, hit.getCategory(), hit.getAction());
        return hit.isBlock() ? hit.getCategory() : null;
    }

    @Override
    public String matchImage(byte[] bytes) {
        Snapshot s = current();
        if (s == null || bytes == null || (s.imageBySha.isEmpty() && s.dhashImages.isEmpty())) return null;
        String exact = s.imageBySha.get(ImageFingerprint.sha256(bytes));
        if (exact != null) return exact;
        if (s.dhashImages.isEmpty()) return null;
        Long hash = ImageFingerprint.dhash(bytes);
        if (hash == null) return null;
        for (ContentSensitiveImage img : s.dhashImages) {
            if (ImageFingerprint.distance(hash, img.getDhash()) <= ImageFingerprint.NEAR_DUPLICATE_DISTANCE) {
                return img.getCategory();
            }
        }
        return null;
    }

    @Override
    public boolean hasImageEntries() {
        Snapshot s = current();
        return s != null && (!s.imageBySha.isEmpty() || !s.dhashImages.isEmpty());
    }

    @Override
    public void recordHit(String source, String contentType, Long contentId, String category, String action) {
        try {
            ContentSensitiveHit hit = new ContentSensitiveHit();
            hit.setSource(source);
            hit.setContentType(contentType == null ? "UNKNOWN" : contentType);
            hit.setContentId(contentId);
            hit.setCategory(category == null ? "OTHER" : category);
            hit.setAction(action);
            hit.setCreateTime(new Date(clock.millis()));
            mapper.insertHit(hit);
        } catch (RuntimeException e) {
            log.error("[敏感内容库] 命中记录写入失败 contentType={} exceptionType={}", contentType,
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public String currentVersion() {
        Snapshot s = current();
        return s == null ? null : s.version;
    }
}

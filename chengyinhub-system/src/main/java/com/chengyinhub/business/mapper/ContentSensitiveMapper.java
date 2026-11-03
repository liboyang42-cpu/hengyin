package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ContentSensitiveAllow;
import com.chengyinhub.business.domain.ContentSensitiveHit;
import com.chengyinhub.business.domain.ContentSensitiveImage;
import com.chengyinhub.business.domain.ContentSensitiveWord;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** Round5D 敏感内容库:词、放行短语、图片指纹、命中记录,以及存量复查读取公开文本。 */
public interface ContentSensitiveMapper {

    // ---- 加载(只读启用项) ----
    List<ContentSensitiveWord> selectEnabledWords();
    List<String> selectEnabledAllowPhrases();
    List<ContentSensitiveImage> selectEnabledImages();
    /** 版本指纹:三张表的行数与最后修改时间拼成一串;任一实例改了表,其它实例下一次轮询就会重载。 */
    String selectLibraryVersion();

    // ---- 管理 ----
    List<ContentSensitiveWord> selectWordList(@Param("word") String word, @Param("category") String category,
                                              @Param("enabled") Integer enabled);
    int insertWord(ContentSensitiveWord word);
    int updateWordEnabled(@Param("id") Long id, @Param("enabled") Integer enabled,
                          @Param("updateBy") String updateBy, @Param("now") Date now);
    List<ContentSensitiveAllow> selectAllowList(@Param("phrase") String phrase);
    int insertAllow(ContentSensitiveAllow allow);
    int updateAllowEnabled(@Param("id") Long id, @Param("enabled") Integer enabled,
                           @Param("updateBy") String updateBy, @Param("now") Date now);
    List<ContentSensitiveImage> selectImageList();
    int insertImage(ContentSensitiveImage image);
    int updateImageEnabled(@Param("id") Long id, @Param("enabled") Integer enabled, @Param("now") Date now);

    // ---- 命中 ----
    int insertHit(ContentSensitiveHit hit);
    List<ContentSensitiveHit> selectRecentHits(@Param("limit") int limit);
    int countRescanHit(@Param("contentType") String contentType, @Param("contentId") Long contentId,
                       @Param("scanVersion") String scanVersion, @Param("action") String action);

    // ---- 存量复查:按 id 递增游标分页读公开文本(id, text, owner) ----
    List<Map<String, Object>> selectPublicText(@Param("contentType") String contentType,
                                               @Param("afterId") Long afterId, @Param("limit") int limit);
}

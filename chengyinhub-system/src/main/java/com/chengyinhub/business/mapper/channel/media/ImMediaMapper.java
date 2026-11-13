package com.chengyinhub.business.mapper.channel.media;

import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** IM 媒体上传台账（im_media_upload）+ 审核事实（app_media_asset）联读，以及授权读用的原消息行。 */
public interface ImMediaMapper
{
    int insertUpload(Map<String, Object> row);

    Map<String, Object> lockByRequest(@Param("ownerId") long ownerId, @Param("kind") String kind, @Param("requestId") String requestId);

    Map<String, Object> selectByRequest(@Param("ownerId") long ownerId, @Param("kind") String kind, @Param("requestId") String requestId);

    Map<String, Object> selectById(@Param("id") long id);

    Map<String, Object> lockById(@Param("id") long id);

    Map<String, Object> selectByMessage(@Param("messageId") long messageId);

    int markUploaded(@Param("id") long id, @Param("assetId") long assetId);

    int markDeleted(@Param("id") long id);

    int bindMessage(@Param("id") long id, @Param("messageId") long messageId);

    Map<String, Object> selectMessage(@Param("messageId") long messageId);
}

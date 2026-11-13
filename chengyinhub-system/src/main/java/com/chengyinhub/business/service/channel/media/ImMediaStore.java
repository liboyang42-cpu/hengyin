package com.chengyinhub.business.service.channel.media;

/**
 * IM 媒体字节与审核事实的存放缝：生产走既有 OSS 私有对象 + app_media_asset 审核（{@link OssImMediaStore}），
 * 测试可替换。返回的 assetId 即 app_media_asset.id，审核态只从该表读。
 */
public interface ImMediaStore
{
    /** 写私有对象并登记待审媒体（HOLD + 审核单），返回 app_media_asset.id。 */
    long put(long ownerMemberId, byte[] bytes, String ext, String mime);

    /** 300 秒内有效的读取地址；只在审核通过后调用。 */
    String shortLivedUrl(long assetId, int seconds);

    /** 删除尚未挂到消息上的对象；对象不在本桶时返回 false。 */
    boolean delete(long assetId);
}

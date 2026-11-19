package com.chengyinhub.business.service.support;

/**
 * 角色台词配音缓存:按 sha256(preset + text) 存一份,同一句只合成一次。
 * 生产实现落 OSS 私有对象 cast_voice/{sha256}.mp3,返回短期签名地址。
 */
public interface CastVoiceAudioCache
{
    /** 命中返回可播放地址,未命中返回 null。 */
    String find(String sha256Hex);

    /** 把厂商临时音频落成缓存对象,返回可播放地址。 */
    String store(String sha256Hex, String vendorUrl);
}

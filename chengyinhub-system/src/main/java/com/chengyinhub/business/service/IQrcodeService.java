package com.chengyinhub.business.service;

/**
 * 二维码服务接口
 */
public interface IQrcodeService {
    /**
     * 生成二维码
     * @param content 二维码内容
     * @param width 宽度
     * @param height 高度
     * @return 二维码图片URL
     */
    String generateQrcode(String content, int width, int height);

    /** 动态码直接返回 PNG Data URI，不写入对象存储。 */
    String generateInlineQrcode(String content, int width, int height);
}

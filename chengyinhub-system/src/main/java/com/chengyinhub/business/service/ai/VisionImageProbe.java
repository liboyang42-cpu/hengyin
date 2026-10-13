package com.chengyinhub.business.service.ai;

/**
 * 远程图片预检出口:取长度 / 类型 / 头部字节,用于「发请求前拒掉超限图」。
 *
 * <p>实现必须设超时且读量有上限;探不到(网络失败 / 服务端不支持 Range)一律返回 null ——
 * 调用方按「未知」放行,绝不因为探针自身失败把一张合法照片判死(那是硬拦,会让节点走死)。
 */
public interface VisionImageProbe
{
    /** 探针能拿到的图片事实;拿不到返回 null。 */
    ImageHead probe(String imageUrl);

    class ImageHead
    {
        private final long contentLength;
        private final String contentType;
        private final byte[] headBytes;

        public ImageHead(long contentLength, String contentType, byte[] headBytes)
        {
            this.contentLength = contentLength;
            this.contentType = contentType;
            this.headBytes = headBytes == null ? new byte[0] : headBytes;
        }

        /** 整图字节数;未知为 -1。 */
        public long getContentLength()
        {
            return contentLength;
        }

        /** HTTP Content-Type;未知为 null。 */
        public String getContentType()
        {
            return contentType;
        }

        /** 图片头部若干字节,用于读宽高;未知为空数组。 */
        public byte[] getHeadBytes()
        {
            return headBytes;
        }
    }
}

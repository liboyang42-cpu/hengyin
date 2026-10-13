package com.chengyinhub.business.service.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import com.chengyinhub.common.utils.ip.IpUtils;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * 远程图片探针:一次 Range 请求只读图片头部(≤128 KiB),拿到全图大小 / Content-Type / 头部字节。
 *
 * <p>读满上限就断开,绝不为了一张图把整图拉进内存;任何失败都返回 null(调用方按未知放行)。
 *
 * @author chengyinhub
 */
public class HttpVisionImageProbe implements VisionImageProbe
{
    private static final int TIMEOUT_MS = 3000;
    static final int HEAD_BYTES = 128 * 1024;

    @Override
    public ImageHead probe(String imageUrl)
    {
        return notInternalTarget(imageUrl) ? fetchHead(imageUrl) : null;
    }

    /**
     * 出网闸之后的真实请求;包内可见,测试直接打本地服务器(127.0.0.1 过不了出网闸)。
     *
     * <p>★ 必须 {@code executeAsync()}:hutool 同步 {@code execute()} 会先把整个响应读进内存再交出流。
     * 服务端遵守 Range 时无所谓,但**忽略 Range 回 200 全量**时,同步模式会把整张图吸进堆,
     * 后面的 readUpTo 只是截一个已在内存里的数组。异步模式读满 HEAD_BYTES 就关连接。
     * 重定向显式不跟随:302 可能跳出上面的出网闸,不依赖 hutool 的全局默认值。
     */
    ImageHead fetchHead(String imageUrl)
    {
        try (HttpResponse resp = HttpRequest.get(imageUrl)
                .header("Range", "bytes=0-" + (HEAD_BYTES - 1))
                .timeout(TIMEOUT_MS)
                .setMaxRedirectCount(0)
                .executeAsync())
        {
            if (!resp.isOk())
            {
                return null;
            }
            return new ImageHead(contentLength(resp), resp.header("Content-Type"),
                    readUpTo(resp.bodyStream(), HEAD_BYTES));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 本机 / 内网字面量目标:凡是**我们服务器**发起的抓图请求都不该碰(探针、下载回落共用)。
     *
     * <p>⚠️ 这不是 SSRF 安全闸:只查字面量主机名,不做 DNS 解析、不校验重定向,
     * 绕过方式很多。它唯一的职责是不主动去够内网,本轮明确不做完整安全防线 ——
     * 后来者别把它当安全闸,要防 SSRF 得另立单独的网络层收口。
     *
     * @return true=目标不像本机/内网字面量,可以发起请求
     */
    static boolean notInternalTarget(String imageUrl)
    {
        try
        {
            String host = new URL(imageUrl).getHost();
            if (host == null || host.isEmpty())
            {
                return false;
            }
            String lower = host.toLowerCase();
            if ("localhost".equals(lower) || lower.endsWith(".localhost")
                    || lower.endsWith(".local") || lower.endsWith(".internal"))
            {
                return false;
            }
            if ("::1".equals(host) || "[::1]".equals(lower))
            {
                return false;
            }
            return !IpUtils.isIP(host) || !IpUtils.internalIp(host);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** 206 时 Content-Length 只是这一段的长度,整图大小在 Content-Range 的 "/" 后面。 */
    private static long contentLength(HttpResponse resp)
    {
        String range = resp.header("Content-Range");
        if (range != null)
        {
            int slash = range.lastIndexOf('/');
            if (slash >= 0)
            {
                return parseLong(range.substring(slash + 1));
            }
        }
        return parseLong(resp.header("Content-Length"));
    }

    private static long parseLong(String raw)
    {
        try
        {
            return Long.parseLong(String.valueOf(raw).trim());
        }
        catch (NumberFormatException e)
        {
            return -1L;
        }
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException
    {
        if (in == null)
        {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        while (total < max)
        {
            int read = in.read(buffer, 0, Math.min(buffer.length, max - total));
            if (read < 0)
            {
                break;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }
}

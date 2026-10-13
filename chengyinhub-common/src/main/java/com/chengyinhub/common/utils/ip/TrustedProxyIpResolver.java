package com.chengyinhub.common.utils.ip;

import java.net.Inet6Address;
import java.net.InetAddress;

import javax.servlet.http.HttpServletRequest;

/** Resolves client IP only across the production local-Nginx trust boundary. */
public final class TrustedProxyIpResolver
{
    private TrustedProxyIpResolver()
    {
    }

    public static String resolve(HttpServletRequest request)
    {
        if (request == null)
        {
            return "unknown";
        }
        return resolve(request.getRemoteAddr(), request.getHeader("X-Real-IP"));
    }

    public static String resolve(String remoteAddress, String realIpHeader)
    {
        String peer = normalizeLiteralIp(remoteAddress);
        if (peer == null)
        {
            return "unknown";
        }
        if (isLoopback(peer))
        {
            String forwarded = normalizeLiteralIp(realIpHeader);
            if (forwarded != null)
            {
                return forwarded;
            }
        }
        return peer;
    }

    private static boolean isLoopback(String address)
    {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    private static String normalizeLiteralIp(String value)
    {
        if (value == null)
        {
            return null;
        }
        String candidate = value.trim();
        if (IpUtils.isIP(candidate))
        {
            return candidate;
        }
        if (!candidate.contains(":") || !candidate.matches("[0-9a-fA-F:]+"))
        {
            return null;
        }
        try
        {
            InetAddress address = InetAddress.getByName(candidate);
            return address instanceof Inet6Address ? candidate : null;
        }
        catch (Exception exception)
        {
            return null;
        }
    }
}

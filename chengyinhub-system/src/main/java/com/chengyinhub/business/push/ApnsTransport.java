package com.chengyinhub.business.push;

import java.util.Map;

/** APNs HTTP/2 出口；测试替换成假实现。 */
public interface ApnsTransport
{
    /** @param path 形如 /3/device/{token} */
    ApnsResponse post(String host, String path, Map<String, String> headers, byte[] body) throws Exception;

    record ApnsResponse(int status, String reason) { }
}

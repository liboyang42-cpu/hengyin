package com.chengyinhub.business.service.support;

import com.chengyinhub.common.exception.ServiceException;
import java.util.*;

/** Bounded transport helpers; none of these values is an authentication bypass. */
public final class NativeAppAttestSupport {
    private NativeAppAttestSupport() { }
    public static void fail(String reason) {
        Map<String,Object> data=new LinkedHashMap<>();data.put("reasonCode",reason);
        data.put("provider",NativeStepProtocol.PROVIDER);data.put("rewardEnabled",false);
        throw new ServiceException("设备验证暂不可用，请刷新状态后重试",409).setData(data);
    }
    public static void account(Long id) { if (id == null || id <= 0) fail("APP_ATTEST_ACCOUNT_REQUIRED"); }
    public static void shape(Map<String,Object> body, String... fields) {
        if (body == null || !body.keySet().equals(new HashSet<>(Arrays.asList(fields)))) fail("APP_ATTEST_REQUEST_INVALID");
        if (!NativeStepProtocol.PROVIDER.equals(body.get("provider"))) fail("APP_ATTEST_PROVIDER_UNSUPPORTED");
    }
    public static String string(Map<String,Object> body,String key,int max) {
        Object value=body.get(key);
        if (!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>max) fail("APP_ATTEST_REQUEST_INVALID");
        return (String)value;
    }
    public static String keyId(String key) {
        if (key == null || key.length()!=44) fail("APP_ATTEST_KEY_INVALID");
        decode(key,32,32,"APP_ATTEST_KEY_INVALID");return key;
    }
    public static byte[] decode(String value,int min,int max,String reason) {
        if (value==null || value.length()>(max+2L)/3L*4L) fail(reason);
        try {
            byte[] bytes=Base64.getDecoder().decode(value);
            if(bytes.length<min || bytes.length>max || !Base64.getEncoder().encodeToString(bytes).equals(value)) fail(reason);
            return bytes;
        } catch(IllegalArgumentException malformed) { fail(reason);return null; }
    }
    public static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
}

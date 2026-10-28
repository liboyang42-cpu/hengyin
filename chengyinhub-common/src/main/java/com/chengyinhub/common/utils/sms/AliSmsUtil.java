package com.chengyinhub.common.utils.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阿里云短信工具类(纯参数,不读 config,保持可测)
 *
 * 模板变量名约定为 code,即短信模板内容形如:您的验证码为 ${code}。
 *
 * @author chengyin
 */
public class AliSmsUtil {

    private static final Logger log = LoggerFactory.getLogger(AliSmsUtil.class);

    private AliSmsUtil() {
    }

    /**
     * 发送验证码短信
     *
     * @param accessKeyId     阿里云 accessKeyId
     * @param accessKeySecret 阿里云 accessKeySecret
     * @param signName        短信签名
     * @param templateCode    短信模板 code
     * @param phone           接收手机号
     * @param code            验证码
     * @return 是否发送成功(resp.body.code 等于 "OK")
     */
    public static boolean sendCode(String accessKeyId, String accessKeySecret, String signName,
                                   String templateCode, String phone, String code) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret);
            config.endpoint = "dysmsapi.aliyuncs.com";

            Client client = new Client(config);

            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(signName)
                    .setTemplateCode(templateCode)
                    .setTemplateParam("{\"code\":\"" + code + "\"}");

            SendSmsResponse response = client.sendSms(request);
            if (response != null && response.getBody() != null && "OK".equals(response.getBody().getCode())) {
                return true;
            }

            log.error("阿里云短信发送失败,phone={},code={}",
                    maskPhone(phone), response != null && response.getBody() != null ? response.getBody().getCode() : "null");
            return false;
        } catch (Exception e) {
            log.error("阿里云短信发送异常,phone={},errorType={}", maskPhone(phone), e.getClass().getSimpleName());
            return false;
        }
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}

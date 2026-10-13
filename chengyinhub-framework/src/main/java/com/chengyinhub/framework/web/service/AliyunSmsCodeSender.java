package com.chengyinhub.framework.web.service;

import com.chengyinhub.common.utils.sms.AliSmsUtil;
import org.springframework.stereotype.Component;

@Component
public class AliyunSmsCodeSender implements SmsCodeSender
{
    @Override
    public boolean send(String accessKeyId, String accessKeySecret, String signName,
                        String templateCode, String phone, String code)
    {
        return AliSmsUtil.sendCode(accessKeyId, accessKeySecret, signName, templateCode, phone, code);
    }
}

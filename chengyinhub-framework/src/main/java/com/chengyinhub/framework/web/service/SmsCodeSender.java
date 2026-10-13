package com.chengyinhub.framework.web.service;

/** Sends one verification code through the configured SMS provider. */
public interface SmsCodeSender
{
    boolean send(String accessKeyId, String accessKeySecret, String signName,
                 String templateCode, String phone, String code);
}

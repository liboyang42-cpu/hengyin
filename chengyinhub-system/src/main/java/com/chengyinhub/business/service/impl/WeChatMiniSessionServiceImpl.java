package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * jscode2session 的唯一实现。行为与原 ApiLoginController.getOpenID 逐字一致
 * (同样的错误码判断、同样的"缺 openid 即抛"),登录链路委托过来后表现不变。
 */
@Service
public class WeChatMiniSessionServiceImpl implements IWeChatMiniSessionService {

    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";

    @Autowired
    private ISysConfigService configService;

    @Override
    public WeChatMiniSession exchange(String jsCode) {
        String appid = miniAppId();
        String appsecret = Convert.toStr(configService.selectConfigByKey("app.wechat.appsecret"));

        String url = ENDPOINT + "?appid=" + appid + "&grant_type=authorization_code"
                + "&js_code=" + jsCode + "&secret=" + appsecret;
        String result = sendHttpGet(url);

        Map mp = JSON.parseObject(result);
        if (mp == null) {
            throw new RuntimeException("微信接口返回数据异常，无法解析");
        }
        if (mp.containsKey("errcode")) {
            int errcode = Integer.parseInt(mp.get("errcode").toString());
            String errmsg = String.valueOf(mp.get("errmsg"));
            throw new RuntimeException("微信接口错误: " + errcode + " - " + errmsg);
        }
        if (!mp.containsKey("openid")) {
            throw new RuntimeException("微信接口返回数据异常，缺少openid");
        }
        Object sessionKey = mp.get("session_key");
        return new WeChatMiniSession(mp.get("openid").toString(),
                sessionKey == null ? null : sessionKey.toString());
    }

    @Override
    public String miniAppId() {
        String appid = Convert.toStr(configService.selectConfigByKey("app.wechat.appid"));
        return StringUtils.isBlank(appid) ? "" : appid;
    }

    /** 单独一层好让单测把网络挡在外面 */
    protected String sendHttpGet(String url) {
        return HttpUtils.sendGet(url);
    }
}

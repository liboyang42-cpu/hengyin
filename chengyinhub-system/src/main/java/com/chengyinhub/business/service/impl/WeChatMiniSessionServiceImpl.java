package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;

/**
 * jscode2session 的唯一实现。行为与原 ApiLoginController.getOpenID 逐字一致
 * (同样的错误码判断、同样的"缺 openid 即抛"),登录链路委托过来后表现不变。
 */
@Service
public class WeChatMiniSessionServiceImpl implements IWeChatMiniSessionService {

    private static final String WECHAT_API_BASE = "https://api.weixin.qq.com";

    @Autowired
    private ISysConfigService configService;

    @Autowired(required = false)
    private Environment environment;

    @Override
    public WeChatMiniSession exchange(String jsCode) {
        String appid = miniAppId();
        String appsecret = Convert.toStr(configService.selectConfigByKey("app.wechat.appsecret"));

        String url = sessionBaseUrl() + "/sns/jscode2session?appid=" + appid + "&grant_type=authorization_code"
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

    /**
     * 与 ApiLoginController / WechatReconfirmationService 同一口径：只有显式激活 test/dev/local/preview
     * 且未激活 prod/production 时才读 chengyin.test.wechat-api-base-url；其余一律固定官方基址。
     */
    String sessionBaseUrl() {
        if (environment == null || environment.matchesProfiles("prod", "production")
                || !environment.matchesProfiles("test", "dev", "local", "preview")) {
            return WECHAT_API_BASE;
        }
        String base = environment.getProperty("chengyin.test.wechat-api-base-url", WECHAT_API_BASE).trim();
        URI uri = URI.create(base);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("非生产微信API基址配置不合法");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 单独一层好让单测把网络挡在外面 */
    protected String sendHttpGet(String url) {
        return HttpUtils.sendGet(url);
    }
}

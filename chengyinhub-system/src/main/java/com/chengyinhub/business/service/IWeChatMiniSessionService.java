package com.chengyinhub.business.service;

/**
 * 小程序 code 换会话(jscode2session)。
 *
 * 玩法域(微信运动步数)要 session_key 当场解密(见 WeRunDecryptor),而 session_key
 * 只能留在服务端、绝不下发。
 *
 * ⚠️ 刻意**没有**把 ApiLoginController.getOpenID 改成委托本服务:那边的单测靠 override
 * protected sendHttpGet 来挡网络(ApiLoginControllerHttpContractTest / HttpFailureTest),
 * 一委托那两处 override 就失效、测试会真的发网络请求。登录是高风险链路,本次只补玩法域,
 * 不顺手重构它。⇒ 两边都拼 jscode2session 的 URL,这份重复是已知且刻意的;
 * 哪天要收口,得连那两个测试的挡网络方式一起改。
 */
public interface IWeChatMiniSessionService {

    /** jscode2session 的结果。session_key 只在服务端内部流转,任何接口都不得下发。 */
    class WeChatMiniSession {
        private final String openId;
        private final String sessionKey;

        public WeChatMiniSession(String openId, String sessionKey) {
            this.openId = openId;
            this.sessionKey = sessionKey;
        }

        public String getOpenId() {
            return openId;
        }

        public String getSessionKey() {
            return sessionKey;
        }
    }

    /**
     * 用一次性 js_code 换 openid 与 session_key。
     * @throws RuntimeException 微信返回错误码或缺 openid 时抛,由调用方决定怎么回给用户
     */
    WeChatMiniSession exchange(String jsCode);

    /** 本小程序 appid;微信运动解密要拿它比对 watermark,缺配置时返回空串由调用方拒绝 */
    String miniAppId();
}

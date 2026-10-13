package com.chengyinhub.common.constant;

/**
 * 缓存的key 常量
 * 
 * @author ruoyi
 */
public class CacheConstants
{
    /**
     * APP/小程序登录用户 redis key
     */
    public static final String LOGIN_APP_TOKEN_KEY = "login_app_tokens:";

    /** APP/小程序会员当前会话代次。 */
    public static final String LOGIN_APP_MEMBER_EPOCH_KEY = "login_app_member_epoch:";

    /** APP/小程序会员已登记的 token UUID 集合。 */
    public static final String LOGIN_APP_MEMBER_TOKENS_KEY = "login_app_member_tokens:";

    /** APP/小程序会员认证状态变更门闩，阻止事务提交窗口内签发会话。 */
    public static final String LOGIN_APP_MEMBER_MUTATION_KEY = "login_app_member_mutation:";

    /**
     * 管理后台登录用户 redis key
     */
    public static final String LOGIN_TOKEN_KEY = "login_tokens:";

    /**
     * 验证码 redis key
     */
    public static final String CAPTCHA_CODE_KEY = "captcha_codes:";

    /**
     * 参数管理 cache key
     */
    public static final String SYS_CONFIG_KEY = "sys_config:";

    /**
     * 字典管理 cache key
     */
    public static final String SYS_DICT_KEY = "sys_dict:";

    /**
     * 防重提交 redis key
     */
    public static final String REPEAT_SUBMIT_KEY = "repeat_submit:";

    /**
     * 限流 redis key
     */
    public static final String RATE_LIMIT_KEY = "rate_limit:";

    /**
     * 登录账户密码错误次数 redis key
     */
    public static final String PWD_ERR_CNT_KEY = "pwd_err_cnt:";
}

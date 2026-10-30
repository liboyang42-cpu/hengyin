package com.chengyinhub.web.controller.api;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
//import com.aliyun.tea.TeaException;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.LoginResponseVO;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysDictData;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.DictUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.chengyinhub.common.utils.apple.AppleIdTokenVerifier;
import com.chengyinhub.common.utils.ip.TrustedProxyIpResolver;
import io.jsonwebtoken.Claims;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.chengyinhub.common.utils.file.FileUtils;
import com.chengyinhub.common.utils.file.MimeTypeUtils;
import com.chengyinhub.system.domain.SysBanner;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.ISysDictDataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.chengyinhub.framework.config.ServerConfig;
import com.chengyinhub.system.service.ISysBannerService;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.chengyinhub.framework.web.service.AppTokenService;
import com.chengyinhub.framework.web.service.SmsVerificationService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.domain.UmsMember;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录接口
 *
 * @author webxh
 * @date 2025-08-22
 */
@RestController
//@Api(tags = "01.通用")
@Tag(name = "登录")
@RequestMapping("/api")
public class ApiLoginController extends BaseController {

    private static final String WECHAT_SERVICE_UNAVAILABLE = "微信服务暂时不可用，请稍后重试";

    /**
     * 新用户默认头像:默认留空,由小程序前端兜底显示本地默认图(/images/d_profile.png),
     * 无需依赖任何外部 URL / OSS 桶,避免外链 404。如需服务端下发统一默认头像,
     * 配置 chengyin.default-avatar-url 指向一个可达 URL 即可覆盖。
     */
    @Value("${chengyin.default-avatar-url:}")
    private String defaultAvatarUrl;

    @Resource
    private AppLoginService appLoginService;

    @Resource
    private AppTokenService appTokenService;


    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private IUmsMemberPointsDetailService umsMemberPointsDetailService;

    @Autowired
    private ICmsPointsSettingService cmsPointsSettingService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private SmsVerificationService smsVerificationService;
    @Autowired
    private org.springframework.core.env.Environment environment;
    @Value("${chengyin.test.wechat-api-base-url:https://api.weixin.qq.com}")
    private String wechatApiBaseUrl = "https://api.weixin.qq.com";


    @Operation(summary = "登录")
    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 10,
            limitType = com.chengyinhub.common.enums.LimitType.IP)
    @PostMapping("/login")
    @Parameters({
            @Parameter(name = "username", description = "用户名"),
            @Parameter(name = "password", description = "密码"),
            @Parameter(name = "code", description = "小程序端获取用户的微信code")
    })
    public AjaxResult login(String username, String password, String code) {

        String openid;
        try {
            openid = getOpenID(code);
        } catch (RuntimeException e) {
            return AjaxResult.error(WECHAT_SERVICE_UNAVAILABLE);
        }

        // 生成令牌
        String token = appLoginService.login(username, password, openid);

        AjaxResult ajax = AjaxResult.success();
        if (StringUtils.isNotEmpty(token)) {
            UmsMember umsMember = new UmsMember();
            umsMember.setWxOpenId(openid);
            List<UmsMember> list = umsMemberService.selectUmsMemberList(umsMember);
            if (list.size() > 0) {
                if (list.get(0).getStatus() == 2) {
                    return AjaxResult.error("账号已被冻结");
                }
                if (list.get(0).getDelFlag() == 2) {
                    return AjaxResult.error("账号已删除");
                }
                ajax = AjaxResult.success(LoginResponseVO.from(list.get(0)));
//                if(list.get(0).getUserType()==1){ //用户类型：1普通会员
//                    TuserJobSeekers tuserJobSeekers = new TuserJobSeekers();
//                    tuserJobSeekers.setUserId(Long.parseLong(list.get(0).getId()));
//                    List<TuserJobSeekers> tuserJobSeekersList = tuserJobSeekersService.selectTuserJobSeekersList(tuserJobSeekers);
//                    if(tuserJobSeekersList.size()>0){
//                        list.get(0).setJobSeekers(tuserJobSeekersList.get(0));
//                    }
//                }
//                else{ //2商户会员
//                    TuserRecruiter tuserRecruiter = new TuserRecruiter();
//                    tuserRecruiter.setUserId(Long.parseLong(list.get(0).getId()));
//                    List<TuserRecruiter> tuserRecruiterList = tuserRecruiterService.selectTuserRecruiterList(tuserRecruiter);
//                    if(tuserRecruiterList.size()>0){
//                        list.get(0).setRecruiter(tuserRecruiterList.get(0));
//                    }
//                }
            } else {
                return AjaxResult.error("账号不存在");
            }
        }
        ajax.put(Constants.TOKEN, token);
        return ajax;
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public AjaxResult logout() {
        appTokenService.delLoginAppUser(getLoginAppUser().getToken());
        return success();
    }

    @Operation(summary = "微信code快捷登录")
    @PostMapping("/login/code")
    @Parameters({
            @Parameter(name = "code", description = "小程序端获取用户的微信code")
    })
    public AjaxResult logincode(String code) {
        String openid;
        try {
            openid = getOpenID(code);
        } catch (Exception e) {
            return AjaxResult.error(WECHAT_SERVICE_UNAVAILABLE);
        }
        UmsMember member = umsMemberService.getOrCreateWechatMember(openid);
        if (Long.valueOf(2L).equals(member.getStatus())) {
            return AjaxResult.error("账号已被冻结");
        }
        if (Integer.valueOf(2).equals(member.getDelFlag())) {
            return AjaxResult.error("账号已删除");
        }
        String token = appLoginService.login(openid);
        AjaxResult ajax = AjaxResult.success();
        ajax.put(Constants.TOKEN, token);
        ajax.put("data", LoginResponseVO.from(member));
        return ajax;
    }

    String wechatSessionBaseUrl() {
        if (environment == null || environment.matchesProfiles("prod", "production")
                || !environment.matchesProfiles("test", "dev", "local", "preview")) {
            return "https://api.weixin.qq.com";
        }
        String configured = wechatApiBaseUrl == null ? "" : wechatApiBaseUrl.trim();
        java.net.URI uri = java.net.URI.create(configured);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("非生产微信API基址配置不合法");
        }
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    /**
     * 根据微信code获取个人微信openid
     *
     * @param code 个人微信code
     * @return
     */
    protected String getOpenID(String code) {
        String appid = Convert.toStr(configService.selectConfigByKey("app.wechat.appid"));
        String appsecret = Convert.toStr(configService.selectConfigByKey("app.wechat.appsecret"));

        String url = wechatSessionBaseUrl() + "/sns/jscode2session?appid=" + appid + "&grant_type=authorization_code&js_code=" + code + "&secret=" + appsecret;
        String result = sendHttpGet(url);

        Map mp = JSON.parseObject(result);

        // 先检查是否有错误码
        if (mp.containsKey("errcode")) {
            int errcode = Integer.parseInt(mp.get("errcode").toString());
            String errmsg = mp.get("errmsg").toString();
            throw new RuntimeException("微信接口错误: " + errcode + " - " + errmsg);
        }

        // 确保有openid字段
        if (!mp.containsKey("openid")) {
            throw new RuntimeException("微信接口返回数据异常，缺少openid");
        }

        return mp.get("openid").toString();
    }

    protected String sendHttpGet(String url) {
        return HttpUtils.sendGet(url);
    }

    protected String sendHttpPost(String url, String body) {
        return HttpUtils.sendPost(url, body);
    }

    @Operation(summary = "根据token获取用户信息")
    @PostMapping("/userInfo")
    @Anonymous
    public AjaxResult userInfo() {
        AjaxResult ajax = AjaxResult.success();
        ajax.put("appUser", userInfoView(getLoginAppUser().getAppUser()));
        return ajax;
    }

    /** 会话对象只按白名单输出，口令/盐/查询参数等内部字段不进响应（与 Codex 90883a923e 字段集一致）。 */
    static java.util.Map<String, Object> userInfoView(AppUser appUser) {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        if (appUser == null) {
            return view;
        }
        view.put("createBy", appUser.getCreateBy());
        view.put("createTime", appUser.getCreateTime());
        view.put("updateBy", appUser.getUpdateBy());
        view.put("updateTime", appUser.getUpdateTime());
        view.put("remark", appUser.getRemark());
        view.put("userId", appUser.getUserId());
        view.put("userName", appUser.getUserName());
        view.put("nickName", appUser.getNickName());
        view.put("email", appUser.getEmail());
        view.put("mobile", appUser.getMobile());
        view.put("sex", appUser.getSex());
        view.put("avatar", appUser.getAvatar());
        view.put("status", appUser.getStatus());
        view.put("delFlag", appUser.getDelFlag());
        view.put("loginIp", appUser.getLoginIp());
        view.put("loginDate", appUser.getLoginDate());
        view.put("userType", appUser.getUserType());
        return view;
    }

    // [P0-安全] 已移除 /api/login/withopenid:该端点直接拿客户端传入的 openid 签发 token,
    // 知道任意 openid 即可登录他人账号(越权)。登录统一走 /api/login/code(jscode2session
    // 服务端换取 openid),会话维持由 AppTokenService 的 JWT 滑动续期负责,无需此端点。

    @PostMapping("/getwxbindphone")
    @Operation(summary = "获取微信绑定的手机号")
    @Parameters({
            @Parameter(name = "code", description = "微信code")
    })
    public AjaxResult getPhoneNumber(String code) throws Exception {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请登录");
        }
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();


        String access_token = getWxToken();
        if (StringUtils.isEmpty(access_token)) {
            return AjaxResult.error(WECHAT_SERVICE_UNAVAILABLE);
        }

        String url2 = "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=" + access_token;
        String result2;
        try {
            result2 = sendHttpPost(url2, "{\"code\":\"" + code + "\"}");
        } catch (RuntimeException e) {
            return AjaxResult.error(WECHAT_SERVICE_UNAVAILABLE);
        }
        Map mapTypes = JSON.parseObject(result2);
        if (mapTypes == null) {
            return AjaxResult.error(WECHAT_SERVICE_UNAVAILABLE);
        }
        Object errcodeObj = mapTypes.get("errcode");
        if (errcodeObj != null && errcodeObj.toString().equals("0")) {
            Map mapTypes2 = JSON.parseObject(mapTypes.get("phone_info").toString());
            Object phoneObj = mapTypes2 == null ? null : mapTypes2.get("phoneNumber");
            String phone = phoneObj == null ? "" : phoneObj.toString().trim();

            // 空号保护:微信换号成功但号为空时直接返回失败,绝不往下查重。
            // 否则 UmsMemberMapper 的 <if test="phone!='' "> 会跳过过滤、查出全表,
            // size()>0 误判成"已被其他用户绑定"(本次排障的根因)。
            if (StringUtils.isEmpty(phone)) {
                return AjaxResult.error("获取手机号失败，请重试");
            }

            UmsMember umsMember = new UmsMember();
            umsMember.setPhone(phone);
            List<UmsMember> umsMemberList = umsMemberService.selectUmsMemberList(umsMember);
            if (umsMemberList.size() > 0 && !umsMemberList.get(0).getId().equals(member_id)) {
                return AjaxResult.error("该手机号已被绑定，请联系客服处理");
            }

            umsMember = new UmsMember();
            umsMember.setPhone(phone);
            umsMember.setId(member_id);
            umsMemberService.updateUmsMember(umsMember);

            // 号必须放 data:success(String) 会把 11 位号写进 msg,小程序消毒器会改成「操作失败」
            return AjaxResult.success("操作成功", phone);
        } else {
            // 40029 = 授权码本身无效(开发者工具模拟授权、码已用过):重试同一条路没用,别说「稍后重试」(实走 A4)
            if (errcodeObj != null && "40029".equals(errcodeObj.toString())) {
                return AjaxResult.error("手机号授权无效，请在手机微信里重新授权");
            }
            getWxToken(true);
            return AjaxResult.error("获取手机号失败，请稍后重试");
        }
    }

    protected String getWxToken() throws Exception {
        return getWxToken(false);
    }

    protected String getWxToken(boolean refresh) throws Exception {
        String appid = Convert.toStr(configService.selectConfigByKey("app.wechat.appid"));
        String appsecret = Convert.toStr(configService.selectConfigByKey("app.wechat.appsecret"));
        // 稳定版接口:与 cgi-bin/token 互相隔离,并发/重复获取不会让旧 token 失效(微信官方推荐,根治"时灵时不灵")
        String url = "https://api.weixin.qq.com/cgi-bin/stable_token";
        String access_token = "";

        try {
            if (refresh) {
                redisCache.deleteObject("wx_access_map");
            }

            Map wx_access_map = redisCache.getCacheMap("wx_access_map");
            boolean needFetch = (wx_access_map == null || wx_access_map.size() == 0);
            if (!needFetch) {
                long time = Long.parseLong(wx_access_map.get("time").toString());
                long diff = (DateUtils.getNowDate().getTime() - time) / 1000;
                // 提前 60s 过期刷新
                needFetch = diff > (Long.parseLong(wx_access_map.get("expires_in").toString()) - 60);
            }

            if (needFetch) {
                String body = "{\"grant_type\":\"client_credential\",\"appid\":\"" + appid
                        + "\",\"secret\":\"" + appsecret + "\",\"force_refresh\":" + refresh + "}";
                Map map = JSON.parseObject(sendHttpPost(url, body));
                // 只在拿到真 token 时才缓存,避免把错误响应(额度/secret错误等)缓存住,导致后续都拿空 token
                if (map != null && map.get("access_token") != null) {
                    map.put("time", DateUtils.getNowDate().getTime());
                    redisCache.setCacheMap("wx_access_map", map);
                    access_token = map.get("access_token").toString();
                } else {
                    redisCache.deleteObject("wx_access_map");
                }
            } else {
                access_token = wx_access_map.get("access_token").toString();
            }
        } catch (Exception ex) {
            redisCache.deleteObject("wx_access_map");
        }

        return access_token;
    }

    @PostMapping("/register")
    @Operation(summary = "注册")
    @Parameters({
            @Parameter(name = "name", description = "真实姓名"),
            @Parameter(name = "phone", description = "手机号"),
            @Parameter(name = "smscode", description = "验证码"),
            @Parameter(name = "password", description = "密码"),
            @Parameter(name = "wxcode", description = "微信获取的code")
    })
    public AjaxResult register(String name, String phone, String smscode, String password, String wxcode,
                               HttpServletRequest request) {
        if (StringUtils.isEmpty(phone) || StringUtils.isEmpty(password) || StringUtils.isEmpty(smscode) || StringUtils.isEmpty(wxcode)) {
            return AjaxResult.error("参数有误");
        }

        if (!checkMscode(phone, smscode, request)) {
            return AjaxResult.error("验证码输入有误");
        }

        UmsMember umsMember = new UmsMember();
        umsMember.setPhone(phone);
        List<UmsMember> tuserList = umsMemberService.selectUmsMemberList(umsMember);
        if (tuserList.size() > 0) {
            return AjaxResult.error("注册手机号已经存在");
        }

        String openid;
        try {
            openid = getOpenID(wxcode);
        } catch (RuntimeException e) {
            return AjaxResult.error(WECHAT_SERVICE_UNAVAILABLE);
        }
        // F-L5C-02:注册填的名字会出现在公开资料上,与 /user/update 同一道内容安全闸。
        String nameSecMsg = wxContentSecurityService.checkText(name, openid);
        if (nameSecMsg != null) {
            return AjaxResult.error(nameSecMsg);
        }

        umsMember = new UmsMember();
        umsMember.setName(name);
        umsMember.setUserType(1L); //用户类型：1普通会员 2商户会员
        umsMember.setRole("player"); //RBAC 角色：注册默认玩家
        umsMember.setPhone(phone);
        umsMember.setPassword(password == null ? null : SecurityUtils.encryptPassword(password)); // [P0-安全] 注册即哈希存储
        umsMember.setWxOpenId(openid);
        umsMember.setStatus(1L);
        umsMember.setDelFlag(0);
        int rows = umsMemberService.insertUmsMember(umsMember);
        if (rows > 0) {
            // 生成令牌
            String token = appLoginService.login(phone, password, openid);
            AjaxResult ajax = AjaxResult.success();
            ajax.put(Constants.TOKEN, token);
            return ajax;
        } else {
            return AjaxResult.error("注册失败");
        }


    }


    /**
     * 校验手机验证码
     *
     * @param phone 手机号
     * @param code  验证码
     * @return
     */
    protected boolean checkMscode(String phone, String code, HttpServletRequest request) {
        return smsVerificationService.verify(phone, code, TrustedProxyIpResolver.resolve(request));
    }


    @PostMapping("/sms/send")
    @Operation(summary = "发送手机验证码")
    @Parameters({
            @Parameter(name = "phone", description = "手机号")
    })
    public AjaxResult smsSend(String phone, HttpServletRequest request) {
        SmsVerificationService.SendResult result = smsVerificationService.send(
                phone, TrustedProxyIpResolver.resolve(request));
        return result.isSuccess() ? success(result.getMessage()) : error(result.getMessage());
    }

    @PostMapping("/login/phone")
    @Operation(summary = "手机号验证码登录")
    @Parameters({
            @Parameter(name = "phone", description = "手机号"),
            @Parameter(name = "code", description = "短信验证码")
    })
    public AjaxResult loginPhone(String phone, String code, HttpServletRequest request) {
        if (StringUtils.isEmpty(phone) || !phone.matches("^1\\d{10}$")) {
            return error("手机号格式不正确");
        }
        if (StringUtils.isEmpty(code)) {
            return error("请输入验证码");
        }

        if (!checkMscode(phone, code, request)) {
            return error("验证码错误或已过期");
        }

        UmsMember umsMember = new UmsMember();
        umsMember.setPhone(phone);
        List<UmsMember> list = umsMemberService.selectUmsMemberList(umsMember);

        if (list.size() > 0) {
            UmsMember member = list.get(0);
            if (member.getStatus() != null && member.getStatus() == 2) {
                return AjaxResult.error("账号已被冻结");
            }
            if (member.getDelFlag() != null && member.getDelFlag() == 2) {
                return AjaxResult.error("账号已删除");
            }

            // 生成令牌
            String token = appLoginService.loginByMember(member, "phone", phone, null);

            AjaxResult ajax = AjaxResult.success();
            ajax.put(Constants.TOKEN, token);
            ajax.put("data", LoginResponseVO.from(member));
            return ajax;
        } else {
            // 新建纯手机号用户(参照 logincode 新建分支)
            umsMember = new UmsMember();
            umsMember.setNickname("城瘾用户");
            umsMember.setName("城瘾用户");
            umsMember.setUserType(1L); //用户类型：1普通会员 2商户会员
            umsMember.setRole("player"); //RBAC 角色：注册默认玩家
            umsMember.setPhone(phone);
            umsMember.setPassword("");
            umsMember.setAvatar(defaultAvatarUrl);
            umsMember.setWxOpenId("");
            umsMember.setStatus(1L);
            umsMember.setDelFlag(0);
            umsMember.setPoint(Long.valueOf(50));
            int rows = umsMemberService.insertUmsMember(umsMember);
            if (rows > 0) {
                umsMember = new UmsMember();
                umsMember.setPhone(phone);
                list = umsMemberService.selectUmsMemberList(umsMember);
                UmsMember member = list.get(0);

                // 生成令牌
                String token = appLoginService.loginByMember(member, "phone", phone, null);

                AjaxResult ajax = AjaxResult.success();
                ajax.put(Constants.TOKEN, token);
                ajax.put("data", LoginResponseVO.from(member));

                int points = 0;
                CmsPointsSetting cmsPointsSetting = cmsPointsSettingService.selectCmsPointsSettingById(1L);
                if (cmsPointsSetting != null) {
                    points = cmsPointsSetting.getValue().intValue();
                }

                UmsMemberPointsDetail umsMemberPointsDetail = new UmsMemberPointsDetail();
                umsMemberPointsDetail.setMemberId(member.getId());
                umsMemberPointsDetail.setCurrentPoints(Long.valueOf(points));
                umsMemberPointsDetail.setAfterPoints(Long.valueOf(points));
                umsMemberPointsDetail.setChangePoints(Long.valueOf(points));
                umsMemberPointsDetail.setChangeReason("注册赠送");
                umsMemberPointsDetail.setEventType(Long.valueOf(1));
                umsMemberPointsDetail.setEventId(Long.valueOf(0));
                umsMemberPointsDetail.setChangeType(1L);
                umsMemberPointsDetail.setDelFlag(0);
                umsMemberPointsDetail.setCreateTime(new Date());
                umsMemberPointsDetailService.insertUmsMemberPointsDetail(umsMemberPointsDetail);

                return ajax;
            } else {
                return AjaxResult.error("注册失败");
            }
        }
    }


    @PostMapping("/login/wechat/app")
    @Operation(summary = "原生微信App登录")
    @Parameters({
            @Parameter(name = "code", description = "微信开放平台(原生App)授权返回的 code")
    })
    public AjaxResult loginWechatApp(String code) {
        if (StringUtils.isEmpty(code)) {
            return error("参数有误");
        }

        // 注意:原生 App 走"微信开放平台"的 appid/secret,与小程序的 app.wechat.appid/appsecret 不同 key
        String appid = Convert.toStr(configService.selectConfigByKey("app.wechat.app.appid"));
        String appsecret = Convert.toStr(configService.selectConfigByKey("app.wechat.app.appsecret"));
        if (StringUtils.isEmpty(appid) || StringUtils.isEmpty(appsecret)) {
            return error("原生微信未配置");
        }

        String url = "https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + appid
                + "&secret=" + appsecret + "&code=" + code + "&grant_type=authorization_code";
        String result;
        try {
            result = sendHttpGet(url);
        } catch (RuntimeException e) {
            return AjaxResult.error(WECHAT_SERVICE_UNAVAILABLE);
        }
        Map mp = JSON.parseObject(result);
        if (mp == null) {
            return error("微信接口返回异常");
        }
        if (mp.containsKey("errcode") && !"0".equals(mp.get("errcode").toString())) {
            Object errmsg = mp.get("errmsg");
            return error(errmsg == null ? "微信登录失败" : errmsg.toString());
        }
        if (!mp.containsKey("openid")) {
            return error("微信接口返回数据异常，缺少openid");
        }
        String appOpenid = mp.get("openid").toString();
        // unionid 需在微信开放平台绑定后才会返回;可能为空
        String unionid = mp.get("unionid") == null ? "" : mp.get("unionid").toString();

        // 账号归一,三段查找,顺序不可调换:
        //   ① app_open_id —— App openid 的正确归属列(2026-08-18 起新写入都在这)
        //   ② union_id    —— 小程序与 App 跨应用归一
        //   ③ wx_open_id  —— **仅为兼容存量**:该列曾混存 App openid,
        //                    老 App 用户在被迁移到 app_open_id 之前只能靠它查到。
        // ⚠️ ③ 不能删也不能提前:删了老用户会被当新用户重复建号;
        //    提前则可能把「小程序 openid 恰好等于某 App openid」的极端情况判错。
        UmsMember member = null;
        {
            UmsMember q = new UmsMember();
            q.setAppOpenId(appOpenid);
            List<UmsMember> list = umsMemberService.selectUmsMemberList(q);
            if (list.size() > 0) {
                member = list.get(0);
            }
        }
        if (member == null && StringUtils.isNotEmpty(unionid)) {
            UmsMember q = new UmsMember();
            q.setUnionId(unionid);
            List<UmsMember> list = umsMemberService.selectUmsMemberList(q);
            if (list.size() > 0) {
                member = list.get(0);
            }
        }
        if (member == null) {
            UmsMember q = new UmsMember();
            q.setWxOpenId(appOpenid);
            List<UmsMember> list = umsMemberService.selectUmsMemberList(q);
            if (list.size() > 0) {
                member = list.get(0);
            }
        }

        if (member != null) {
            if (member.getStatus() != null && member.getStatus() == 2) {
                return AjaxResult.error("账号已被冻结");
            }
            if (member.getDelFlag() != null && member.getDelFlag() == 2) {
                return AjaxResult.error("账号已删除");
            }
            // 回填可能新拿到的 unionId(老账号补绑)
            if (StringUtils.isNotEmpty(unionid) && StringUtils.isEmpty(member.getUnionId())) {
                UmsMember upd = new UmsMember();
                upd.setId(member.getId());
                upd.setUnionId(unionid);
                umsMemberService.updateUmsMember(upd);
                member.setUnionId(unionid);
            }
            // 渐进迁移:把 App openid 落到它该在的列。
            // ★ 只补空,**不动 wx_open_id** —— 那一列可能是该用户真实的小程序
            //   openid(双端用户),清掉会让他的小程序侧支付/转账全断。
            //   存量 App 用户由此在「下次 App 登录」后自动获得正确的转账渠道。
            if (StringUtils.isEmpty(member.getAppOpenId())) {
                UmsMember upd = new UmsMember();
                upd.setId(member.getId());
                upd.setAppOpenId(appOpenid);
                umsMemberService.updateUmsMember(upd);
                member.setAppOpenId(appOpenid);
            }

            String token = appLoginService.loginByMember(member, "wechat", appOpenid, unionid);
            AjaxResult ajax = AjaxResult.success();
            ajax.put(Constants.TOKEN, token);
            ajax.put("data", LoginResponseVO.from(member));
            return ajax;
        }

        // 新建用户(参照 logincode 新建分支)
        UmsMember umsMember = new UmsMember();
        umsMember.setNickname("城瘾用户");
        umsMember.setName("城瘾用户");
        umsMember.setUserType(1L); //用户类型：1普通会员 2商户会员
        umsMember.setRole("player"); //RBAC 角色：注册默认玩家
        umsMember.setPhone("");
        umsMember.setPassword("");
        umsMember.setAvatar(defaultAvatarUrl);
        // ★ App openid 写进 app_open_id,**不再塞进 wx_open_id** ——
        //   混存正是 R1/R2 的根因(转账按 (appid,openid) 认人,配错必被拒付)。
        umsMember.setAppOpenId(appOpenid);
        if (StringUtils.isNotEmpty(unionid)) {
            umsMember.setUnionId(unionid);
        }
        umsMember.setStatus(1L);
        umsMember.setDelFlag(0);
        umsMember.setPoint(Long.valueOf(50));
        int rows = umsMemberService.insertUmsMember(umsMember);
        if (rows <= 0) {
            return AjaxResult.error("注册失败");
        }

        // useGeneratedKeys 已回填 id,直接用对象签 token(避免空 unionId/openid 反查越权)
        String token = appLoginService.loginByMember(umsMember, "wechat", appOpenid, unionid);
        AjaxResult ajax = AjaxResult.success();
        ajax.put(Constants.TOKEN, token);
        ajax.put("data", LoginResponseVO.from(umsMember));

        grantRegisterPoints(umsMember.getId());
        return ajax;
    }

    @PostMapping("/login/apple")
    @Operation(summary = "Sign in with Apple 登录")
    @Parameters({
            @Parameter(name = "identityToken", description = "Apple 登录回传的 identityToken(JWT)")
    })
    public AjaxResult loginApple(String identityToken) {
        if (StringUtils.isEmpty(identityToken)) {
            return error("参数有误");
        }

        String clientId = Convert.toStr(configService.selectConfigByKey("apple.clientId"));
        if (StringUtils.isEmpty(clientId)) {
            return error("Apple登录未配置");
        }

        // 校验 identityToken(验签 + iss/aud/exp)
        Claims claims;
        try {
            claims = AppleIdTokenVerifier.verify(identityToken, clientId);
        } catch (Exception e) {
            return error("Apple 身份校验失败");
        }

        String appleId = claims.getSubject();
        if (StringUtils.isEmpty(appleId)) {
            return error("Apple 身份校验失败");
        }
        // email 可空,Apple 仅首次授权回传
        Object emailObj = claims.get("email");
        String email = emailObj == null ? "" : emailObj.toString();

        // 账号:按 appleId 查
        UmsMember q = new UmsMember();
        q.setAppleId(appleId);
        List<UmsMember> list = umsMemberService.selectUmsMemberList(q);

        if (list.size() > 0) {
            UmsMember member = list.get(0);
            if (member.getStatus() != null && member.getStatus() == 2) {
                return AjaxResult.error("账号已被冻结");
            }
            if (member.getDelFlag() != null && member.getDelFlag() == 2) {
                return AjaxResult.error("账号已删除");
            }
            String token = appLoginService.loginByMember(member, "apple", appleId, null);
            AjaxResult ajax = AjaxResult.success();
            ajax.put(Constants.TOKEN, token);
            ajax.put("data", LoginResponseVO.from(member));
            return ajax;
        }

        // 新建用户(参照 logincode 新建分支);email 不当 phone,仅写 mark 备注
        UmsMember umsMember = new UmsMember();
        umsMember.setNickname("城瘾用户");
        umsMember.setName("城瘾用户");
        umsMember.setUserType(1L); //用户类型：1普通会员 2商户会员
        umsMember.setRole("player"); //RBAC 角色：注册默认玩家
        umsMember.setPhone("");
        umsMember.setPassword("");
        umsMember.setAvatar(defaultAvatarUrl);
        umsMember.setWxOpenId("");
        umsMember.setAppleId(appleId);
        if (StringUtils.isNotEmpty(email)) {
            umsMember.setMark(email);
        }
        umsMember.setStatus(1L);
        umsMember.setDelFlag(0);
        umsMember.setPoint(Long.valueOf(50));
        int rows = umsMemberService.insertUmsMember(umsMember);
        if (rows <= 0) {
            return AjaxResult.error("注册失败");
        }

        String token = appLoginService.loginByMember(umsMember, "apple", appleId, null);
        AjaxResult ajax = AjaxResult.success();
        ajax.put(Constants.TOKEN, token);
        ajax.put("data", LoginResponseVO.from(umsMember));

        grantRegisterPoints(umsMember.getId());
        return ajax;
    }

    /**
     * 写注册赠送积分明细(与 logincode/loginPhone 同款逻辑,抽出复用)。
     * 注:50 分已在新建用户时通过 setPoint 落库,此处仅记积分明细流水。
     *
     * @param memberId 新用户ID
     */
    private void grantRegisterPoints(Long memberId) {
        int points = 0;
        CmsPointsSetting cmsPointsSetting = cmsPointsSettingService.selectCmsPointsSettingById(1L);
        if (cmsPointsSetting != null) {
            points = cmsPointsSetting.getValue().intValue();
        }

        UmsMemberPointsDetail umsMemberPointsDetail = new UmsMemberPointsDetail();
        umsMemberPointsDetail.setMemberId(memberId);
        umsMemberPointsDetail.setCurrentPoints(Long.valueOf(points));
        umsMemberPointsDetail.setAfterPoints(Long.valueOf(points));
        umsMemberPointsDetail.setChangePoints(Long.valueOf(points));
        umsMemberPointsDetail.setChangeReason("注册赠送");
        umsMemberPointsDetail.setEventType(Long.valueOf(1));
        umsMemberPointsDetail.setEventId(Long.valueOf(0));
        umsMemberPointsDetail.setChangeType(1L);
        umsMemberPointsDetail.setDelFlag(0);
        umsMemberPointsDetail.setCreateTime(new Date());
        umsMemberPointsDetailService.insertUmsMemberPointsDetail(umsMemberPointsDetail);
    }

    @PostMapping("/findpass")
    @Operation(summary = "找回密码")
    @Parameters({
            @Parameter(name = "phone", description = "手机号"),
            @Parameter(name = "smscode", description = "验证码"),
            @Parameter(name = "password", description = "新密码")
    })
    public AjaxResult findpassword(String phone, String smscode, String password, HttpServletRequest request) {
        if (StringUtils.isEmpty(phone) || StringUtils.isEmpty(password) || StringUtils.isEmpty(smscode)) {
            return AjaxResult.error("参数有误");
        }

        if (!checkMscode(phone, smscode, request)) {
            return AjaxResult.error("验证码输入有误");
        }

        UmsMember umsMember = new UmsMember();
        umsMember.setPhone(phone);
        List<UmsMember> tuserList = umsMemberService.selectUmsMemberList(umsMember);
        if (tuserList.size() > 0) {
            tuserList.get(0).setPassword(password == null ? null : SecurityUtils.encryptPassword(password)); // [P0-安全] 改密即哈希存储
            umsMemberService.updateUmsMember(tuserList.get(0));
        }

        return success();
    }
}

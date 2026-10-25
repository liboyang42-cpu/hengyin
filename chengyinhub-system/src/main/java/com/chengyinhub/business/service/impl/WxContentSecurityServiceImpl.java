package com.chengyinhub.business.service.impl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.RoamStamp;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.mapper.RoamStampMapper;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.util.TopicContentSafetyHold;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.service.ISysConfigService;

/**
 * 微信内容安全 Service 实现。
 *
 * 配置与凭据统一复用 {@link WxPayConfig}（与 Comment / CreativeSquare 控制器现有的
 * checkTextSecurity 同一开关 wx 配置 secCheckEnabled / miniAppId / miniSecret），
 * access_token 复用同一 redis key（wx:mini:access_token），避免多份开关与多次取 token。
 *
 * 与控制器内私有 checkTextSecurity 的区别：本实现用 msgSecCheck v2（带 openid + suggest 判定），
 * 是更推荐的中心化版本。后续可把 Comment / CreativeSquare 的私有方法迁移到这里统一收口。
 */
@Service
public class WxContentSecurityServiceImpl implements IWxContentSecurityService
{
    private static final long CALLBACK_TIMESTAMP_WINDOW_SECONDS = 300L;
    private static final Logger log = LoggerFactory.getLogger(WxContentSecurityServiceImpl.class);

    /** access_token 在 redis 中的 key（与控制器现有实现一致，共享同一份 token） */
    private static final String WX_ACCESS_TOKEN_KEY = "wx:mini:access_token";
    private static final String WX_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String WX_MSG_SEC_CHECK_URL = "https://api.weixin.qq.com/wxa/msg_sec_check";
    private static final String WX_MEDIA_CHECK_URL = "https://api.weixin.qq.com/wxa/media_check_async";

    /** trace_id → "bizType:bizId" 映射在 redis 中的 key 前缀 */
    private static final String MEDIA_TRACE_PREFIX = "wx:mediacheck:trace:";
    private static final String CALLBACK_NONCE_PREFIX = "wx:content-sec:callback:nonce:";
    private static final int CALLBACK_NONCE_TTL_MINUTES = 10;
    private static final int HTTP_TIMEOUT_MILLIS = 5000;

    @Autowired
    private WxPayConfig wxConfig;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private IWxContentCallbackInboxService callbackInboxService;

    private Clock clock = Clock.systemUTC();

    @Autowired
    @Lazy
    private ICmsTopicService cmsTopicService;

    @Autowired
    @Lazy
    private ICmsActivityService cmsActivityService;

    @Autowired
    @Lazy
    private IClubPostService clubPostService;

    @Autowired
    private ImMessageMapper imMessageMapper;
    // 附近的局:封面违规 → 隐藏 + 关群。ImServiceImpl 反向依赖本服务,用 ObjectProvider 懒取破环,关群逻辑只在 IImService 一份
    @Autowired
    private RoamHangoutMapper roamHangoutMapper;
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IImService> imServiceProvider;
    // 模板封面/勋章图(BIZ_TYPE_TEMPLATE):bizId = cms_member_template.id;公开读路径只读 cms_template_library 投影
    @Autowired
    private com.chengyinhub.business.mapper.CmsMemberTemplateMapper cmsMemberTemplateMapper;
    @Autowired
    private com.chengyinhub.business.mapper.CmsTemplateLibraryMapper cmsTemplateLibraryMapper;
    @Autowired
    private com.chengyinhub.business.mapper.AuditTaskMapper auditTaskMapper;

    /** 申诉单撤回走审核服务(同一处通知作者);ObjectProvider 破环。 */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IAuditTaskService> auditTaskServiceProvider;

    /** 内容安全下架关闭未结申诉时给作者的说明。 */
    static final String APPEAL_CLOSED_BY_SAFETY = "图片未通过内容安全检测已被下架，原申诉已关闭；请更换图片后重新提交审核";
    // 社区帖文图片:命中 → 帖文生命周期 HIDE。CommunityPostServiceImpl 反向依赖本服务(送检),ObjectProvider 懒取破环
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.ICommunityPostService> communityPostServiceProvider;

    @Autowired
    private RoamStampMapper roamStampMapper;

    @Autowired
    private com.chengyinhub.business.mapper.UmsMemberMapper umsMemberMapper;

    @Autowired
    private com.chengyinhub.business.mapper.ClubMapper clubMapper;

    @Autowired
    private com.chengyinhub.business.mapper.MmsMerchantMapper mmsMerchantMapper;

    @Autowired
    private com.chengyinhub.business.service.IUmsCreativeSquareService umsCreativeSquareService;

    @Autowired
    private com.chengyinhub.business.service.IUmsCommentService umsCommentService;

    /** 主题节点 NPC 头像(scope_type=4)。bizId = npc_profile.id,不是 merchantId —— 见 BIZ_TYPE_NODE_NPC 注释。 */
    @Autowired
    private com.chengyinhub.business.mapper.NpcProfileMapper npcProfileMapper;

    @Autowired
    private com.chengyinhub.business.mapper.OfficialEventMapper officialEventMapper;

    @Override
    public boolean isEnabled()
    {
        return wxConfig.isSecCheckEnabled();
    }

    @Override
    public String checkText(String content, String openid)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (StringUtils.isEmpty(content))
        {
            return null;
        }

        if (!isEnabled())
        {
            // F-L5C-07:总开关关闭 = 机审缺位,拒写而不是放行;ERROR 让关闭状态可被发现。
            log.error("[内容安全] 文本检测总开关已关闭(wx.sec-check.enabled=false),fail-close 拦截");
            return MSG_UNAVAILABLE;
        }

        // msgSecCheck v2 的 openid 必填:为空微信必判 40003(invalid openid)。当场 fail-close,
        // 别把调用方 bug 伪装成「服务繁忙,请稍后重试」——重试永远不会通,用户还会以为再试就好。
        if (StringUtils.isBlank(openid))
        {
            log.error("[内容安全] 文本检测缺少 openid(v2 必填,微信必判 40003),fail-close 拦截");
            return MSG_UNAVAILABLE;
        }

        try
        {
            String accessToken = getAccessToken(false);
            if (StringUtils.isEmpty(accessToken))
            {
                // C4 fail-close:拿不到 token 视为审核不可用,拦截并提示重试(合规优先,不放违规内容上线;运营可关 secCheckEnabled 总开关降级)
                log.error("[内容安全] access_token 获取失败,文本检测 fail-close 拦截");
                // 合并 #1057×master(RUN-29 语义):「审不了」回 MSG_BUSY,master 侧调用方按
                // isDetectionUnavailable 判「繁忙 ≠ 违规」;真 suggest=review 才回 REVIEW_REQUIRED。
                return MSG_BUSY;
            }

            JSONObject body = new JSONObject();
            body.put("content", content);
            body.put("version", 2);
            // scene: 1 资料 2 评论 3 论坛 4 社交日志；UGC 发帖按论坛处理
            body.put("scene", 3);
            if (StringUtils.isNotEmpty(openid))
            {
                body.put("openid", openid);
            }

            String result = sendPost(WX_MSG_SEC_CHECK_URL + "?access_token=" + accessToken,
                    body.toJSONString(), "application/json");
            Map<String, Object> resp = JSON.parseObject(result);
            if (resp == null)
            {
                // C4 fail-close:无响应视为审核不可用,拦截
                log.error("[内容安全] 文本检测无响应,fail-close 拦截。contentLength={}", content.length());
                // 合并 #1057×master(RUN-29 语义):「审不了」回 MSG_BUSY,master 侧调用方按
                // isDetectionUnavailable 判「繁忙 ≠ 违规」;真 suggest=review 才回 REVIEW_REQUIRED。
                return MSG_BUSY;
            }

            int errcode = Convert.toInt(resp.get("errcode"), -1);

            // token 过期（40001/42001）→ 强制刷新后重试一次
            if (errcode == 40001 || errcode == 42001)
            {
                accessToken = getAccessToken(true);
                result = sendPost(WX_MSG_SEC_CHECK_URL + "?access_token=" + accessToken,
                        body.toJSONString(), "application/json");
                resp = JSON.parseObject(result);
                errcode = resp == null ? -1 : Convert.toInt(resp.get("errcode"), -1);
            }

            // 87014 命中违规；其余非 0 错误码容错放行并告警
            if (errcode == 87014)
            {
                return "内容包含违规信息，请修改后重试";
            }
            if (errcode != 0)
            {
                // C4 fail-close:检测接口异常视为不可用,拦截。
                // 40003(invalid openid)是我们调用方的参数错误,不是「服务繁忙」:重试无用,文案要分开。
                if (errcode == 40003)
                {
                    log.error("[内容安全] 文本检测调用方参数错误 errcode={}, fail-close 拦截(非繁忙,重试无用)", errcode);
                    return MSG_UNAVAILABLE;
                }
                log.error("[内容安全] 文本检测返回异常 errcode={}, fail-close 拦截", errcode);
                // 合并 #1057×master(RUN-29 语义):「审不了」回 MSG_BUSY,master 侧调用方按
                // isDetectionUnavailable 判「繁忙 ≠ 违规」;真 suggest=review 才回 REVIEW_REQUIRED。
                return MSG_BUSY;
            }

            // errcode==0：再看 result.suggest（risky=拦截，review=人工复审，pass=通过）
            Object resultObj = resp.get("result");
            if (resultObj == null)
            {
                log.warn("[内容安全] 文本检测成功包缺少 result，转人工复审。contentLength={}", content.length());
                return IWxContentSecurityService.REVIEW_REQUIRED;
            }
            Map<String, Object> r = JSON.parseObject(JSON.toJSONString(resultObj));
            String suggest = Convert.toStr(r.get("suggest"), "");
            if ("pass".equalsIgnoreCase(suggest)) return null;
            if ("risky".equalsIgnoreCase(suggest))
            {
                return "内容包含违规信息，请修改后重试";
            }
            log.info("[内容安全] 文本需人工复审，suggest={}，contentLength={}", suggest, content.length());
            return IWxContentSecurityService.REVIEW_REQUIRED;
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (TokenConfigurationException e)
        {
            return MSG_UNAVAILABLE;
        }
        catch (Exception e)
        {
            // C4 fail-close:任何异常视为审核不可用,拦截并提示重试(合规优先);运营可临时关 secCheckEnabled 总开关降级
            log.error("[内容安全] 文本检测异常，fail-close 拦截。exceptionType={}",
                    e.getClass().getSimpleName());
            // 合并 #1057×master(RUN-29 语义):「审不了」回 MSG_BUSY,master 侧调用方按
            // isDetectionUnavailable 判「繁忙 ≠ 违规」;真 suggest=review 才回 REVIEW_REQUIRED。
            return MSG_BUSY;
        }
    }

    @Override
    public String checkMediaAsync(String mediaUrl, String openid)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!isEnabled() || StringUtils.isBlank(mediaUrl) || StringUtils.isBlank(openid))
        {
            return null;
        }
        try
        {
            String accessToken = getAccessToken(false);
            if (StringUtils.isEmpty(accessToken))
            {
                return null;
            }

            JSONObject body = new JSONObject();
            body.put("media_url", mediaUrl);
            body.put("media_type", 2); // 1 音频 2 图片
            body.put("version", 2);
            body.put("scene", 3);
            if (StringUtils.isNotEmpty(openid))
            {
                body.put("openid", openid);
            }

            String result = sendPost(WX_MEDIA_CHECK_URL + "?access_token=" + accessToken,
                    body.toJSONString(), "application/json");
            Map<String, Object> resp = JSON.parseObject(result);
            if (resp == null)
            {
                return null;
            }
            int errcode = Convert.toInt(resp.get("errcode"), -1);
            if (errcode == 40001 || errcode == 42001)
            {
                accessToken = getAccessToken(true);
                result = sendPost(WX_MEDIA_CHECK_URL + "?access_token=" + accessToken,
                        body.toJSONString(), "application/json");
                resp = JSON.parseObject(result);
                errcode = resp == null ? -1 : Convert.toInt(resp.get("errcode"), -1);
            }
            if (errcode != 0)
            {
                log.warn("[内容安全] 图片送检失败 errcode={}, mediaLength={}",
                        errcode, mediaUrl.length());
                return null;
            }
            // 送检成功，返回 trace_id；最终结果由微信经「消息推送」异步回调（监听 wxa_media_check 事件）
            return Convert.toStr(resp.get("trace_id"));
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (Exception e)
        {
            log.error("[内容安全] 图片送检异常。exceptionType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void submitMediaCheck(int bizType, Long bizId, String imgArr, String openid)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!isEnabled() || bizId == null || StringUtils.isEmpty(imgArr))
        {
            return;
        }
        // 图片地址串支持逗号或分号分隔
        String[] imgs = imgArr.split("[,;]");
        for (String img : imgs)
        {
            if (img == null || img.trim().length() == 0)
            {
                continue;
            }
            submitMediaCheckOne(bizType, bizId, img, openid);
        }
    }

    @Override
    public String submitMediaCheckOne(int bizType, Long bizId, String imgUrl, String openid)
    {
        return submitMediaCheckOne(bizType, bizId, imgUrl, imgUrl, openid);
    }

    @Override
    public String submitMediaCheckOne(int bizType, Long bizId, String fetchUrl, String identity, String openid)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!isEnabled() || bizId == null || StringUtils.isEmpty(fetchUrl) || StringUtils.isEmpty(identity))
        {
            return null;
        }
        String img = fetchUrl.trim();
        try
        {
            String traceId = checkMediaAsync(img, openid);
            if (StringUtils.isNotEmpty(traceId))
            {
                // MySQL inbox 是回调业务 mutation 的幂等/fencing 权威。登记失败不能只写 Redis 假装可处理。
                if (!callbackInboxService.register(traceId, bizType, bizId, identity.trim()))
                {
                    log.error("[内容安全] 图片送检 inbox 登记失败，拒绝返回 trace: bizType={}, bizId={}", bizType, bizId);
                    return null;
                }
                // MySQL inbox 已是权威回执；Redis 仅兼容旧映射，失败不得抹掉已提交的送审结果。
                try
                {
                    redisCache.setCacheObject(MEDIA_TRACE_PREFIX + traceId,
                            bizType + ":" + bizId, 2, TimeUnit.DAYS);
                }
                catch (RuntimeException redisFailure)
                {
                    log.warn("[内容安全] 辅助 Redis trace 映射失败，MySQL inbox 已登记: bizType={}, bizId={}",
                            bizType, bizId);
                }
                return traceId;
            }
            // C4:图片未送检成功(token/接口异常),该图逃过内容安全异步回调,无法同步 fail-close;ERROR 落日志供人工复审下架
            log.error("[内容安全] 图片未送检成功,逃过内容安全回调,需人工复审: bizType={}, bizId={}, mediaLength={}",
                    bizType, bizId, img.length());
            return null;
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (Exception e)
        {
            // C4:送检异常不可静默,ERROR 供人工复审(异步模型不阻断已发布内容,但必须可发现)
            log.error("[内容安全] 图片送检异常,需人工复审: bizType={}, bizId={}, mediaLength={}, exceptionType={}",
                    bizType, bizId, img.length(), e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean handleMediaCheckResult(String traceId, boolean risky)
    {
        if (StringUtils.isEmpty(traceId))
        {
            return false;
        }
        if (!ensureCallbackInboxRegistration(traceId))
        {
            return false;
        }
        boolean handled = callbackInboxService.process(traceId, risky,
                new IWxContentCallbackInboxService.Mutation()
                {
                    @Override
                    public boolean isCurrent(WxContentCallbackInbox inbox)
                    {
                        return isCurrentSubmittedMedia(inbox);
                    }

                    @Override
                    public boolean apply(WxContentCallbackInbox inbox, boolean callbackRisky)
                    {
                        if (callbackRisky && Integer.valueOf(BIZ_TYPE_MEMBER_PROFILE).equals(inbox.getBizType()))
                        {
                            // 资料图按「哪张图违规清哪个字段」处理:二维码违规不连坐作品图,反之亦然。
                            return clearRiskyMemberProfileMedia(inbox.getBizId(), inbox.getMediaHash());
                        }
                        return processMediaCheckResult(inbox.getBizType(), inbox.getBizId(), traceId,
                                callbackRisky);
                    }
                });
        if (handled)
        {
            deleteMediaTraceMapping(traceId);
        }
        return handled;
    }

    @Override
    public CallbackResult processMediaCheckCallback(String signature, String timestamp, String nonce, String body)
    {
        CallbackResult verification = verifyMediaCheckCallback(signature, timestamp, nonce);
        if (verification != CallbackResult.SUCCESS)
        {
            return verification;
        }
        if (StringUtils.isEmpty(body))
        {
            return CallbackResult.INVALID_PAYLOAD;
        }
        String traceId;
        boolean risky;
        try
        {
            JSONObject obj = JSON.parseObject(body);
            if (obj == null
                    || !"wxa_media_check".equalsIgnoreCase(obj.getString("Event"))
                    || StringUtils.isEmpty(obj.getString("trace_id"))
                    || !obj.containsKey("errcode")
                    || Convert.toInt(obj.get("errcode"), -1) != 0)
            {
                return CallbackResult.INVALID_PAYLOAD;
            }
            JSONObject result = obj.getJSONObject("result");
            String suggest = result == null ? "" : Convert.toStr(result.get("suggest"), "");
            if (!"pass".equalsIgnoreCase(suggest) && !"risky".equalsIgnoreCase(suggest))
            {
                return CallbackResult.INVALID_PAYLOAD;
            }
            traceId = obj.getString("trace_id");
            risky = "risky".equalsIgnoreCase(suggest);
        }
        catch (Exception e)
        {
            return CallbackResult.INVALID_PAYLOAD;
        }
        // 只有所有无副作用校验完成后才占 nonce；失败仅由 owner 原子释放以允许微信重试。
        String nonceOwner = tryAcquireCallbackNonce(nonce);
        if (StringUtils.isEmpty(nonceOwner))
        {
            return CallbackResult.REPLAYED;
        }
        try
        {
            if (handleMediaCheckResult(traceId, risky))
            {
                return CallbackResult.SUCCESS;
            }
        }
        catch (Exception e)
        {
            log.error("[内容安全回调] 业务处理异常，未确认成功");
        }
        releaseCallbackNonce(nonce, nonceOwner);
        return CallbackResult.PROCESSING_FAILED;
    }

    @Override
    public CallbackResult verifyMediaCheckCallback(String signature, String timestamp, String nonce)
    {
        String token = Convert.toStr(configService.selectConfigByKey("wx.msgpush.token"), "");
        if (StringUtils.isEmpty(token))
        {
            log.error("[内容安全回调] 消息推送 token 未配置，拒绝回调");
            return CallbackResult.TOKEN_UNAVAILABLE;
        }
        if (!checkCallbackSignature(token, signature, timestamp, nonce))
        {
            return CallbackResult.FORBIDDEN;
        }
        return isCallbackTimestampFresh(timestamp) ? CallbackResult.SUCCESS : CallbackResult.INVALID_TIMESTAMP;
    }

    private boolean checkCallbackSignature(String token, String signature, String timestamp, String nonce)
    {
        if (StringUtils.isEmpty(signature) || StringUtils.isEmpty(timestamp) || StringUtils.isEmpty(nonce))
        {
            return false;
        }
        try
        {
            String[] values = new String[] { token, timestamp, nonce };
            Arrays.sort(values);
            StringBuilder source = new StringBuilder();
            for (String value : values)
            {
                source.append(value);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes)
            {
                String part = Integer.toHexString(value & 0xff);
                if (part.length() == 1)
                {
                    hex.append('0');
                }
                hex.append(part);
            }
            return hex.toString().equalsIgnoreCase(signature);
        }
        catch (Exception e)
        {
            log.error("[内容安全回调] 签名计算异常");
            return false;
        }
    }

    private boolean isCallbackTimestampFresh(String timestamp)
    {
        try
        {
            long callbackTime = Long.parseLong(timestamp);
            long now = clock.instant().getEpochSecond();
            return callbackTime >= now - CALLBACK_TIMESTAMP_WINDOW_SECONDS
                    && callbackTime <= now + CALLBACK_TIMESTAMP_WINDOW_SECONDS;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private boolean processMediaCheckResult(int bizType, Long bizId, String traceId, boolean risky)
    {
        return processMappedMediaCheckResult(bizType + ":" + bizId, traceId, risky);
    }

    private boolean processMappedMediaCheckResult(String mapping, String traceId, boolean risky)
    {

        // ⚠️ 集邮票必须在下面那句「检测通过直接返回」之前分流:它 pass 也要回写 check_state=1。
        // 沿用早返回的话,「机审通过」与「压根没送检」在库里同为 0,永远分不开。
        // 其余业务维持原语义(通过=无需处理),这里不碰它们的解析与流程。
        if (mapping.startsWith(BIZ_TYPE_ROAM_STAMP + ":"))
        {
            return handleRoamStampCheckResult(mapping, risky);
        }
        if (mapping.startsWith(BIZ_TYPE_IM_MESSAGE + ":"))
        {
            try
            {
                String[] parts = mapping.split(":");
                return handleImMediaCheckResult(Long.parseLong(parts[1]), traceId, risky);
            }
            catch (Exception e)
            {
                return false;
            }
        }

        if (!risky)
        {
            // 检测通过，无需处理
            return true;
        }

        try
        {
            String[] parts = mapping.split(":");
            int bizType = Integer.parseInt(parts[0]);
            Long bizId = Long.parseLong(parts[1]);
            // 命中违规：自动下架对应内容（可逆，仅改状态，不删数据）
            if (bizType == BIZ_TYPE_TOPIC)
            {
                CmsTopic current = cmsTopicService.selectCmsTopicById(bizId);
                if (current == null)
                {
                    return false;
                }
                // AUDIT-INT-7:无论是否已下架/已被人工驳回都落留置标记,申诉/撤销不能凭它恢复违规图。
                cmsTopicService.markContentSafetyHold(bizId);
                if (topicTakedownApplied(current))
                {
                    return confirmAlreadyApplied("主题图片违规已自动下架", bizId);
                }
                CmsTopic t = new CmsTopic();
                t.setId(bizId);
                t.setUserStatus(0); // 0=下架
                // AUDIT-G2-1:记下「为什么下架」(check_status=2 + 固定原因),作者不能再自助翻回上架,
                // 须人工复核通过才解除(见 TopicContentSafetyHold)。人工已驳回(status=2)的不改写其驳回原因。
                if (!Integer.valueOf(2).equals(current.getStatus()))
                {
                    t.setCheckStatus(2);
                    t.setReason(TopicContentSafetyHold.REASON);
                }
                int affected = cmsTopicService.updateCmsTopic(t);
                if (affected <= 0)
                {
                    CmsTopic observed = cmsTopicService.selectCmsTopicById(bizId);
                    return topicTakedownApplied(observed)
                            ? confirmAlreadyApplied("主题图片违规已自动下架", bizId)
                            : confirmMutation(affected, "主题图片违规已自动下架", bizId);
                }
                // [6-09] 内容安全自动下架 = 不公开:同步帖跟着收起,违规图片不能继续在广场露出。
                cmsTopicService.syncCreativeSquarePostVisibility(bizId, false);
                notifyImageTakedown(current.getMemberId(), com.chengyinhub.business.domain.AuditTask.BIZ_TOPIC, bizId,
                        com.chengyinhub.business.service.support.SystemNoticeCard.item("主题", current.getName()));
                return confirmMutation(affected, "主题图片违规已自动下架", bizId);
            }
            else if (bizType == BIZ_TYPE_ACTIVITY)
            {
                CmsActivity current = cmsActivityService.selectCmsActivityById(bizId);
                if (current == null)
                {
                    return false;
                }
                // AUDIT-INT-7:已下架/已驳回的活动也落留置标记,申诉成立或撤销驳回不能把违规图放回去。
                cmsActivityService.markContentSafetyHold(bizId);
                if (Long.valueOf(0L).equals(current.getPublishStatus()))
                {
                    return confirmAlreadyApplied("活动图片违规已自动下架", bizId);
                }
                CmsActivity a = new CmsActivity();
                a.setId(bizId);
                a.setPublishStatus(0L); // 0=下架中
                int affected = cmsActivityService.updateCmsActivity(a);
                if (affected <= 0)
                {
                    CmsActivity observed = cmsActivityService.selectCmsActivityById(bizId);
                    return observed != null && Long.valueOf(0L).equals(observed.getPublishStatus())
                            ? confirmAlreadyApplied("活动图片违规已自动下架", bizId)
                            : confirmMutation(affected, "活动图片违规已自动下架", bizId);
                }
                notifyImageTakedown(current.getMemberId(), com.chengyinhub.business.domain.AuditTask.BIZ_ACTIVITY, bizId,
                        com.chengyinhub.business.service.support.SystemNoticeCard.item("活动", current.getName()));
                return confirmMutation(affected, "活动图片违规已自动下架", bizId);
            }
            else if (bizType == BIZ_TYPE_CLUB_POST)
            {
                // 软删 SQL 不带 del_flag 条件(驱动按 found rows 计数,已删也回 1),先读下架前状态判断本次是否真正下架。
                ClubPost before = clubPostService.selectClubPostByIdForUpdate(bizId);
                int affected = clubPostService.deleteClubPostById(bizId); // del_flag=2 软下架(可逆)
                if (affected <= 0)
                {
                    ClubPost observed = clubPostService.selectClubPostById(bizId);
                    return observed != null && Integer.valueOf(2).equals(observed.getDelFlag())
                            ? confirmAlreadyApplied("俱乐部动态图片违规已自动下架", bizId)
                            : confirmMutation(affected, "俱乐部动态图片违规已自动下架", bizId);
                }
                if (before != null && !Integer.valueOf(2).equals(before.getDelFlag()))
                {
                    notifyImageTakedown(before.getAuthorMemberId(), com.chengyinhub.business.domain.AuditTask.BIZ_CLUB_POST, bizId,
                            com.chengyinhub.business.service.support.SystemNoticeCard.item("俱乐部动态", before.getContent()));
                }
                return confirmMutation(affected, "俱乐部动态图片违规已自动下架", bizId);
            }
            else if (bizType == BIZ_TYPE_MEMBER_PROFILE)
            {
                int affected = umsMemberMapper.clearProfileMedia(bizId); // 清头像/案例图(违规图必去,合规图需用户重传)
                return confirmMutation(affected, "用户资料图片违规已自动清除", bizId);
            }
            else if (bizType == BIZ_TYPE_CLUB_PROFILE)
            {
                int affected = clubMapper.clearProfileMedia(bizId);
                return confirmMutation(affected, "俱乐部资料图片违规已自动清除", bizId);
            }
            else if (bizType == BIZ_TYPE_MERCHANT_PROFILE)
            {
                int affected = mmsMerchantMapper.clearProfileMedia(bizId);
                return confirmMutation(affected, "商家资料图片违规已自动清除", bizId);
            }
            else if (bizType == BIZ_TYPE_NODE_NPC)
            {
                // 不按 bizId 清商家图:这里 bizId 是 npc_profile.id(见接口注释)。
                // 清头像 + 停用一步到位 —— 玩家读闸是 audit_status=1 AND enabled=1,停了就立刻不可见。
                int affected = npcProfileMapper.hideNodeNpcById(bizId);
                if (affected <= 0)
                {
                    com.chengyinhub.business.domain.NpcProfile observed =
                            npcProfileMapper.selectNodeNpcById(bizId);
                    return observed != null && Integer.valueOf(0).equals(observed.getEnabled())
                            ? confirmAlreadyApplied("节点NPC头像违规已自动下架", bizId)
                            : confirmMutation(affected, "节点NPC头像违规已自动下架", bizId);
                }
                return confirmMutation(affected, "节点NPC头像违规已自动下架", bizId);
            }
            else if (bizType == BIZ_TYPE_MERCHANT_NPC)
            {
                // bizId = npc_profile.id(scope_type=2)。只打回驳回态,不删图:玩家读闸 audit_status=1 立即失效,
                // 商家端能看到驳回原因并换图重存。
                int affected = npcProfileMapper.rejectMerchantNpcByContentSecurity(bizId);
                if (affected <= 0)
                {
                    com.chengyinhub.business.domain.NpcProfile observed = npcProfileMapper.selectMerchantNpcById(bizId);
                    return observed != null && Integer.valueOf(2).equals(observed.getAuditStatus())
                            ? confirmAlreadyApplied("门店形象头像违规已打回", bizId)
                            : confirmMutation(affected, "门店形象头像违规已打回", bizId);
                }
                return confirmMutation(affected, "门店形象头像违规已打回", bizId);
            }
            else if (bizType == BIZ_TYPE_SQUARE)
            {
                com.chengyinhub.business.domain.UmsCreativeSquare current =
                        umsCreativeSquareService.selectUmsCreativeSquareById(bizId);
                if (current == null)
                {
                    return false;
                }
                if (Integer.valueOf(2).equals(current.getStatus()))
                {
                    return confirmAlreadyApplied("广场帖图片违规已自动下架", bizId);
                }
                com.chengyinhub.business.domain.UmsCreativeSquare s = new com.chengyinhub.business.domain.UmsCreativeSquare();
                s.setId(bizId);
                s.setStatus(2); // 2=下架/驳回(列表恒 status=1 过滤,自动隐藏;可逆,仅改状态)
                int affected = umsCreativeSquareService.updateUmsCreativeSquare(s);
                if (affected <= 0)
                {
                    com.chengyinhub.business.domain.UmsCreativeSquare observed =
                            umsCreativeSquareService.selectUmsCreativeSquareById(bizId);
                    return observed != null && Integer.valueOf(2).equals(observed.getStatus())
                            ? confirmAlreadyApplied("广场帖图片违规已自动下架", bizId)
                            : confirmMutation(affected, "广场帖图片违规已自动下架", bizId);
                }
                notifyImageTakedown(current.getMemberId(), com.chengyinhub.business.domain.AuditTask.BIZ_SQUARE, bizId,
                        com.chengyinhub.business.service.support.SystemNoticeCard.item("广场帖", current.getContents()));
                return confirmMutation(affected, "广场帖图片违规已自动下架", bizId);
            }
            else if (bizType == BIZ_TYPE_COMMENT)
            {
                com.chengyinhub.business.domain.UmsComment current = umsCommentService.selectUmsCommentById(bizId);
                if (current == null)
                {
                    return false;
                }
                if (Integer.valueOf(2).equals(current.getStatus()))
                {
                    return confirmAlreadyApplied("评论图片违规已自动下架", bizId);
                }
                com.chengyinhub.business.domain.UmsComment c = new com.chengyinhub.business.domain.UmsComment();
                c.setId(bizId);
                c.setStatus(2); // 2=下架(列表恒 status=1 过滤,自动隐藏;可逆,仅改状态)
                int affected = umsCommentService.updateUmsComment(c);
                if (affected <= 0)
                {
                    com.chengyinhub.business.domain.UmsComment observed = umsCommentService.selectUmsCommentById(bizId);
                    return observed != null && Integer.valueOf(2).equals(observed.getStatus())
                            ? confirmAlreadyApplied("评论图片违规已自动下架", bizId)
                            : confirmMutation(affected, "评论图片违规已自动下架", bizId);
                }
                return confirmMutation(affected, "评论图片违规已自动下架", bizId);
            }
            else if (bizType == BIZ_TYPE_HANGOUT)
            {
                com.chengyinhub.business.domain.RoamHangout current = roamHangoutMapper.selectById(bizId, 0L);
                if (current == null)
                {
                    return false;
                }
                if (Integer.valueOf(com.chengyinhub.business.domain.RoamHangout.STATUS_HIDDEN).equals(current.getStatus()))
                {
                    return confirmAlreadyApplied("附近的局封面违规已自动隐藏", bizId);
                }
                int affected = roamHangoutMapper.updateStatus(bizId, com.chengyinhub.business.domain.RoamHangout.STATUS_HIDDEN);
                imServiceProvider.getObject().closeHangoutGroup(bizId);   // 隐藏即关群,逻辑只在 IImService 一份
                return confirmMutation(affected, "附近的局封面违规已自动隐藏", bizId);
            }
            else if (bizType == BIZ_TYPE_TEMPLATE)
            {
                // bizId 是会员模板 id;列表/详情/首页/分类都只读模板库投影(status=1 且 publish_status=1)。
                // 下架 = 投影 publish_status=0 + status=2:作者不能直接再上架,必须重新提交广场人审;
                // 在途的广场审核单一并撤销,免得人审通过把违规图放回去。
                com.chengyinhub.business.domain.CmsMemberTemplate template =
                        cmsMemberTemplateMapper.selectCmsMemberTemplateById(bizId);
                if (template == null)
                {
                    return false;
                }
                int affected = 0;
                for (com.chengyinhub.business.domain.CmsTemplateLibrary row
                        : cmsTemplateLibraryMapper.selectByMemberTemplateForUpdate(bizId))
                {
                    if (!Integer.valueOf(0).equals(row.getDelFlag()))
                    {
                        continue;
                    }
                    // AUDIT-INT-9:未结申诉一并结案(WITHDRAWN + 通知作者),免得申诉成立把违规图放回去。
                    auditTaskServiceProvider.getObject().withdrawOpenAppeal(
                            com.chengyinhub.business.domain.AuditTask.BIZ_TEMPLATE_LIBRARY, row.getId(), APPEAL_CLOSED_BY_SAFETY);
                    auditTaskMapper.cancelOpenTemplateTasks(
                            com.chengyinhub.business.domain.AuditTask.BIZ_TEMPLATE_LIBRARY, row.getId());
                    if (Long.valueOf(0L).equals(row.getPublishStatus()) && Long.valueOf(2L).equals(row.getStatus()))
                    {
                        continue;
                    }
                    com.chengyinhub.business.domain.CmsTemplateLibrary t =
                            new com.chengyinhub.business.domain.CmsTemplateLibrary();
                    t.setId(row.getId());
                    t.setPublishStatus(0L);
                    t.setStatus(2L);
                    t.setReason("模板图片违规已自动下架");
                    t.setUpdateTime(new java.util.Date());
                    if (cmsTemplateLibraryMapper.updateCmsTemplateLibrary(t) != 1)
                    {
                        return confirmMutation(0, "模板图片违规已自动下架", bizId);
                    }
                    affected++;
                }
                if (affected > 0)
                {
                    notifyImageTakedown(template.getMemberId(), com.chengyinhub.business.domain.AuditTask.BIZ_TEMPLATE, bizId,
                            com.chengyinhub.business.service.support.SystemNoticeCard.item("玩法模板", template.getTitle()));
                }
                return affected > 0
                        ? confirmMutation(affected, "模板图片违规已自动下架", bizId)
                        : confirmAlreadyApplied("模板图片违规已自动下架", bizId);
            }
            else if (bizType == BIZ_TYPE_COMMUNITY_POST)
            {
                // 生命周期、乐观版本、搜索投影与复核单都在帖文服务一处;已隐藏/待审/终态返回 true(幂等已落地)
                boolean applied = communityPostServiceProvider.getObject().applyRiskyMediaResult(bizId);
                if (applied)
                {
                    log.warn("[内容安全] 社区帖文图片违规已隐藏待复核: id={}", bizId);
                }
                return applied;
            }
            else if (bizType == BIZ_TYPE_OFFICIAL_EVENT)
            {
                // AUDIT-G1-4:官方活动封面违规 ⇒ 下线(ST_OFFLINE,与审核台驳回同一终态,详情/列表都不出)。
                // 行锁在 WxContentCallbackMutationService(lockBusinessTarget),封面已换则 isCurrent 判 stale 不进这里。
                // 只改状态,不删数据、不动已有报名/订单。
                com.chengyinhub.business.domain.OfficialEvent current = officialEventMapper.selectOfficialEventById(bizId);
                if (current == null)
                {
                    return false;
                }
                if (Integer.valueOf(com.chengyinhub.business.domain.OfficialEvent.ST_OFFLINE).equals(current.getStatus()))
                {
                    return confirmAlreadyApplied("官方活动封面违规已自动下线", bizId);
                }
                com.chengyinhub.business.domain.OfficialEvent offline = new com.chengyinhub.business.domain.OfficialEvent();
                offline.setId(bizId);
                offline.setStatus(com.chengyinhub.business.domain.OfficialEvent.ST_OFFLINE);
                offline.setUpdateTime(new java.util.Date());
                int affected = officialEventMapper.updateOfficialEvent(offline);
                // 只有 V2 UMS_MEMBER 身份域的发布者是小程序会员;后台账号创建的不按裸 sender_account 猜会员。
                if (affected > 0 && com.chengyinhub.business.domain.OfficialEventParty.IDENTITY_UMS_MEMBER
                        .equals(current.getCreatorIdentitySource()))
                {
                    notifyImageTakedown(current.getSenderAccount(), com.chengyinhub.business.domain.AuditTask.BIZ_OFFICIAL_EVENT, bizId,
                            com.chengyinhub.business.service.support.SystemNoticeCard.item("官方活动", current.getTitle()));
                }
                return confirmMutation(affected, "官方活动封面违规已自动下线", bizId);
            }
            else
            {
                // 其它业务类型(如据点模板,量小且权限门控)暂无自动下架,落 ERROR 告警供人工核查下架
                log.error("[内容安全] 图片违规但该业务类型无自动下架处理,需人工核查下架: bizType={}, bizId={}", bizType, bizId);
                return false;
            }
        }
        catch (Exception e)
        {
            log.error("[内容安全] 处理图片检测结果异常: mapping={}, exceptionType={}",
                    mapping, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 图片违规下架后告诉作者是哪一条、为什么。只在本次回调真正把内容从公开态下架时调用(已下架的重复回调走
     * confirmAlreadyApplied 不通知);回调收件箱同事务置 DONE,同一 trace 重放不会再进来 —— 每次下架只通知一次。
     * 走嵌套事务 + 只告警:通知失败不能挡住违规内容下架。
     */
    private void notifyImageTakedown(Long memberId, String bizType, Long bizId, String item)
    {
        if (memberId == null || memberId <= 0 || imServiceProvider == null)
        {
            return;
        }
        try
        {
            com.chengyinhub.business.service.IImService im = imServiceProvider.getIfAvailable();
            if (im == null)
            {
                return;
            }
            im.sendSystemInNestedTransaction(memberId, ImMessage.MSG_CARD,
                    com.chengyinhub.business.service.support.SystemNoticeCard.IMAGE_TAKEDOWN_TITLE,
                    com.chengyinhub.business.service.support.SystemNoticeCard.imageTakedown(bizType, bizId, item));
        }
        catch (RuntimeException e)
        {
            com.chengyinhub.business.service.support.SavepointLockFailures.rethrowIfLockFailure(e);
            log.error("[内容安全] 图片违规下架通知发送失败: bizType={}, bizId={}, exceptionType={}",
                    bizType, bizId, e.getClass().getSimpleName());
        }
    }

    /**
     * 用户资料违规图按字段清:头像命中清头像、联系二维码命中清二维码、作品图只摘掉命中的那一张。
     * 调用前 inbox 事务已锁 ums_member 行并确认该哈希仍在当前资料里(isCurrentSubmittedMedia),
     * 所以读改写不会和用户新保存交错;旧二维码的迟到回调在 isCurrent 一步就被判 stale,碰不到新图。
     */
    private boolean clearRiskyMemberProfileMedia(Long memberId, String mediaHash)
    {
        if (memberId == null || StringUtils.isEmpty(mediaHash))
        {
            return false;
        }
        UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
        if (member == null)
        {
            return false;
        }
        boolean clearAvatar = containsMediaHash(mediaHash, member.getAvatar());
        boolean clearWechat = containsMediaHash(mediaHash, member.getWechat());
        boolean casePicsHit = containsMediaHash(mediaHash, member.getCasePics());
        if (!clearAvatar && !clearWechat && !casePicsHit)
        {
            return false;
        }
        String remainingCasePics = casePicsHit ? withoutMediaHash(member.getCasePics(), mediaHash) : null;
        int affected = umsMemberMapper.clearRiskyProfileMedia(memberId, clearAvatar, clearWechat,
                casePicsHit, remainingCasePics);
        return confirmMutation(affected, "用户资料违规图片已按字段清除", memberId);
    }

    /** 作品图串去掉命中哈希的那几张,其余原样保留(分号连接,与小程序写入口径一致);全去光返回 null。 */
    private String withoutMediaHash(String casePics, String mediaHash)
    {
        if (StringUtils.isEmpty(casePics))
        {
            return null;
        }
        StringBuilder kept = new StringBuilder();
        for (String part : casePics.split("[,;]"))
        {
            String url = part.trim();
            if (url.isEmpty() || hashEquals(mediaHash, url))
            {
                continue;
            }
            if (kept.length() > 0)
            {
                kept.append(';');
            }
            kept.append(url);
        }
        return kept.length() == 0 ? null : kept.toString();
    }

    private boolean confirmMutation(int affected, String action, Long bizId)
    {
        if (affected <= 0)
        {
            log.error("[内容安全] {}未更新任何记录，拒绝确认回调成功: id={}", action, bizId);
            return false;
        }
        log.warn("[内容安全] {}: id={}", action, bizId);
        return true;
    }

    /** 已下架且已留置(或已被人工驳回)= 本次违规结论已落地 */
    private static boolean topicTakedownApplied(CmsTopic topic)
    {
        return topic != null && Integer.valueOf(0).equals(topic.getUserStatus())
                && (TopicContentSafetyHold.isHeld(topic) || Integer.valueOf(2).equals(topic.getStatus()));
    }

    private boolean confirmAlreadyApplied(String action, Long bizId)
    {
        log.warn("[内容安全] {}，已观测到目标状态: id={}", action, bizId);
        return true;
    }

    @Override
    public String tryAcquireCallbackNonce(String nonce)
    {
        if (StringUtils.isEmpty(nonce) || nonce.length() > 256)
        {
            return null;
        }
        try
        {
            String ownerToken = UUID.randomUUID().toString();
            Boolean acquired = redisCache.setIfAbsent(
                    CALLBACK_NONCE_PREFIX + sha256Hex(nonce),
                    ownerToken,
                    CALLBACK_NONCE_TTL_MINUTES,
                    TimeUnit.MINUTES);
            return Boolean.TRUE.equals(acquired) ? ownerToken : null;
        }
        catch (Exception e)
        {
            log.error("[内容安全回调] nonce 防重放存储不可用，拒绝本次回调");
            return null;
        }
    }

    @Override
    public boolean releaseCallbackNonce(String nonce, String ownerToken)
    {
        if (StringUtils.isEmpty(nonce) || StringUtils.isEmpty(ownerToken) || nonce.length() > 256)
        {
            return false;
        }
        try
        {
            return redisCache.compareAndDelete(
                    CALLBACK_NONCE_PREFIX + sha256Hex(nonce),
                    ownerToken);
        }
        catch (Exception e)
        {
            log.error("[内容安全回调] nonce owner 释放失败");
            return false;
        }
    }

    private void deleteMediaTraceMapping(String traceId)
    {
        try
        {
            redisCache.deleteObject(MEDIA_TRACE_PREFIX + traceId);
        }
        catch (Exception e)
        {
            // DONE 状态已经持久化，映射清理失败不会导致重复业务副作用。
            log.error("[内容安全回调] 检测结果映射清理异常");
        }
    }

    /**
     * 兼容迁移前已经送检、只有 Redis/业务表 trace 的在途任务。新任务在送检返回前已登记 inbox。
     * 兼容登记无法恢复原媒体 URL 时 media_hash 留空，不伪造 stale-media 证明。
     */
    private boolean ensureCallbackInboxRegistration(String traceId)
    {
        if (callbackInboxService.isRegistered(traceId))
        {
            return true;
        }
        String mapping = redisCache.getCacheObject(MEDIA_TRACE_PREFIX + traceId);
        if (StringUtils.isNotEmpty(mapping))
        {
            try
            {
                String[] parts = mapping.split(":");
                return parts.length == 2 && callbackInboxService.register(
                        traceId, Integer.parseInt(parts[0]), Long.parseLong(parts[1]), null);
            }
            catch (Exception e)
            {
                return false;
            }
        }
        ImMessage message = imMessageMapper.selectByTraceId(traceId);
        return message != null && callbackInboxService.register(
                traceId, BIZ_TYPE_IM_MESSAGE, message.getId(), message.getContent());
    }

    /** 在 inbox 与目标行均已 FOR UPDATE 的事务里判断“当前媒体仍是当初送检媒体”。 */
    private boolean isCurrentSubmittedMedia(WxContentCallbackInbox inbox)
    {
        String hash = inbox.getMediaHash();
        if (StringUtils.isEmpty(hash))
        {
            return false; // 缺少媒体哈希的旧任务会在 mutation 入口持久化为 MANUAL_REVIEW。
        }
        Long id = inbox.getBizId();
        switch (inbox.getBizType())
        {
            case BIZ_TYPE_TOPIC:
                CmsTopic topic = cmsTopicService.selectCmsTopicById(id);
                return topic != null && containsMediaHash(hash, topic.getImgUrl(), topic.getImgArr());
            case BIZ_TYPE_ACTIVITY:
                CmsActivity activity = cmsActivityService.selectCmsActivityById(id);
                return activity != null && containsMediaHash(hash, activity.getImgUrl(), activity.getImgArr());
            case BIZ_TYPE_CLUB_POST:
                ClubPost post = clubPostService.selectClubPostById(id);
                return post != null && containsMediaHash(hash, post.getImages());
            case BIZ_TYPE_MEMBER_PROFILE:
                UmsMember member = umsMemberMapper.selectUmsMemberById(id);
                return member != null && containsMediaHash(hash, member.getAvatar(), member.getCasePics(),
                        member.getWechat());
            case BIZ_TYPE_CLUB_PROFILE:
                Club club = clubMapper.selectClubById(id);
                return club != null && containsMediaHash(hash, club.getLogo(), club.getCover(), club.getCaseImages());
            case BIZ_TYPE_MERCHANT_PROFILE:
                MmsMerchant merchant = mmsMerchantMapper.selectMmsMerchantById(id);
                return merchant != null && containsMediaHash(hash,
                        merchant.getLogo(), merchant.getCoverImage(), merchant.getGallery());
            case BIZ_TYPE_NODE_NPC:
                com.chengyinhub.business.domain.NpcProfile nodeNpc = npcProfileMapper.selectNodeNpcById(id);
                return nodeNpc != null && containsMediaHash(hash, nodeNpc.getAvatar());
            case BIZ_TYPE_MERCHANT_NPC:
                com.chengyinhub.business.domain.NpcProfile merchantNpc = npcProfileMapper.selectMerchantNpcById(id);
                return merchantNpc != null && containsMediaHash(hash, merchantNpc.getAvatar());
            case BIZ_TYPE_SQUARE:
                UmsCreativeSquare square = umsCreativeSquareService.selectUmsCreativeSquareById(id);
                return square != null && containsMediaHash(hash,
                        square.getPics(), square.getFileUrl(), square.getRoutePreviewImg());
            case BIZ_TYPE_COMMENT:
                UmsComment comment = umsCommentService.selectUmsCommentById(id);
                return comment != null && containsMediaHash(hash, comment.getImgArr());
            case BIZ_TYPE_ROAM_STAMP:
                RoamStamp stamp = roamStampMapper.selectById(id);
                return stamp != null && containsMediaHash(hash, stamp.getPicUrl());
            case BIZ_TYPE_IM_MESSAGE:
                ImMessage message = imMessageMapper.selectImMessageById(id);
                return message != null && containsMediaHash(hash, message.getContent());
            case BIZ_TYPE_TEMPLATE:
                com.chengyinhub.business.domain.CmsMemberTemplate template =
                        cmsMemberTemplateMapper.selectCmsMemberTemplateById(id);
                return template != null && containsMediaHash(hash, template.getImgUrl(), template.getMedalImg());
            case BIZ_TYPE_HANGOUT:
                com.chengyinhub.business.domain.RoamHangout hangout = roamHangoutMapper.selectById(id, 0L);
                return hangout != null && containsMediaHash(hash, hangout.getCoverUrl());
            case BIZ_TYPE_COMMUNITY_POST:
                return containsMediaHash(hash,
                        communityPostServiceProvider.getObject().activeMediaKeys(id).toArray(new String[0]));
            case BIZ_TYPE_OFFICIAL_EVENT:
                // 官方活动只送检封面(ApiOfficialEventController 发布口)。
                com.chengyinhub.business.domain.OfficialEvent event = officialEventMapper.selectOfficialEventById(id);
                return event != null && containsMediaHash(hash, event.getCoverImg());
            default:
                return false;
        }
    }

    private boolean containsMediaHash(String expectedHash, String... values)
    {
        if (values == null)
        {
            return false;
        }
        for (String value : values)
        {
            if (StringUtils.isEmpty(value))
            {
                continue;
            }
            if (hashEquals(expectedHash, value.trim()))
            {
                return true;
            }
            try
            {
                Object parsed = JSON.parse(value);
                if (containsHashedLeaf(expectedHash, parsed))
                {
                    return true;
                }
            }
            catch (Exception ignored)
            {
                // 非 JSON 的普通 URL/分隔串继续走下面的固定分隔解析。
            }
            String[] parts = value.split("[,;]");
            for (String part : parts)
            {
                String normalized = part.trim().replaceAll("^[\\[\\{\\\"']+|[\\]\\}\\\"']+$", "");
                if (hashEquals(expectedHash, normalized))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsHashedLeaf(String expectedHash, Object value)
    {
        if (value instanceof Map)
        {
            for (Object child : ((Map<?, ?>) value).values())
            {
                if (containsHashedLeaf(expectedHash, child)) return true;
            }
            return false;
        }
        if (value instanceof Iterable)
        {
            for (Object child : (Iterable<?>) value)
            {
                if (containsHashedLeaf(expectedHash, child)) return true;
            }
            return false;
        }
        return value instanceof String && hashEquals(expectedHash, ((String) value).trim());
    }

    private boolean hashEquals(String expectedHash, String value)
    {
        try
        {
            return StringUtils.isNotEmpty(value) && expectedHash.equalsIgnoreCase(sha256Hex(value));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private String sha256Hex(String value) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash)
        {
            String part = Integer.toHexString(b & 0xff);
            if (part.length() == 1)
            {
                hex.append('0');
            }
            hex.append(part);
        }
        return hex.toString();
    }

    /**
     * 集邮票的机审回写。与其它业务的差别:本业务「通过」也是要落库的事实,不是无事发生。
     *
     * pass  → 1,但仅从 0 迁移:已判违规的票绝不因一条迟到的 pass 回调复活(违规单向)。
     * risky → 2,无条件:违规判定永远赢,册子里当场消失。
     * 机审关闭 / 送检失败 → 压根不会有回调,票留在 0=未送检 —— 这是诚实值,不是遗漏。
     */
    private boolean handleRoamStampCheckResult(String mapping, boolean risky)
    {
        try
        {
            Long stampId = Long.parseLong(mapping.split(":")[1]);
            if (risky)
            {
                int affected = roamStampMapper.markCheckRisky(stampId);
                if (affected <= 0)
                {
                    RoamStamp observed = roamStampMapper.selectById(stampId);
                    return observed != null && Integer.valueOf(2).equals(observed.getCheckState())
                            && confirmAlreadyApplied("集邮票图片违规已自动下架", stampId);
                }
                return confirmMutation(affected, "集邮票图片违规已自动下架", stampId);
            }
            else
            {
                int affected = roamStampMapper.markCheckPassed(stampId);
                if (affected <= 0)
                {
                    RoamStamp observed = roamStampMapper.selectById(stampId);
                    return observed != null && Integer.valueOf(1).equals(observed.getCheckState())
                            && confirmAlreadyApplied("集邮票图片审核已通过", stampId);
                }
                return true;
            }
        }
        catch (Exception e)
        {
            log.error("[内容安全] 处理集邮票检测结果异常: mapping={}, exceptionType={}",
                    mapping, e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean handleImMediaCheckResult(Long messageId, String traceId, boolean risky)
    {
        ImMessage current = imMessageMapper.selectImMessageById(messageId);
        if (current == null || !traceId.equals(current.getTraceId()))
        {
            return false;
        }
        int expectedCheckStatus = risky ? 2 : 1;
        if (Integer.valueOf(expectedCheckStatus).equals(current.getCheckStatus())
                && (!risky || Integer.valueOf(ImMessage.STATUS_BLOCKED).equals(current.getStatus())))
        {
            return confirmAlreadyApplied(risky ? "IM图片违规已自动下架" : "IM图片审核已通过", messageId);
        }
        int affected = imMessageMapper.applyMediaCheckResult(messageId, traceId, risky);
        return confirmMutation(affected, risky ? "IM图片违规已自动下架" : "IM图片审核已通过", messageId);
    }

    @Override
    public String getAccessToken()
    {
        return getAccessTokenOrEmpty(false);
    }

    @Override
    public String refreshAccessToken()
    {
        return getAccessTokenOrEmpty(true);
    }

    private String getAccessTokenOrEmpty(boolean forceRefresh)
    {
        try
        {
            return getAccessToken(forceRefresh);
        }
        catch (TokenConfigurationException e)
        {
            return "";
        }
    }

    private static class TokenConfigurationException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 获取微信小程序 access_token（与控制器现有实现共享同一 redis key）。
     *
     * @param forceRefresh true 时清缓存强制重新获取
     */
    private String getAccessToken(boolean forceRefresh)
    {
        String appId = wxConfig.getMiniAppId();
        String secret = wxConfig.getMiniSecret();
        if (StringUtils.isEmpty(appId) || StringUtils.isEmpty(secret))
        {
            log.warn("[内容安全] 未配置 miniAppId/miniSecret");
            throw new TokenConfigurationException();
        }
        try
        {
            if (forceRefresh)
            {
                redisCache.deleteObject(WX_ACCESS_TOKEN_KEY);
            }
            else
            {
                String cached = redisCache.getCacheObject(WX_ACCESS_TOKEN_KEY);
                if (StringUtils.isNotEmpty(cached))
                {
                    return cached;
                }
            }

            Map<String, Object> credentials = new java.util.LinkedHashMap<>();
            credentials.put("grant_type", "client_credential");
            credentials.put("appid", appId);
            credentials.put("secret", secret);
            credentials.put("force_refresh", false);
            String result = sendPost(WX_TOKEN_URL, JSON.toJSONString(credentials), "application/json");
            Map<String, Object> map = JSON.parseObject(result);
            if (map == null)
            {
                return "";
            }
            String token = Convert.toStr(map.get("access_token"));
            if (StringUtils.isEmpty(token))
            {
                int errcode = Convert.toInt(map.get("errcode"), -1);
                log.warn("[内容安全] 获取 access_token 失败，errcode={}", errcode);
                if (errcode == 40125 || errcode == 40013 || errcode == 40164)
                {
                    throw new TokenConfigurationException();
                }
                return "";
            }
            long expiresIn = Convert.toLong(map.get("expires_in"), 7200L);
            // 提前 300s 过期，避免临界失效
            redisCache.setCacheObject(WX_ACCESS_TOKEN_KEY, token, (int) Math.max(60, expiresIn - 300), TimeUnit.SECONDS);
            return token;
        }
        catch (TokenConfigurationException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            redisCache.deleteObject(WX_ACCESS_TOKEN_KEY);
            log.error("[内容安全] 获取 access_token 异常。exceptionType={}",
                    ex.getClass().getSimpleName());
            return "";
        }
    }

    String sendPost(String url, String body, String contentType)
    {
        if (url != null && (url.startsWith(WX_MSG_SEC_CHECK_URL + "?")
                || url.startsWith(WX_MEDIA_CHECK_URL + "?")))
            com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        HttpURLConnection connection = null;
        try
        {
            connection = openConnection(url, "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType + "; charset=UTF-8");
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    connection.getOutputStream(), StandardCharsets.UTF_8))
            {
                writer.write(body == null ? "" : body);
            }
            return readResponse(connection);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("微信内容安全请求失败");
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    String sendGet(String url)
    {
        HttpURLConnection connection = null;
        try
        {
            connection = openConnection(url, "GET");
            return readResponse(connection);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("微信内容安全请求失败");
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String url, String method) throws Exception
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
        connection.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private String readResponse(HttpURLConnection connection) throws Exception
    {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null)
        {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
            return response.toString();
        }
    }
}

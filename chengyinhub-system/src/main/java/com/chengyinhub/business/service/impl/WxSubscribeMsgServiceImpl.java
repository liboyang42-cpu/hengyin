package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信订阅消息发送实现(F8)。best-effort:任何失败/未配置都跳过、绝不阻断业务 —— 但必须留日志。
 * ⚠️ 不阻断 ≠ 静默:失败要么 warn 要么 error,绝不能进 log.info 冒充成功(见 send 里 errcode 解析)。
 */
@Service
public class WxSubscribeMsgServiceImpl implements IWxSubscribeMsgService {

    private static final Logger log = LoggerFactory.getLogger(WxSubscribeMsgServiceImpl.class);
    private static final String SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";

    @Autowired
    private IWxContentSecurityService wxSec;

    @Autowired
    private IUmsMemberService umsMemberService;

    /** 模板ID + 跳转页(application 配置;未配置则该类消息跳过) */
    @Value("${wx.subscribe.tplRefund:}")
    private String tplRefund;

    @Value("${wx.subscribe.tplSignupSuccess:}")
    private String tplSignupSuccess;

    @Value("${wx.subscribe.tplBeforeStart:}")
    private String tplBeforeStart;

    // 合作/定价四类(F3-2)
    @Value("${wx.subscribe.tplCoopInvited:}")
    private String tplCoopInvited;

    @Value("${wx.subscribe.tplCoopStatus:}")
    private String tplCoopStatus;

    @Value("${wx.subscribe.tplClubInterest:}")
    private String tplClubInterest;

    @Value("${wx.subscribe.tplRecruit:}")
    private String tplRecruit;

    @Value("${wx.subscribe.tplCoopSettle:}")
    private String tplCoopSettle;

    /** 官方活动/公告专用模板。⚠️ 需在微信公众平台建模板后配置此 ID,否则不发(见 notifyOfficialBroadcast)。 */
    @Value("${wx.subscribe.tplOfficialEvent:}")
    private String tplOfficialEvent;

    /** 漫游召回专用模板。公众平台尚未创建时保持空，绝不借用别的业务模板。 */
    @Value("${wx.subscribe.tplRoamRecall:}")
    private String tplRoamRecall;

    @Value("${wx.subscribe.page:pages/index/index}")
    private String defaultPage;

    /** 已告警过的未配模板。每 JVM 只警一次:批量通知逐条告警会刷屏。 */
    private final Set<String> warnedMissingTpl = ConcurrentHashMap.newKeySet();

    /** 未配模板 = 该类消息整体不下发。这不是改码能修的:模板 ID 须在微信公众平台申请后填进 application.yml。 */
    private void warnTplMissing(String key, String businessEvent) {
        if (warnedMissingTpl.add(key)) {
            log.warn("[订阅消息] 模板未配置 templateKey={} businessEvent={} action=skip,"
                    + "需在微信公众平台申请并配置后重启", key, businessEvent);
        }
    }

    @Override
    public boolean send(String openid, String templateId, String page, Map<String, String> fields) {
        return send(openid, templateId, page, fields, null, null, null);
    }

    private boolean send(String openid, String templateId, String page, Map<String, String> fields,
                         Long memberId, String templateKey, String businessEvent) {
        return send(openid, templateId, page, fields, memberId, templateKey, businessEvent, false);
    }

    private boolean send(String openid, String templateId, String page, Map<String, String> fields,
                         Long memberId, String templateKey, String businessEvent, boolean frozenPage) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (StringUtils.isEmpty(openid)) {
            log.debug("[订阅消息] 无 openid,跳过 templateChars={}", safeLength(templateId)); // 用户没走微信登录,属正常
            return false;
        }
        if (StringUtils.isEmpty(templateId)) {
            log.warn("[订阅消息] 模板未配置,本条不发(配置缺失,非用户问题)");
            return false;
        }
        String token = wxSec.getAccessToken();
        if (StringUtils.isEmpty(token)) {
            log.error("[订阅消息] access_token 获取失败,本条不发 templateChars={}",
                    safeLength(templateId));
            return false; // 不阻断业务,但不能不吭声
        }
        try {
            StringBuilder data = new StringBuilder("{");
            boolean first = true;
            if (fields != null) {
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    if (!first) data.append(",");
                    data.append("\"").append(e.getKey()).append("\":{\"value\":\"")
                        .append(esc(e.getValue())).append("\"}");
                    first = false;
                }
            }
            data.append("}");
            String body = "{\"touser\":\"" + openid + "\",\"template_id\":\"" + templateId
                    + "\",\"page\":\"" + (frozenPage ? page : (StringUtils.isEmpty(page) ? defaultPage : page))
                    + "\",\"data\":" + data + "}";
            String resp = post(SEND_URL + "?access_token=" + token, body);
            // ⚠️ 微信 send 失败也返 HTTP 200 + body {"errcode":40003,...};不解析 errcode 就是把失败当成功记。
            // 网络层异常由 HttpUtils 显式抛出，在本方法 catch 中按 best-effort 合同安全降级。
            Map<String, Object> r = JSON.parseObject(resp);
            int errcode = r == null ? -1 : Convert.toInt(r.get("errcode"), -1);
            if (errcode != 0) {
                // 只 error 不抛:调用方(如 refundOne)在 @Transactional(rollbackFor=Exception.class) 里,
                // 抛出会把已在微信网关受理的退款连带回滚 = 资损。best-effort 的含义就是这个。
                // 改为「不抛但返回 false」:想判成败的调用方看返回值,不关心的照旧忽略,退款路径行为不变。
                if (!StringUtils.isEmpty(templateKey)) {
                    log.error("[订阅消息] 发送失败 templateKey={} businessEvent={} memberId={} errcode={} errmsg={}",
                            templateKey, businessEvent, memberId, errcode,
                            safeErrmsg(r == null ? null : r.get("errmsg"), openid, templateId));
                } else {
                    log.error("[订阅消息] 发送失败 templateChars={} errcode={} responseChars={}",
                            safeLength(templateId), errcode, resp == null ? 0 : resp.length());
                }
                return false;
            }
            log.info("[订阅消息] send ok templateChars={}", safeLength(templateId));
            return true;
        } catch (Exception ex) {
            if (ex instanceof com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) throw paused;
            log.warn("[订阅消息] 发送失败 templateChars={} reason={}",
                    safeLength(templateId), ex.getClass().getSimpleName());
            return false;
        }
    }

    private boolean sendToMember(Long memberId, String templateKey, String businessEvent,
                                 String templateId, String page, Map<String, String> fields) {
        if (StringUtils.isEmpty(templateId)) {
            warnTplMissing(templateKey, businessEvent);
            return false;
        }
        return send(openidOf(memberId), templateId, page, fields,
                memberId, templateKey, businessEvent);
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    /** errmsg 仅用于诊断:去掉换行并遮掉本次 openid/templateId,避免把收件标识或模板凭据写进日志。 */
    private static String safeErrmsg(Object value, String openid, String templateId) {
        String message = value == null ? "" : String.valueOf(value);
        message = message.replace('\r', ' ').replace('\n', ' ');
        if (!StringUtils.isEmpty(openid)) {
            message = message.replace(openid, "[redacted]");
        }
        if (!StringUtils.isEmpty(templateId)) {
            message = message.replace(templateId, "[redacted]");
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /** HTTP 缝:HttpUtils 是静态方法且本仓 Mockito 无 inline mock maker,测试覆写此方法注入响应。 */
    String post(String url, String body) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        return HttpUtils.sendPost(url, body, "application/json");
    }

    @Override
    public boolean notifyRefund(Long memberId, String topicName, String amount) {
        // 模板【订单退款通知】:退款金额 amount1 / 退款时间 time2 / 订单编号 character_string3 / 退款结果 phrase5
        Map<String, String> f = new HashMap<>();
        f.put("amount1", amount == null || amount.isEmpty() ? "0" : amount);
        f.put("time2", nowTime());
        f.put("character_string3", "AUTOREFUND");
        f.put("phrase5", "已退款");
        return sendToMember(memberId, "tplRefund", "refund", tplRefund, defaultPage, f);
    }

    @Override
    public boolean notifySignupSuccess(Long memberId, String topicName) {
        // 模板【报名成功通知】:活动名称 thing1 / 活动地址 thing2 / 活动时间 date3 / 任务截至时间 time12 / 用户昵称 thing10
        Map<String, String> f = new HashMap<>();
        f.put("thing1", clip(topicName));
        f.put("thing2", "详见活动详情");
        f.put("date3", nowDate());
        f.put("time12", nowTime());
        f.put("thing10", nickOf(memberId));
        return sendToMember(memberId, "tplSignupSuccess", "signup_success", tplSignupSuccess, defaultPage, f);
    }

    @Override
    public boolean notifyBeforeStart(Long memberId, String topicName, String whenText) {
        PreparedBeforeStart message = prepareBeforeStart(memberId, topicName, whenText);
        return message != null && sendPreparedBeforeStart(message);
    }

    @Override
    public PreparedBeforeStart prepareBeforeStart(Long memberId, String topicName, String whenText) {
        // Original BEFORE_START template and five fields; no broadcast-template fallback.
        Map<String, String> f = new HashMap<>();
        f.put("thing1", clip(topicName));
        f.put("date5", wxDate5(whenText));
        f.put("thing8", "详见活动详情");
        f.put("thing7", "请准时到场");
        f.put("thing12", nickOf(memberId));
        if (StringUtils.isEmpty(tplBeforeStart)) {
            warnTplMissing("tplBeforeStart", "before_start");
            return null;
        }
        String openid = openidOf(memberId);
        if (StringUtils.isEmpty(openid)) return null;
        return new PreparedBeforeStart(memberId, openid, tplBeforeStart, defaultPage, f);
    }

    @Override
    public boolean sendPreparedBeforeStart(PreparedBeforeStart message) {
        if (message == null || message.memberId() == null || message.memberId() <= 0
                || StringUtils.isEmpty(message.openid()) || StringUtils.isEmpty(message.templateId())
                || message.page() == null || message.fields() == null
                || !message.fields().keySet().equals(java.util.Set.of("thing1", "date5", "thing8", "thing7", "thing12")))
            throw new IllegalArgumentException("出发提醒冻结微信输入无效");
        return send(message.openid(), message.templateId(), message.page(), message.fields(),
                message.memberId(), "tplBeforeStart", "before_start", true);
    }

    @Override
    public boolean notifyCoopInvited(Long toMemberId, String topicName, String fromName) {
        // 模板【合作邀约通知】:对方名称 thing2 / 温馨提示 thing3
        Map<String, String> f = new HashMap<>();
        f.put("thing2", clip(fromName));
        f.put("thing3", clip("邀你合作:" + (topicName == null ? "" : topicName)));
        return sendToMember(toMemberId, "tplCoopInvited", "coop_invited", tplCoopInvited, defaultPage, f);
    }

    @Override
    public boolean notifyClubInterested(Long toMemberId, String topicName, String clubName) {
        // 模板【承接意向通知】(2026-07-21 新增,公众平台模板待创建;字段 key 须与实际模板一致,
        // 沿用 coopInvited 同款结构:对方名称 thing2 / 温馨提示 thing3。模板未配置时明确 WARN 后跳过。
        Map<String, String> f = new HashMap<>();
        f.put("thing2", clip(clubName));
        f.put("thing3", clip("想承接《" + (topicName == null ? "" : topicName) + "》带队"));
        return sendToMember(toMemberId, "tplClubInterest", "club_interest", tplClubInterest, defaultPage, f);
    }

    @Override
    public boolean notifyCoopStatus(Long fromMemberId, String topicName, String toName, String statusText, String progress) {
        // 模板【邀约状态变更通知】:邀约状态 phrase1 / 邀约名称 thing2 / 接受者 thing3 / 邀约进展 thing6
        Map<String, String> f = new HashMap<>();
        f.put("phrase1", clipPhrase(statusText));
        f.put("thing2", clip(topicName));
        f.put("thing3", clip(toName));
        f.put("thing6", clip(progress));
        return sendToMember(fromMemberId, "tplCoopStatus", "coop_status", tplCoopStatus, defaultPage, f);
    }

    @Override
    public boolean notifyRecruit(Long publisherId, String topicName, String activityTime, String tip) {
        // 模板【新活动招募通知】:活动名称 thing1 / 活动时间 time2 / 温馨提示 thing3
        Map<String, String> f = new HashMap<>();
        f.put("thing1", clip(topicName));
        f.put("time2", activityTime == null ? "" : activityTime);
        f.put("thing3", clip(tip));
        return sendToMember(publisherId, "tplRecruit", "recruit_status", tplRecruit, defaultPage, f);
    }

    @Override
    public boolean notifyCoopSettle(Long merchantId, String amount, String whenText, String desc, String orderNo) {
        // 模板【订单结算通知】:结算金额 amount1 / 结算说明 thing2 / 结算时间 time3 / 订单编号 character_string4
        Map<String, String> f = new HashMap<>();
        f.put("amount1", amount == null ? "" : amount);
        f.put("thing2", clip(desc));
        f.put("time3", whenText == null ? "" : whenText);
        f.put("character_string4", orderNo == null ? "" : orderNo);
        return sendToMember(merchantId, "tplCoopSettle", "coop_settle", tplCoopSettle, defaultPage, f);
    }

    @Override
    public OfficialBroadcastResult notifyOfficialBroadcastResult(Long memberId, String title, String sub) {
        String body;
        String token;
        try {
            if (StringUtils.isEmpty(tplOfficialEvent)) return officialNotSubmitted(OfficialBroadcastResult.Reason.TEMPLATE_MISSING);
            if (memberId == null || memberId <= 0) return officialNotSubmitted(OfficialBroadcastResult.Reason.IDENTITY_MISSING);
            String openid = openidOf(memberId);
            if (StringUtils.isEmpty(openid)) return officialNotSubmitted(OfficialBroadcastResult.Reason.IDENTITY_MISSING);
            token = wxSec.getAccessToken();
            if (StringUtils.isEmpty(token)) return officialNotSubmitted(OfficialBroadcastResult.Reason.TOKEN_MISSING);
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("thing1", Map.of("value", clip(title)));
            fields.put("time2", Map.of("value", nowTime()));
            fields.put("thing3", Map.of("value", clip(sub)));
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("touser", openid); payload.put("template_id", tplOfficialEvent);
            payload.put("page", defaultPage); payload.put("data", fields);
            body = JSON.toJSONString(payload);
        } catch (Exception preparationFailure) {
            return officialNotSubmitted(OfficialBroadcastResult.Reason.LOCAL_PREPARATION_FAILED);
        }
        try {
            return officialBroadcastResponse(post(SEND_URL + "?access_token=" + token, body));
        } catch (Exception transportFailure) {
            return new OfficialBroadcastResult(OfficialBroadcastResult.State.UNKNOWN,
                    OfficialBroadcastResult.Reason.TRANSPORT_UNCONFIRMED, null);
        }
    }

    private static OfficialBroadcastResult officialNotSubmitted(OfficialBroadcastResult.Reason reason) {
        return new OfficialBroadcastResult(OfficialBroadcastResult.State.NOT_SUBMITTED, reason, null);
    }

    /** Parse only a received response. Never infer a provider result from an old boolean. */
    static OfficialBroadcastResult officialBroadcastResponse(String response) {
        try {
            Object parsed = JSON.parse(response);
            if (parsed instanceof Map<?, ?> values) {
                Object raw = values.get("errcode");
                // Strings, booleans, decimals, absent and overflowing values are not numeric codes.
                if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long
                        || raw instanceof java.math.BigInteger) {
                    int code = new java.math.BigInteger(raw.toString()).intValueExact();
                    return new OfficialBroadcastResult(code == 0 ? OfficialBroadcastResult.State.ACCEPTED
                            : OfficialBroadcastResult.State.REJECTED, code == 0 ? OfficialBroadcastResult.Reason.PROVIDER_ACCEPTED
                            : OfficialBroadcastResult.Reason.PROVIDER_REJECTED, code);
                }
            }
        } catch (RuntimeException invalid) {
            // Do not retain the response or parser exception: either may contain private data.
        }
        return new OfficialBroadcastResult(OfficialBroadcastResult.State.UNKNOWN,
                OfficialBroadcastResult.Reason.RESPONSE_UNCONFIRMED, null);
    }

    @Override
    public boolean notifyOfficialBroadcast(Long memberId, String title, String sub) {
        // 模板【官方活动通知】:活动名称 thing1 / 活动时间 time2 / 温馨提示 thing3。best-effort。
        // 未配专用模板 ⇒ 不发。不回退 tplRecruit:那是「新活动招募→发布者」的真实模板,
        // 拿它给玩家发官方广播既是模板语义错配,又会烧掉用户为招募授权的配额(一次授权=一次发送)。
        if (StringUtils.isEmpty(tplOfficialEvent)) {
            warnTplMissing("tplOfficialEvent", "official_broadcast");
            return false;
        }
        Map<String, String> f = new HashMap<>();
        f.put("thing1", clip(title));
        f.put("time2", nowTime());
        f.put("thing3", clip(sub));
        return sendToMember(memberId, "tplOfficialEvent", "official_broadcast", tplOfficialEvent, defaultPage, f);
    }

    @Override
    public boolean notifyRoamRecall(Long memberId, int distanceM, int poisFound) {
        if (StringUtils.isEmpty(tplRoamRecall)) {
            warnTplMissing("tplRoamRecall", "roam_recall");
            return false;
        }
        Map<String, String> f = new HashMap<>();
        // 字段 key 待公众平台选定模板后按实际字段校准；模板为空时本分支不会执行。
        f.put("thing1", clip("你的城市足迹还在延伸"));
        f.put("thing2", clip("上次走了" + Math.max(0, distanceM) + "米，发现" + Math.max(0, poisFound) + "处"));
        f.put("thing3", clip("回来继续点亮城市"));
        // ⚠️ 2026-08-10:subpackageRoam/passport 整页已删(内容被漫游页自己的「漫游护照」tab 取代),
        // 这里改跳漫游首页。仍然没留转发桩页,依据同上一轮查过的事实:tplRoamRecall 生产值为空、
        // feature.roamRecall.enabled 默认 false ⇒ 这条订阅消息一条都没发出过,不存在会跳空的存量卡片。
        // 落在「开始漫游」tab 而不是护照 tab:本条文案是"回来继续点亮城市",召回的是再出发不是看存档。
        // 若以后先配模板再改前端路径,这里要跟着改,否则用户点进来是空白页。
        return sendToMember(memberId, "tplRoamRecall", "roam_recall",
                tplRoamRecall, "pages/roam/index", f);
    }

    @Override
    public boolean isRoamRecallReady() {
        return !StringUtils.isEmpty(tplRoamRecall);
    }

    private String openidOf(Long memberId) {
        if (memberId == null) return null;
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        return m == null ? null : m.getWxOpenId();
    }

    /** 昵称(用于 thing 类型的"用户昵称"字段);取不到用"用户"兜底,避免 thing 空值被微信拒收 */
    private String nickOf(Long memberId) {
        try {
            if (memberId == null) return "用户";
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            String n = m == null ? null : m.getNickname();
            return StringUtils.isEmpty(n) ? "用户" : clip(n);
        } catch (Exception e) {
            return "用户";
        }
    }

    /** 当前时刻 yyyy-MM-dd HH:mm(微信 time 类型) */
    private String nowTime() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
    }

    /**
     * date5 的格式归一(微信 date 类型要求带年份,官方允许「yyyy年MM月dd日 HH:mm」等形状;
     * 只有 MM-dd 会被判 47003)。
     *
     * 旧实现在这里 substring(0,10):把生产者给的 "MM-dd HH:mm" 截成 "09-16 14:0" ——
     * 既丢年份又截断分钟,102 启用后每条都会被微信拒,且没有任何信号(审查D §8 P1-1)。
     * 现在只保证一件事:**date5 出去的值一定带年份**。带年份的原样放行;历史 MM-dd 形状
     * 补当前年份;识别不了的自由文本打 WARN 后用当前时间兜底,绝不发注定被拒的值。
     */
    private String wxDate5(String whenText) {
        if (StringUtils.isEmpty(whenText)) {
            return nowTime();
        }
        String text = whenText.trim();
        if (text.matches("\\d{4}[-/年].+")) {
            return text;
        }
        if (text.matches("\\d{1,2}-\\d{1,2}.*")) {
            return new java.text.SimpleDateFormat("yyyy-").format(new java.util.Date()) + text;
        }
        log.warn("[订阅消息] date5 值不含年份,已用当前时间兜底(微信 date 类型要求年月日) chars={}",
                text.length());
        return nowTime();
    }

    /** 当前日期 yyyy-MM-dd(微信 date 类型) */
    private String nowDate() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /** 微信 thing 类型限 20 字 */
    private String clip(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    /** 微信 phrase(短语)类型限 5 个汉字 */
    private String clipPhrase(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 5 ? s.substring(0, 5) : s;
    }
}

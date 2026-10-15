package com.chengyinhub.business.service;

/**
 * 微信内容安全（UGC 过审硬门槛）Service 接口
 *
 * 用途：在主题/活动/创意广场/评论等用户生成内容的发布链路前置调用，
 * 命中违规内容直接拦截，避免被微信封禁小程序。
 *
 * 设计约定（重要）：
 *  - checkText 返回 null 表示「放行」（合规或空文本）；检测关闭、缺 openid/token、接口异常时一律 fail-closed，
 *    返回非 null 字符串表示「拦截」，字符串即给用户的提示文案。
 *    控制器用法：String msg = wxSec.checkText(text, openid); if (msg != null) return error(msg);
 *  - 是否真正调用微信接口由配置 wx.sec-check.enabled 控制（WxPayConfig，默认 true）。
 *    关闭不是「降级放行」：非空文本会返回 MSG_UNAVAILABLE 被拒写（F-L5C-07），并记 ERROR 日志。
 */
public interface IWxContentSecurityService
{
    /** 微信建议人工复核；统一门禁必须映射为 HOLD，旧调用方则至少会 fail-closed 拦截。 */
    String REVIEW_REQUIRED = "内容需要人工复核，请稍后查看";

    enum CallbackResult
    {
        SUCCESS,
        TOKEN_UNAVAILABLE,
        FORBIDDEN,
        INVALID_TIMESTAMP,
        INVALID_PAYLOAD,
        REPLAYED,
        PROCESSING_FAILED
    }

    /**
     * 文本内容安全检测（同步，msgSecCheck v2）。
     *
     * @param content 待检测文本（多个字段可拼接后传入）
     * @param openid  发表者的微信小程序 openid（v2 <b>必填</b>；传空会被微信判 40003，见下方两条文案常量）
     * @return null=放行；REVIEW_REQUIRED=真人工复审（suggest=review 或成功包缺 result）；
     *         其他非 null=拦截提示文案（含「审不了」的两种：MSG_BUSY / MSG_UNAVAILABLE，均 fail-close）
     */
    /** 机审「审不了」且**可重试**(token 拿不到 / 网络异常 / 接口报错)时的固定文案;调用方按引用相等判「繁忙 ≠ 违规」,别 sniff 文案 */
    String MSG_BUSY = "内容审核服务繁忙，请稍后重试";

    /**
     * 机审「审不了」且**重试无用**(调用方参数错误,如 openid 缺失/无效的 40003;或配置错)时的固定文案。
     * 与 MSG_BUSY 分开,是为了不把我们的调用错误说成「服务繁忙，请稍后重试」——用户重试多少次都不会通。
     * 同样 fail-close(返回非 null),绝不放行。
     */
    String MSG_UNAVAILABLE = "内容审核暂时不可用，请联系平台";

    /** 机审「没给出结论」(MSG_BUSY / MSG_UNAVAILABLE)的统一判据:调用方据此区别于「真违规」,别把用户当违规者。 */
    static boolean isDetectionUnavailable(String message)
    {
        return message == MSG_BUSY || message == MSG_UNAVAILABLE;
    }

    String checkText(String content, String openid);

    /**
     * 图片/媒体内容安全异步检测（mediaCheckAsync）。
     * 微信异步返回：结果通过「小程序消息推送」回调到后台配置的服务器 URL（需在
     * 微信公众平台-开发管理-消息推送 配置，监听 wxa_media_check 事件），本方法只负责送检并拿 trace_id。
     *
     * @param mediaUrl 媒体 URL（必须是可公网访问的 https 地址）
     * @param openid   发表者 openid
     * @return trace_id（送检成功）或 null（未送检 / 异常）
     */
    String checkMediaAsync(String mediaUrl, String openid);

    /** 业务类型：主题 */
    int BIZ_TYPE_TOPIC = 1;
    /** 业务类型：活动 */
    int BIZ_TYPE_ACTIVITY = 2;
    /** 业务类型：俱乐部动态 */
    int BIZ_TYPE_CLUB_POST = 3;
    /** 业务类型：用户资料(头像/案例图) */
    int BIZ_TYPE_MEMBER_PROFILE = 4;
    /** 业务类型：俱乐部资料(logo/封面/案例图) */
    int BIZ_TYPE_CLUB_PROFILE = 5;
    /** 业务类型：商家资料(logo/封面/相册) */
    int BIZ_TYPE_MERCHANT_PROFILE = 6;
    /** 业务类型：据点互动模板 */
    int BIZ_TYPE_NODE_TEMPLATE = 7;
    /** 业务类型：官方活动 */
    int BIZ_TYPE_OFFICIAL_EVENT = 8;
    /** 业务类型：创意广场帖 */
    int BIZ_TYPE_SQUARE = 9;
    /** 业务类型：评论 */
    int BIZ_TYPE_COMMENT = 10;
    /** 业务类型：漫游集邮 UGC 邮票 */
    int BIZ_TYPE_ROAM_STAMP = 11;
    /** 业务类型：站内消息图片 */
    int BIZ_TYPE_IM_MESSAGE = 12;

    /** 模板发布(标题/描述/规则/题目/勋章名等自由文本 + 封面与勋章图) */
    int BIZ_TYPE_TEMPLATE = 13;

    /** 庆祝板(槽位标题与落款文字) */
    int BIZ_TYPE_CELEBRATION = 14;

    /** 漫游·附近的局封面(命中 → roam_hangout.status=2 隐藏 + 关群) */
    int BIZ_TYPE_HANGOUT = 15;

    /**
     * 主题节点 NPC 头像(npc_profile scope_type=4)。
     *
     * <p>⚠️ 不能复用 {@link #BIZ_TYPE_MERCHANT_PROFILE}:那条的 bizId 语义是 merchantId,
     * 违规回调会把 bizId 当商家 id 去清「商家资料图」—— 拿节点 NPC 的行 id 传进去,
     * 清掉的是别人家的封面/相册。bizType 与 bizId 是成对的,换了语义就必须换类型。
     */
    int BIZ_TYPE_NODE_NPC = 16;

    /**
     * 批量送检某条内容的图片（异步）。会拆分 imgArr（逗号/分号分隔），逐张调用 checkMediaAsync，
     * 并把 trace_id 与业务目标登记进 MySQL inbox；Redis 映射仅保留作迁移兼容与辅助清理。
     * best-effort：未启用 / 无图 / 送检失败均静默跳过，不阻断主流程。
     *
     * @param bizType BIZ_TYPE_TOPIC / BIZ_TYPE_ACTIVITY
     * @param bizId   内容主键
     * @param imgArr  图片地址串（逗号或分号分隔，须 https 公网可访问）
     * @param openid  发表者 openid
     */
    void submitMediaCheck(int bizType, Long bizId, String imgArr, String openid);

    /**
     * 单图送检(异步),与 submitMediaCheck 同样先登记 MySQL inbox，再把 trace_id 返回给调用方，
     * 供仍需兼容的业务表落 check_trace 列。
     * submitMediaCheck 一条内容可能有多张图、无法返回单一 trace_id，故单图业务走本方法。
     *
     * @return trace_id；未启用 / 送检失败 / inbox 登记失败 / 异常均返回 null，由调用方按发布语义决定是否阻断
     */
    String submitMediaCheckOne(int bizType, Long bizId, String imgUrl, String openid);

    /**
     * 处理微信「消息推送」回调的图片检测结果：按 trace_id 找回内容，命中违规则自动下架。
     *
     * @param traceId 送检时微信返回的 trace_id
     * @param risky   是否判定为违规（suggest=risky）
     * @return 是否由 MySQL inbox 成功匹配并完成幂等处理（false=未登记/未取得 owner/处理失败）
     */
    boolean handleMediaCheckResult(String traceId, boolean risky);

    /**
     * 完整处理微信 JSON 明文内容安全回调。验签、时效、结构、防重放和失败补偿均封装在此接口内。
     */
    CallbackResult processMediaCheckCallback(String signature, String timestamp, String nonce, String body);

    /** GET 接入验证只做 token/签名校验；Controller 只映射 HTTP 结果。 */
    CallbackResult verifyMediaCheckCallback(String signature, String timestamp, String nonce);

    /**
     * 原子登记消息推送回调 nonce，防止同一 nonce 被重复消费。
     *
     * @param nonce 微信回调 nonce
     * @return 首次登记成功时返回唯一 owner token；重复、非法或 Redis 不可用返回 null
     */
    String tryAcquireCallbackNonce(String nonce);

    /**
     * 仅由占用该 nonce 的 owner 原子释放，用于业务失败后允许微信重试。
     */
    boolean releaseCallbackNonce(String nonce, String ownerToken);

    /**
     * 当前是否启用内容安全检测（读 sys_config: wx.sec.check.enabled）。
     */
    boolean isEnabled();

    /** 获取小程序 access_token(复用内容安全的 token+redis 缓存);未配置/失败返回 ""。供订阅消息等复用,避免另起 token 管理。 */
    String getAccessToken();

    /** 微信明确返回 token 失效时强制刷新；调用方每次业务请求最多调用一次。 */
    String refreshAccessToken();

    /**
     * 以会员身份送检:按 memberId 取 openid 再调 {@link #checkText}。会员查不到时 openid 为 null,
     * 由 checkText 按既有口径 fail-closed 拦截。PR10 复审:收拢各写口里重复的「查 openid → checkText」。
     */
    static String checkTextAsMember(IWxContentSecurityService security, IUmsMemberService members,
                                    Long memberId, String text)
    {
        com.chengyinhub.business.domain.UmsMember actor = members.selectUmsMemberById(memberId);
        return security.checkText(text, actor == null ? null : actor.getWxOpenId());
    }

    /**
     * 拼接多个文本字段为一段送检文本（自动忽略空字段，用换行分隔）。
     * 供各发布接口把「标题+副标题+正文」合并后一次性检测。
     */
    static String joinText(String... parts)
    {
        if (parts == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts)
        {
            if (p != null && p.trim().length() > 0)
            {
                if (sb.length() > 0)
                {
                    sb.append('\n');
                }
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }
}

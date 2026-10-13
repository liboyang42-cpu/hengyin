package com.chengyinhub.business.security;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.ViewOrder;
import com.chengyinhub.business.domain.ViewCollaborators;
import com.chengyinhub.common.enums.DesensitizedType;

/**
 * 后台展示面的 PII 保护:调用方无「看全量」权限时,把明文替换成脱敏值,再交给序列化/导出。
 *
 * <p>脱敏必须发生在后端:TypeHandler 在 ORM 边界已把密文解成明文,若只在 Vue 模板里掐字符,
 * 明文仍然完整躺在 /list 的 JSON 响应和 /export 的 xlsx 里。
 *
 * <p>权限判定留在 Controller(framework 层),本类只做字段映射,以免 system 模块反向依赖 framework。
 *
 * @author chengyin
 */
public final class AdminPiiProtector
{
    /** 地址保留的前缀长度:中文地址前缀是省/市/区,粗粒度、对运营有用且不定位到人。 */
    private static final int ADDRESS_KEEP_PREFIX = 6;

    private AdminPiiProtector()
    {
    }

    /**
     * 就地脱敏报名记录的 PII。
     *
     * <p>就地改对象是安全的:没有任何 mapper 声明 &lt;cache/&gt;,二级缓存实际未启用,
     * 一级缓存随 SqlSession 结束即废弃,不存在把脱敏值写进共享缓存的风险。
     */
    public static void maskInPlace(CmsRegistration registration)
    {
        if (registration == null)
        {
            return;
        }
        registration.setRealName(PiiCryptoService.maskName(registration.getRealName()));
        registration.setPhone(PiiCryptoService.maskPhone(registration.getPhone()));
        registration.setEmail(maskEmail(registration.getEmail()));
        registration.setAddress(maskAddress(registration.getAddress()));
    }

    /**
     * 把无权限调用方传来的 PII 字段一律置 null。两个调用点都要用:
     *
     * <p><b>1. 写回(update)</b>:updateCmsRegistration 的动态 &lt;if test="phone != null"&gt; 会跳过 null 列,
     * 于是原值原封不动。不这么做的话,前端编辑框里的脱敏串 "138****1234" 会被当成真值加密写回,
     * 直接毁掉真实手机号。
     *
     * <p><b>2. 检索(query 入参)</b>:CmsRegistration 同时是列表/导出的查询条件对象,而 mapper 对 phone
     * 做盲索引精确匹配。若不清掉,无权限者看到 138****1234 后,只需枚举中间四位 0000-9999,
     * 哪次返回该行哪次就是真号——最多 1 万次请求就能把脱敏完全反推掉。按 PII 检索本身就是 PII 能力。
     */
    public static void stripPii(CmsRegistration registration)
    {
        if (registration == null)
        {
            return;
        }
        registration.setRealName(null);
        registration.setPhone(null);
        registration.setEmail(null);
        registration.setAddress(null);
    }

    /**
     * 就地脱敏订单列表/详情的 PII(收件人姓名、收件电话、详细地址、下单人手机)。
     *
     * <p>不动 nickname:它是用户自选的展示名,小程序里本就公开可见,遮了只会让后台认不出人,
     * 挡不住任何本来挡得住的东西。receiverProvince 只到省、receiverPhoneCode 是国际区号,同理不动。
     */
    public static void maskInPlace(ViewOrder order)
    {
        if (order == null)
        {
            return;
        }
        order.setReceiverName(PiiCryptoService.maskName(order.getReceiverName()));
        order.setReceiverPhone(PiiCryptoService.maskPhone(order.getReceiverPhone()));
        order.setReceiverDetailAddress(maskAddress(order.getReceiverDetailAddress()));
        order.setPhone(PiiCryptoService.maskPhone(order.getPhone()));
    }

    /** 就地脱敏订单导出行。OmsOrder 没有下单人 phone/nickname,只有收件信息。 */
    public static void maskInPlace(OmsOrder order)
    {
        if (order == null)
        {
            return;
        }
        order.setReceiverName(PiiCryptoService.maskName(order.getReceiverName()));
        order.setReceiverPhone(PiiCryptoService.maskPhone(order.getReceiverPhone()));
        order.setReceiverDetailAddress(maskAddress(order.getReceiverDetailAddress()));
    }

    /**
     * 同 {@link #stripPii(CmsRegistration)}:updateOmsOrder 的 &lt;if&gt; 会跳过 null 列;
     * 且 selectOmsOrderList(导出用)对 receiverPhone 做盲索引精确匹配,不清掉同样能被枚举反推。
     */
    public static void stripPii(OmsOrder order)
    {
        if (order == null)
        {
            return;
        }
        order.setReceiverName(null);
        order.setReceiverPhone(null);
        order.setReceiverDetailAddress(null);
    }

    /**
     * ViewOrder 是订单列表的查询条件对象:selectViewOrderList 对下单人 phone 做明文等值匹配
     * (该列走 join、未加密),不清掉照样能枚举反推。
     *
     * <p>不清 nickname:它没被脱敏(自选展示名、小程序本就公开),留着搜索才符合直觉。
     */
    public static void stripPii(ViewOrder order)
    {
        if (order == null)
        {
            return;
        }
        order.setReceiverName(null);
        order.setReceiverPhone(null);
        order.setReceiverDetailAddress(null);
        order.setPhone(null);
    }

    /**
     * 就地脱敏会员的 PII,并抹掉密码哈希。
     *
     * <p>★ password 必须置 <b>null</b> 而不是空串:updateUmsMember 的动态 if 是
     * {@code <if test="password != null">}——<b>只判 null、不判空串</b>。而后台 member 编辑表单
     * 里有密码输入框、会把 getInfo 的返回值整体 PUT 回来。若置成空串,管理员点一次保存
     * 就把密码写成空串,该用户再也登不上。
     * (ApiUmsMemberController.sanitizeForOthers 用的是空串——那条路径是只读的,所以它安全;
     *  照抄到这条会写回的路径上就是事故。)
     *
     * <p>name 一并脱敏:该字段标着「真实姓名」,实测 49 行里 13 行是用户自填的,
     * 虽无实名认证流程、装的未必是真名,但按 PII 从严处理。
     */
    public static void maskInPlace(UmsMember member)
    {
        if (member == null)
        {
            return;
        }
        member.setPassword(null);
        member.setPhone(PiiCryptoService.maskPhone(member.getPhone()));
        member.setName(PiiCryptoService.maskName(member.getName()));
    }

    /**
     * 把会员实体清成「可以给陌生人看」的样子:只留 id/昵称/头像 这类公开资料。
     *
     * <p>用在<b>C 端</b>把某个会员(如主题发布者)整体下发的出口。与后台的 maskInPlace 分开,
     * 是因为口径不同:后台运营需要脱敏值来认人(138****1234),而陌生用户连脱敏值都不该看到。
     *
     * <p>口径对齐仓内既有的 ApiUmsMemberController.sanitizeForOthers,并补上它没清的
     * unionId/appleId/loginIp——都是账号标识符/行为数据,陌生人一样不该拿到。
     *
     * <p>★ 为什么不把脱敏下沉到 UmsMemberServiceImpl.selectUmsMemberById:它有 90+ 个调用方,
     * 登录口令比对、内容安全送检要的 wxOpenId、积分结算都靠明文干活,下沉会**静默**打断它们。
     * 所以只在「对外出口」这一层清。
     *
     * <p>password 这里置空串而非 null 是安全的:C 端这几条是只读展示路径,不存在把返回值
     * 原样 PUT 回来的链路(与后台 member 编辑表单不同,那边必须用 null,见 maskInPlace(UmsMember))。
     * 保持与既有 sanitizeForOthers 一致。
     */
    public static void sanitizeMemberForPublic(UmsMember member)
    {
        if (member == null)
        {
            return;
        }
        member.setPassword("");
        member.setUserType(null);
        member.setRole(null);
        member.setPhone(null);
        member.setLevelId(null);
        member.setName(null);
        member.setWechat(null);
        member.setStatus(null);
        member.setInviteUserId(null);
        member.setWxOpenId(null);
        member.setUnionId(null);
        member.setAppleId(null);
        member.setLoginIp(null);
        member.setLoginTime(null);
        member.setSex(null);
        member.setAreaId(null);
        member.setBirthday(null);
        member.setMark(null);
        member.setTagIds(null);
        member.setCasePics(null);
        member.setCaseIntroducton(null);
        member.setWebsite(null);
        member.setWorkAddress(null);
        member.setIntroduction(null);
        member.setBalance(null);
        member.setPoint(null);
        member.setDelFlag(null);
        member.setDeregisterStatus(null);
        member.setDeregisterApplyTime(null);
        member.setDeregisterExecuteAfter(null);
        member.setMerchant(null);
        member.setTopicNum(null);
        member.setActivityNum(null);
        member.setFollowNum(null);
        member.setIsFollow(null);
        member.setFansNum(null);
        member.setLikeNum(null);
        member.setStartTime(null);
        member.setEndTime(null);
        member.setJoinUserNum(null);
        member.setSysCategoryList(null);
        member.setCreateBy(null);
        member.setCreateTime(null);
        member.setUpdateBy(null);
        member.setUpdateTime(null);
        member.setRemark(null);
        member.setParams(null);
    }

    /**
     * ★ 本次脱敏动作**自己引入**的回写风险,必须配套堵上。
     *
     * <p>ViewCollaboratorsController 有 getInfo(:66)与 edit(:88),构成读→写回链。
     * 在 service 层脱敏之前,getInfo 返回真手机号,原样 PUT 回来无害;脱敏之后返回的是掩码,
     * 而 updateViewCollaborators 里有
     * {@code <if test="memberPhone != null">member_phone = #{memberPhone},</if>}
     * ——掩码会被写进 view_collaborators,并经 MySQL 可更新视图传导到基表 ums_member.phone,
     * 真手机号当场毁掉。
     *
     * <p>这两列是聚合维护的视图列,本就不该由后台编辑写入,故一律清掉(而非看权限)。
     */
    public static void stripPii(ViewCollaborators row)
    {
        if (row == null)
        {
            return;
        }
        row.setMemberPhone(null);
        row.setMemberRealName(null);
    }

    /** 无「看全量」权限者提交的会员更新:PII 与密码一律置 null,让动态 if 跳过,原值不动。 */
    public static void stripPii(UmsMember member)
    {
        if (member == null)
        {
            return;
        }
        member.setPhone(null);
        member.setName(null);
    }

    /**
     * 邮箱脱敏:保留本地部分首字符与完整域名,如 zhangsan@example.com -> z****@example.com。
     *
     * <p>不能用 maskMiddle:它保留尾部,而邮箱尾部是域名+本地部分末字符,遮不住人。
     */
    public static String maskEmail(String email)
    {
        if (email == null || email.isEmpty())
        {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0)
        {
            // 不像邮箱:DesensitizedType.EMAIL 的正则不匹配时会把原串原样返回(=泄漏),
            // 故这里不能交给它,退化成通用中间掩码,总之不原样返回。
            return PiiCryptoService.maskMiddle(email);
        }
        return DesensitizedType.EMAIL.desensitizer().apply(email);
    }

    /**
     * 地址脱敏:只保留粗粒度前缀,其余抹掉,如 北京市朝阳区建国路88号 -> 北京市朝阳区****。
     *
     * <p>不能用 maskMiddle:它保留尾部,而地址尾部正是门牌号——最能定位到人的一段。
     */
    public static String maskAddress(String address)
    {
        if (address == null || address.isEmpty())
        {
            return address;
        }
        if (address.length() <= ADDRESS_KEEP_PREFIX)
        {
            return "****";
        }
        return address.substring(0, ADDRESS_KEEP_PREFIX) + "****";
    }
}

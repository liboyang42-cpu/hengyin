package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMemberIdentity;
import com.chengyinhub.business.mapper.UmsMemberIdentityMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.IdCardUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.uuid.IdUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 发布者实名登记(RUN-52 改判后口径)。
 *
 * <p>立论:要收钱就得知道收钱的人是谁。姓名 + 身份证号只在**发布侧**采集,
 * 个人中心没有这一行、也没有任何展示页 —— 它是平台的合规材料,不是用户的社交资料。
 *
 * <p>三条硬口径写在这里而不是散在各控制器:
 * <ul>
 *   <li><b>只回显状态</b>:{@link #checkRegistered} 只回答「登记了没有」,明文永不下发;</li>
 *   <li><b>不叫「认证」</b>:没有三要素通道,我们只做到号码自洽(见 {@link IdCardUtils}),
 *       所以文案统一用「已登记 / 发布者实名」;</li>
 *   <li><b>改绑走人工</b>:重复提交只有证件一致才幂等成功,不一致直接拒,不给自助覆盖。</li>
 * </ul>
 */
@Service
public class MemberIdentityService {

    /** consent_record 的文档类型:实名登记的单独同意留痕走全站同一张 append-only 表。 */
    public static final String CONSENT_DOC_TYPE = "publisher_identity";
    public static final String CONSENT_DOC_VERSION = "v1";

    /** 采集入口白名单。写进 ums_member_identity.source,出问题时知道是哪条链路收的。 */
    public static final String SOURCE_CLUB_APPLY = "club_apply";
    public static final String SOURCE_MERCHANT_APPLY = "merchant_apply";
    public static final String SOURCE_TOPIC_PUBLISH = "topic_publish";
    private static final Set<String> SOURCES =
            new HashSet<>(Arrays.asList(SOURCE_CLUB_APPLY, SOURCE_MERCHANT_APPLY, SOURCE_TOPIC_PUBLISH));

    /** 未登记时的统一文案。三个入口共用一句,别在三处各写一遍再各自漂移。 */
    public static final String MSG_NOT_REGISTERED = "请先登记实名信息(姓名与身份证号),再发布";

    private static final int NAME_MIN = 2;
    private static final int NAME_MAX = 20;

    @Autowired
    private UmsMemberIdentityMapper identityMapper;

    @Autowired
    private IConsentRecordService consentRecordService;

    /**
     * 登记一次。已登记且证件相同 → 幂等成功(双击、弱网重发不会报错也不会覆盖);
     * 证件不同 → 拒绝,改绑只走人工。
     *
     * @param consent 前端「同意提供真实姓名与身份证号」勾选,必须由用户显式给出(PIPL 第29条单独同意)
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(Long memberId, String realName, String idCard, boolean consent,
                         String source, String ipAddress) {
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录");
        }
        if (!SOURCES.contains(source)) {
            throw new ServiceException("实名登记入口不合法,请从发布页重新提交");
        }
        if (!consent) {
            throw new ServiceException("请先勾选同意提供真实姓名与身份证号");
        }
        String name = realName == null ? "" : realName.trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new ServiceException("请填写真实姓名(2-" + NAME_MAX + " 个字)");
        }
        if (StringUtils.containsAny(name, "0123456789")) {
            throw new ServiceException("姓名里不应包含数字");
        }
        String normalizedId = IdCardUtils.normalize(idCard);
        if (!IdCardUtils.isValid(normalizedId)) {
            throw new ServiceException("身份证号格式不正确,请核对后重填");
        }

        UmsMemberIdentity identity = new UmsMemberIdentity();
        identity.setMemberId(memberId);
        identity.setRealName(name);
        identity.setIdCard(normalizedId);
        identity.setSource(source);
        Date now = new Date();
        identity.setConsentTime(now);
        try {
            identityMapper.insertIdentity(identity);
        } catch (DuplicateKeyException e) {
            // uk_member 撞车:要么是同一次提交的重发,要么是换了一张证。
            // 前者幂等放行,后者按「改绑走人工」拒掉。
            UmsMemberIdentity stored = identityMapper.selectByMemberId(memberId);
            if (stored == null || !normalizedId.equalsIgnoreCase(stored.getIdCard())) {
                throw new ServiceException("实名信息已登记,如需变更请联系平台客服");
            }
            return;
        }
        consentRecordService.record(memberId, CONSENT_DOC_TYPE, CONSENT_DOC_VERSION, source,
                "AGREE", IdUtils.fastSimpleUUID(), ipAddress);
    }

    /** 发布闸:未登记返回统一文案,已登记返回 null。口径与 quotaService.checkCreate 一致。 */
    public String checkRegistered(Long memberId) {
        return isRegistered(memberId) ? null : MSG_NOT_REGISTERED;
    }

    /** 只回答「有没有」,不把明文带出这条调用栈。 */
    public boolean isRegistered(Long memberId) {
        return memberId != null && memberId > 0 && identityMapper.existsByMemberId(memberId);
    }
}

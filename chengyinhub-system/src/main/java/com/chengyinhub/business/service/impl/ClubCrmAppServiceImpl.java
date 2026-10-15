package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.mapper.ClubCrmMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IClubCrmAppService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 俱乐部客户(按人聚合)。
 *
 * ★ 三档权限,都用现成的判据,不新增权限码:
 *   ① 能不能看客户            = clubMemberService.canGovernClub(主理人 / 管理员 / MEMBER_MANAGE)
 *   ② 能不能看金额            = ClubPermission.FINANCE_READ(策略表里只有 OWNER / CO_OWNER 有)
 *   ③ 能不能看(脱敏)手机号   = ClubPermission.MEMBER_LIST_READ
 *      EVENT_CHECKIN(核销员)两条都没有 ⇒ 拿到的是替代说明,不是号码 —— 与 R1「核销员默认
 *      看不到完整手机号」一致。
 *
 * ★ 手机号:任何一档都**不下发原始号码**。有权限的看到 PiiCryptoService.maskPhone 的脱敏串,
 *   没权限的看到一句替代说明。前端因此没有"藏起来"的机会,也就没有"藏漏了"的可能。
 *   ⚠️ 明文只在本类的局部变量里活过一行,VO 上根本没有 phone 这个字段(见 ClubCustomerVO)。
 *
 * ★ 清退退款仍然是 owner-only 的另一个接口,本类一个写路径都没碰。
 */
@Service
public class ClubCrmAppServiceImpl implements IClubCrmAppService {

    /** 无手机号查看权限时给的替代说明(Figma K1 里苏禾那一行)。 */
    public static final String PHONE_UNAVAILABLE_TEXT = "由俱乐部带团，请联系领队";

    private static final int LIST_LIMIT = 200;
    private static final int RECORD_LIMIT = 50;
    private static final int MAX_TAGS = 8;
    private static final int MAX_TAG_LENGTH = 12;
    private static final int MAX_REMARK_LENGTH = 200;

    @Autowired
    private ClubCrmMapper clubCrmMapper;
    @Autowired
    private PiiCryptoService piiCryptoService;

    @Autowired
    private IClubService clubService;

    @Autowired
    private IClubMemberService clubMemberService;

    @Autowired
    private IClubAccessService clubAccessService;

    @Override
    public ClubCustomerVO.ListPage listCustomers(Long principalMemberId, Long clubId, String filter, String keyword) {
        Club club = requireGovernable(principalMemberId, clubId);
        boolean canSeePhone = hasPermission(principalMemberId, clubId, ClubPermission.MEMBER_LIST_READ);

        List<ClubCustomerVO.RawCustomer> raws =
                clubCrmMapper.selectClubCustomers(club.getId(), normalizedFilter(filter), trimmed(keyword), LIST_LIMIT);
        Map<Long, ClubCustomerVO.Profile> profiles = profileMap(club.getId());

        ClubCustomerVO.ListPage page = new ClubCustomerVO.ListPage();
        page.setTotal(clubCrmMapper.countClubCustomers(club.getId()));
        page.setMonthNew(clubCrmMapper.countClubCustomersNewThisMonth(club.getId()));
        page.setCanEdit(Boolean.TRUE);
        List<ClubCustomerVO.Row> items = new ArrayList<>();
        for (ClubCustomerVO.RawCustomer raw : raws) {
            ClubCustomerVO.Profile profile = profiles.get(raw.getMemberId());
            ClubCustomerVO.Row row = new ClubCustomerVO.Row();
            row.setMemberId(raw.getMemberId());
            row.setDisplayName(participantDisplayName(null, raw.getDisplayName(), raw.getMemberId()));
            row.setAvatar(raw.getAvatar());
            row.setVerifiedCount(nullToZero(raw.getVerifiedCount()));
            row.setPendingCount(nullToZero(raw.getPendingCount()));
            row.setLastVisitDate(formatDay(raw.getLastInteractionTime()));
            row.setLastTopicName(raw.getLastTopicName());
            row.setRemark(profile == null ? "" : trimmed(profile.getRemark()));
            row.setPhoneText(phoneText(raw.getPhone(), canSeePhone));
            items.add(row);
        }
        page.setItems(items);
        return page;
    }

    @Override
    public int customerCount(Long principalMemberId, Long clubId) {
        Club club = requireGovernable(principalMemberId, clubId);
        Integer total = clubCrmMapper.countClubCustomers(club.getId());
        return total == null ? 0 : total;
    }

    @Override
    public ClubCustomerVO.Detail customerDetail(Long principalMemberId, Long clubId, Long customerMemberId) {
        Club club = requireGovernable(principalMemberId, clubId);
        if (customerMemberId == null) throw new ServiceException("缺少客户ID");

        ClubCustomerVO.RawCustomer raw = clubCrmMapper.selectClubCustomerSummary(club.getId(), customerMemberId);
        if (raw == null) throw new ServiceException("这位客户没有在本俱乐部的成交记录");

        boolean canSeePhone = hasPermission(principalMemberId, clubId, ClubPermission.MEMBER_LIST_READ);
        boolean canSeeAmount = hasPermission(principalMemberId, clubId, ClubPermission.FINANCE_READ);

        ClubCustomerVO.Summary summary = new ClubCustomerVO.Summary();
        summary.setMemberId(raw.getMemberId());
        summary.setDisplayName(participantDisplayName(null, raw.getDisplayName(), raw.getMemberId()));
        summary.setAvatar(raw.getAvatar());
        summary.setPhoneText(phoneText(raw.getPhone(), canSeePhone));
        summary.setLastInteractionTime(raw.getLastInteractionTime());
        summary.setArrivedCount(nullToZero(raw.getVerifiedCount()));
        summary.setPendingCount(nullToZero(raw.getPendingCount()));
        summary.setRefundedCount(nullToZero(raw.getRefundedCount()));
        // K2-B:没有 FINANCE_READ 就不下发金额。给 0 会被读成"这人没花过钱",那是假事实。
        summary.setPaidAmount(canSeeAmount ? raw.getPaidAmount() : null);

        ClubCustomerVO.Detail detail = new ClubCustomerVO.Detail();
        detail.setSummary(summary);
        detail.setRecords(clubCrmMapper.selectClubCustomerRecords(club.getId(), customerMemberId, RECORD_LIMIT));
        ClubCustomerVO.Profile profile = clubCrmMapper.selectProfile(club.getId(), customerMemberId);
        detail.setTags(parseTags(profile == null ? null : profile.getTagsJson()));
        detail.setRemark(profile == null ? "" : trimmed(profile.getRemark()));
        detail.setCanEdit(Boolean.TRUE);
        return detail;
    }

    @Override
    public void saveTagRemark(Long principalMemberId, Long clubId, Long customerMemberId,
                              List<String> tags, String remark, String requestId) {
        Club club = requireGovernable(principalMemberId, clubId);
        if (customerMemberId == null) throw new ServiceException("缺少客户ID");
        // 只能给"真的在本俱乐部成交过"的人建档,否则这张表会变成任意 memberId 的便签本
        if (clubCrmMapper.selectClubCustomerSummary(club.getId(), customerMemberId) == null) {
            throw new ServiceException("这位客户没有在本俱乐部的成交记录");
        }
        String id = trimmed(requestId);
        if (id.isEmpty() || id.length() > 64) throw new ServiceException("缺少有效的请求标识");

        List<String> cleaned = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                String name = trimmed(tag);
                if (name.isEmpty()) continue;
                if (name.length() > MAX_TAG_LENGTH) throw new ServiceException("单个标签最多 " + MAX_TAG_LENGTH + " 字");
                if (!cleaned.contains(name)) cleaned.add(name);
            }
        }
        if (cleaned.size() > MAX_TAGS) throw new ServiceException("标签最多 " + MAX_TAGS + " 个");
        String note = trimmed(remark);
        if (note.length() > MAX_REMARK_LENGTH) throw new ServiceException("备注最多 " + MAX_REMARK_LENGTH + " 字");

        clubCrmMapper.upsertProfile(club.getId(), customerMemberId, JSON.toJSONString(cleaned), note,
                principalMemberId, id);
    }

    @Override
    public ClubCustomerVO.TopicPage topicCustomers(Long principalMemberId, Long clubId, Long topicId, String filter) {
        if (principalMemberId == null) throw new ServiceException("请先登录");
        if (clubId == null || topicId == null) throw new ServiceException("缺少俱乐部或主题ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) throw new ServiceException("俱乐部不存在");
        // 核销员要按名单点人,所以这一屏对 EVENT_CHECKIN 开放;但它没有 MEMBER_LIST_READ,
        // 拿到的手机号那一格是替代说明,不是号码。
        boolean governs = clubMemberService.canGovernClub(club, principalMemberId);
        boolean checksIn = hasPermission(principalMemberId, clubId, ClubPermission.EVENT_CHECKIN);
        if (!governs && !checksIn) throw new ServiceException("当前岗位没有客户查看权限");
        boolean canSeePhone = hasPermission(principalMemberId, clubId, ClubPermission.MEMBER_LIST_READ);

        List<ClubCustomerVO.RawTopicRow> raws = clubCrmMapper.selectTopicCustomers(clubId, topicId);
        String wanted = normalizedTopicFilter(filter);

        int sold = 0, pending = 0, contacted = 0, verified = 0;
        Map<String, ClubCustomerVO.Session> sessions = new LinkedHashMap<>();
        SimpleDateFormat sessionFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm");
        for (ClubCustomerVO.RawTopicRow raw : raws) {
            String statusCode = topicStatusCode(raw);
            if ("REFUNDED".equals(statusCode) || "CANCELLED".equals(statusCode)) {
                // 已退款不计入"已售"
            } else {
                sold++;
            }
            if ("PENDING".equals(statusCode)) pending++;
            if ("CONTACTED".equals(statusCode)) contacted++;
            // ★「已评价」是「已核销」之后的一档,不是它的替代:核销数不能因为客户评了价就掉一个,
            //   「已核销」这个筛选也不能把评过价的人筛没了。新增一档只加末端,不挪走已有口径。
            if (isVerifiedOrLater(statusCode)) verified++;
            if (wanted != null && !matchesTopicFilter(wanted, statusCode)) continue;

            String sessionKey = "s-" + (raw.getSessionId() == null ? 0L : raw.getSessionId());
            ClubCustomerVO.Session session = sessions.get(sessionKey);
            if (session == null) {
                session = new ClubCustomerVO.Session();
                session.setKey(sessionKey);
                session.setTimeText(raw.getSessionTime() == null ? "时间待定" : sessionFormat.format(raw.getSessionTime()));
                sessions.put(sessionKey, session);
            }
            ClubCustomerVO.TopicRow row = new ClubCustomerVO.TopicRow();
            row.setKey("reg-" + raw.getRegistrationId());
            row.setDisplayName(participantDisplayName(raw.getParticipantName(), raw.getDisplayName(), raw.getMemberId()));
            row.setAvatar(raw.getAvatar());
            row.setOrderNo(raw.getOrderNo());
            row.setPhoneText(phoneText(raw.getPhone(), canSeePhone));
            row.setStatusCode(statusCode);
            row.setStatusText(topicStatusText(statusCode));
            row.setTimeText(raw.getVerificationTime() == null ? "—" : clockFormat.format(raw.getVerificationTime()));
            session.getRows().add(row);
        }

        ClubCustomerVO.TopicPage page = new ClubCustomerVO.TopicPage();
        page.setSoldCount(sold);
        page.setPendingCount(pending);
        page.setContactedCount(contacted);
        page.setVerifiedCount(verified);
        page.setSessions(new ArrayList<>(sessions.values()));
        return page;
    }

    /**
     * 第二轮拍板第 1 条:活动详情页的主理人侧字段,页面只读不猜。
     *
     * <p>权限全部按后端现有俱乐部角色:
     * <ul>
     *   <li>人数三项与 {@link #topicCustomers} 同口径 —— 主理人/管理员(canGovernClub)或核销员(EVENT_CHECKIN)
     *       才给数,其他人给 null(「看不到」不是「0 人」);</li>
     *   <li>canViewVerify / canManageSessions / canDirect / canReviewChapterApplications 分别对齐核销台账、场次管理、导演台、章节承接审核的后端判据,
     *       页面三块各读各的(总控复查裁定,取代原来共用的 canManageEvents)。</li>
     * </ul>
     * 主题不属于本俱乐部(或已删)一律「主题不存在」,不回任何数。
     */
    @Override
    public Map<String, Object> topicManageStats(Long principalMemberId, Long clubId, Long topicId, Long activityId) {
        if (principalMemberId == null) throw new ServiceException("请先登录");
        if (clubId == null || topicId == null) throw new ServiceException("缺少俱乐部或主题ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) throw new ServiceException("俱乐部不存在");
        Map<String, Object> row = clubCrmMapper.selectTopicManageStats(clubId, topicId, activityId, principalMemberId);
        if (row == null) throw new ServiceException("主题不存在");

        ClubAccessContext access;
        try {
            access = clubAccessService.resolve(principalMemberId, clubId, null);
        } catch (ServiceException notAMember) {
            access = null;   // 停用/非成员:照常给站数,人数与管理身份一律不给
        }
        // 总控复查裁定:三块功能各给各的后端判据,不再共用一个 canManageEvents。
        // 核销区:与核销台账(topicCustomers/checkinDetail)同口径 —— 主理人/管理员(canGovernClub)或核销员。
        boolean canViewVerify = clubMemberService.canGovernClub(club, principalMemberId)
                || (access != null && access.hasPermission(ClubPermission.EVENT_CHECKIN));
        // 场次管理:与 ClubEventOpsServiceImpl.requireActivityManage 同口径 —— ACTIVITY_MANAGE 且主理人/联席主理人。
        boolean canManageSessions = access != null
                && principalMemberId.equals(access.getPrincipalMemberId())
                && clubId.equals(access.getClubId())
                && access.getOwnerMemberId() != null && access.getOwnerMemberId() > 0
                && access.hasPermission(ClubPermission.ACTIVITY_MANAGE)
                && access.getRoleCodes() != null
                && (access.getRoleCodes().contains(com.chengyinhub.business.access.club.ClubRoleCode.CLUB_OWNER)
                    || access.getRoleCodes().contains(com.chengyinhub.business.access.club.ClubRoleCode.CLUB_CO_OWNER));
        // 导演台:与 GameSessionRuntimeServiceImpl.requireClubOwner 同一个判据(isTopicOwner:主题有指向本俱乐部的
        // 生效合作单且查看者是主理人)。俱乐部自建、没有合作单的主题这里是 false,页面不给必失败的导演台入口。
        boolean canDirect = clubMemberService.isTopicOwner(topicId, clubId, principalMemberId);
        boolean canReviewChapterApplications = principalMemberId.equals(toLong(row.get("ownerMemberId")));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeCount", toInt(row.get("nodeCount")));
        data.put("sessionHeadcount", canViewVerify ? toInt(row.get("sessionHeadcount")) : null);
        data.put("pendingVerifyCount", canViewVerify ? toInt(row.get("pendingVerifyCount")) : null);
        data.put("verifiedByMeCount", canViewVerify ? toInt(row.get("verifiedByMeCount")) : null);
        // 本场真状态(NOT_PREPARED/PREPARING/READY/RUNNING/FINISHED/CANCELLED):核销员没有导演台 projection,
        // 核销区靠它判「进行中/已结束」;只给能看核销的人,与人数三项同口径。
        data.put("sessionStatus", canViewVerify ? row.get("sessionStatus") : null);
        data.put("canViewVerify", canViewVerify);
        data.put("canManageSessions", canManageSessions);
        data.put("canDirect", canDirect);
        data.put("canReviewChapterApplications", canReviewChapterApplications);
        return data;
    }

    private static Integer toInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static Long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    /**
     * Figma K3 核销详情 · 俱乐部端。
     *
     * 权限与 topicCustomers 同口径:主理人/管理员看得到,核销员也看得到(他要按名单点人),
     * 但手机号那一格只有 MEMBER_LIST_READ 才给号码 —— 由 phoneText 统一裁,
     * 前端原样显示。没权限时给的是替代说明,不是空字符串:空会被读成「这客户没留电话」。
     */
    @Override
    public ClubCustomerVO.CheckinDetail checkinDetail(Long principalMemberId, Long clubId, Long registrationId) {
        if (principalMemberId == null) throw new ServiceException("请先登录");
        if (clubId == null || registrationId == null) throw new ServiceException("缺少俱乐部或报名单ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) throw new ServiceException("俱乐部不存在");
        boolean governs = clubMemberService.canGovernClub(club, principalMemberId);
        boolean checksIn = hasPermission(principalMemberId, clubId, ClubPermission.EVENT_CHECKIN);
        if (!governs && !checksIn) throw new ServiceException("当前岗位没有核销查看权限");
        boolean canSeePhone = hasPermission(principalMemberId, clubId, ClubPermission.MEMBER_LIST_READ);

        ClubCustomerVO.RawCheckinRow raw = clubCrmMapper.selectCheckinDetail(clubId, registrationId);
        if (raw == null) throw new ServiceException("这条核销记录不存在，或不属于本俱乐部");

        SimpleDateFormat dayTime = new SimpleDateFormat("MM-dd HH:mm");
        SimpleDateFormat sessionFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm");

        ClubCustomerVO.RawTopicRow probe = new ClubCustomerVO.RawTopicRow();
        probe.setPaymentStatus(raw.getPaymentStatus());
        probe.setRegistrationStatus(raw.getRegistrationStatus());
        probe.setPayableAmount(raw.getPayableAmount());
        probe.setPointUsed(raw.getPointUsed());
        probe.setVerificationStatus(raw.getVerificationStatus());
        // ⚠️ 这个 probe 少喂一个字段,对应的状态档就永远算不出来。
        //    2026-09-11 加「已评价」时就漏了它 ⇒ railStep=3 成了死分支,而前端第四格文案
        //    已经改成「已评价」—— 注释替代码许愿的典型。列表与详情必须喂同一组字段。
        probe.setReviewTime(raw.getReviewTime());
        String statusCode = topicStatusCode(probe);

        ClubCustomerVO.CheckinDetail out = new ClubCustomerVO.CheckinDetail();
        out.setDisplayName(participantDisplayName(raw.getParticipantName(), raw.getDisplayName(), null));
        out.setAvatar(raw.getAvatar());
        out.setPhoneText(phoneText(raw.getPhone(), canSeePhone));
        out.setPhoneVisible(canSeePhone);
        out.setSessionTimeText(raw.getSessionTime() == null ? "时间待定" : sessionFormat.format(raw.getSessionTime()));
        out.setStatusCode(statusCode);
        out.setStatusText(topicStatusText(statusCode));
        out.setStatusTimeText(raw.getVerificationTime() == null ? "" : clockFormat.format(raw.getVerificationTime()));
        out.setTopicName(raw.getTopicName());
        out.setTopicCover(raw.getTopicCover());
        out.setOrderNo(raw.getOrderNo());
        // 票种与份数:任一缺失就只给拿到的那半,不用 ×1 冒充「买了一份」
        String ticket = trimmed(raw.getTicketName());
        Long num = raw.getOrderNum();
        out.setTicketText(ticket.isEmpty() ? "—" : (num == null ? ticket : (ticket + " ×" + num)));
        out.setOrderTimeText(raw.getPaymentTime() == null ? "—" : dayTime.format(raw.getPaymentTime()));
        // 金额算不出来给「—」,不给 ¥0.00 —— ¥0 会被读成免费票
        out.setPaidAmountText(raw.getPayableAmount() == null ? "—"
                : ("¥" + raw.getPayableAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
        out.setVerifyTimeText(raw.getVerificationTime() == null ? "—" : dayTime.format(raw.getVerificationTime()));
        out.setStoreName(blankToDash(raw.getStoreName()));
        out.setOperatorName(blankToDash(raw.getOperatorName()));
        // 履约轨迹:已退款不走轨迹(-1),其余走到哪亮到哪
        out.setRailStep(("REFUNDED".equals(statusCode) || "CANCELLED".equals(statusCode)) ? -1
                : ("REVIEWED".equals(statusCode) ? 3
                : ("VERIFIED".equals(statusCode) ? 2 : ("CONTACTED".equals(statusCode) ? 1 : 0))));
        out.setRegistrationId(registrationId);
        // 清退退款 owner-only,且只对「已付款未核销」的单成立 —— 与名册
        // ApiClubController 的 roster.setCanRefund 同口径。已核销的单退不了
        // (服务端也会拒),这里就不该给按钮;核销员看得到这一屏但不该看到退款。
        boolean refundableStatus = "PENDING".equals(statusCode) || "CONTACTED".equals(statusCode);
        out.setCanRefund(refundableStatus
                && club.getMemberId() != null && club.getMemberId().equals(principalMemberId));
        return out;
    }

    private static String blankToDash(String v) { return v == null || v.trim().isEmpty() ? "—" : v.trim(); }

    // ---- 内部工具 ----

    private Club requireGovernable(Long principalMemberId, Long clubId) {
        if (principalMemberId == null) throw new ServiceException("请先登录");
        if (clubId == null) throw new ServiceException("缺少俱乐部ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) throw new ServiceException("俱乐部不存在");
        if (!clubMemberService.canGovernClub(club, principalMemberId)) {
            throw new ServiceException("当前岗位没有客户查看权限");
        }
        return club;
    }

    private boolean hasPermission(Long principalMemberId, Long clubId, String permission) {
        ClubAccessContext access = clubAccessService.resolve(principalMemberId, clubId, null);
        return access != null && access.hasPermission(permission);
    }

    /**
     * 手机号出口只有这一处。有权限 = 脱敏串(前3后4),没权限 = 一句说明。
     * 两条路都不返回原始号码 —— 这是"脱敏必须在服务端做"的落点。
     */
    public static String phoneText(String rawPhone, boolean canSeePhone) {
        if (!canSeePhone) return PHONE_UNAVAILABLE_TEXT;
        String phone = rawPhone == null ? "" : rawPhone.trim();
        if (phone.isEmpty()) return "";
        return PiiCryptoService.maskPhone(phone);
    }

    private Map<Long, ClubCustomerVO.Profile> profileMap(Long clubId) {
        Map<Long, ClubCustomerVO.Profile> out = new LinkedHashMap<>();
        for (ClubCustomerVO.Profile profile : clubCrmMapper.selectProfiles(clubId)) {
            out.put(profile.getMemberId(), profile);
        }
        return out;
    }

    public static List<String> parseTags(String tagsJson) {
        List<String> out = new ArrayList<>();
        if (tagsJson == null || tagsJson.trim().isEmpty()) return out;
        try {
            List<String> parsed = JSON.parseArray(tagsJson, String.class);
            if (parsed != null) {
                for (String tag : parsed) {
                    String name = tag == null ? "" : tag.trim();
                    if (!name.isEmpty()) out.add(name);
                }
            }
        } catch (Exception ignored) {
            // 存量脏数据不该让整页打不开;标签丢了是可见的降级,抛异常是不可见的黑屏。
        }
        return out;
    }

    private String participantDisplayName(String storedName, String nickname, Long memberId) {
        if (storedName != null && !storedName.trim().isEmpty()) {
            String masked = PiiCryptoService.maskName(piiCryptoService.decrypt(storedName));
            if (masked != null && !masked.trim().isEmpty()) return masked;
        }
        String fallback = nickname == null ? "" : nickname.trim();
        if (fallback.isEmpty() || "城瘾用户".equals(fallback)) {
            return memberId == null ? "参与人" : "成员#" + memberId;
        }
        return fallback;
    }

    private static String topicStatusCode(ClubCustomerVO.RawTopicRow raw) {
        if (Integer.valueOf(3).equals(raw.getRegistrationStatus())
                && raw.getPayableAmount() != null && raw.getPayableAmount().signum() == 0
                && raw.getPointUsed() != null && raw.getPointUsed().longValue() == 0L) return "CANCELLED";
        if (Integer.valueOf(4).equals(raw.getPaymentStatus())) return "REFUNDED";
        // ★2026-09-11:K3 稿画了四步履约轨迹,而第四步「已评价」此前在代码里根本不存在 ——
        //   statusCode 只产出四档、railStep 最大取 2,于是那一格永远灰着,是个装饰性的死节点。
        //   评价事实在 merchant_review(按 registration_id 关联),不是新造的状态位。
        //   顺序在核销之前判:评价必然发生在核销之后,反过来判就永远走不到。
        if (raw.getReviewTime() != null) return "REVIEWED";
        if (Integer.valueOf(1).equals(raw.getVerificationStatus())) return "VERIFIED";
        // 已接洽 = 已付款、未核销、场次已经开始(人到了现场但还没扫码);其余是待核销。
        Date sessionTime = raw.getSessionTime();
        if (sessionTime != null && sessionTime.before(new Date())) return "CONTACTED";
        return "PENDING";
    }

    private static String topicStatusText(String statusCode) {
        if ("CANCELLED".equals(statusCode)) return "已取消";
        if ("REFUNDED".equals(statusCode)) return "已退款";
        if ("REVIEWED".equals(statusCode)) return "已评价";
        if ("VERIFIED".equals(statusCode)) return "已核销";
        if ("CONTACTED".equals(statusCode)) return "已接洽";
        return "待核销";
    }

    private static String normalizedFilter(String filter) {
        String value = trimmed(filter);
        if ("repeat".equals(value) || "new".equals(value) || "remark".equals(value)) return value;
        return "all";
    }

    /** 已核销与已评价都算「核销过」。 */
    private static boolean isVerifiedOrLater(String statusCode) {
        return "VERIFIED".equals(statusCode) || "REVIEWED".equals(statusCode);
    }

    /** 选「已核销」时,已评价的单也要留在列表里 —— 它本来就核销过。 */
    private static boolean matchesTopicFilter(String wanted, String statusCode) {
        if ("VERIFIED".equals(wanted)) return isVerifiedOrLater(statusCode);
        return wanted.equals(statusCode);
    }

    private static String normalizedTopicFilter(String filter) {
        String value = trimmed(filter);
        if ("pending".equals(value)) return "PENDING";
        if ("contacted".equals(value)) return "CONTACTED";
        if ("verified".equals(value)) return "VERIFIED";
        return null;
    }

    private static String formatDay(Date date) {
        return date == null ? "" : new SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    private static int nullToZero(Integer value) { return value == null ? 0 : value; }

    private static String trimmed(String value) { return value == null ? "" : value.trim(); }
}

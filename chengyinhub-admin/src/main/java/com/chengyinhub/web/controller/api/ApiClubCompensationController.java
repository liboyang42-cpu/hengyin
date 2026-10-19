package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubQualityDecision;
import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 俱乐部侧：自报实际工时、提交质量证据。
 *
 * <p>只开这两个动作 —— 确认工时、批额外工时、定质量奖励都在管理端且各有专用权限。
 * 归属校验按「群主 或 role=1 管理员」判，与 ApiClubController 的内容治理口径一致；
 * 非归属人一律拒绝，绝不因为「传了 clubId」就认。
 */
@RestController
@RequestMapping("/api/club-compensation")
public class ApiClubCompensationController extends BaseController {

    private final IClubEditionCompensationService compensationService;
    private final IClubService clubService;
    private final IClubMemberService clubMemberService;

    public ApiClubCompensationController(IClubEditionCompensationService compensationService,
                                         IClubService clubService,
                                         IClubMemberService clubMemberService) {
        this.compensationService = compensationService;
        this.clubService = clubService;
        this.clubMemberService = clubMemberService;
    }

    /**
     * ★topicId 走请求体不走路径，与 app 层其余 {@code /api/**} 端点同款。
     *
     * <p>两个理由：①app 侧没有对象级 scope 这一层（那是管理端 canEditTopic 的事），
     * topicId 在路径上并不多带来一分权限意义，归属由 belongsToClub + service 侧
     * requireExecutingClub 两道把关；②小程序静态门禁 U1 只认<b>完整字面量</b>路由
     * （{@code ci/xcx-check.sh} → ui-validity-gates），路径参数会让前端只能拼串，
     * 于是「这个端点后端真的存在吗」这道闸对它永久失效。
     */
    /**
     * ★2026-08-25 已退役：俱乐部自报实际工时。
     *
     * <p>端点保留、立刻返回<b>可读</b>拒绝 —— 既不删也不让它抛 500：
     * 小程序新版本要等 ②微信上传之后才铺开，在那之前旧客户端仍然会打这个地址。
     * 让 service 抛异常它只会拿到 500 白屏；删掉则是 404，同样解释不了发生了什么。
     * 这里给一句人话，旧版本用户看到的是 toast 而不是坏掉的页面。
     *
     * <p>退役理由与 service 侧同源（见 {@code rejectRetiredHoursWrite}）：实际工时是自陈数字，
     * 平台在线下没有任何能证伪它的事实，而 EXTRA 单价 ¥40／人小时 —— 自报即计酬。
     */
    @PostMapping("/hours/report")
    public AjaxResult reportHours(@RequestBody Map<String, Object> body) {
        return error("工时自报已下线，本期结算不再需要填写工时");
    }

    /**
     * 本俱乐部能自报的期次列表（小程序选期控件的数据源）。
     *
     * <p>★为什么不能复用 {@code /api/club/topics}：那个端点的语义是「俱乐部办的经典定向团」，
     * 过滤条件是 {@code cms_topic.club_id = #{clubId}}。探店日 ③ 由<b>平台</b>建期，
     * {@code CmsTopicServiceImpl} 对 PLATFORM 主题把这一列写成 NULL，承接流程也从不回写它 ——
     * 执行俱乐部只存在于 coop_order 阵容与 {@code club_edition_compensation_terms.executing_club_id}。
     * 按那一列取候选，俱乐部一个探店日期次都选不出来 ⇒ {@code reportActualHoursCas} 无人调 ⇒
     * {@code confirm_status} 恒 UNREPORTED ⇒ FINAL 恒 PENDING ⇒ 四个 A 的产出在生产上恒 0 且零告警。
     *
     * <p>归属校验与另外两个动作<b>逐字相同</b>：先要登录态，再 {@code belongsToClub}
     * （群主 或 role=1 管理员）。请求体里的 clubId 只是「想看哪个俱乐部」，不自证身份；
     * service 侧再按 {@code executing_club_id} 逐行回读一遍（{@code EDITION_CLUB_SCOPE_LEAK}）。
     *
     * <p>空列表是合法结果，前端按空态展示，不要当成网络错误。
     */
    @PostMapping("/editions")
    public AjaxResult editions(@RequestBody Map<String, Object> body) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception notLoggedIn) {
            return error("请先登录");
        }
        Long clubId = longOf(body, "clubId");
        if (!belongsToClub(clubId, memberId)) return error("非本俱乐部负责人，无法查看期次");
        return success(compensationService.editionsForExecutingClub(clubId));
    }

    /**
     * X039:期次列表为空时的原因(只给计数,不出主题名/金额)。归属校验与 /editions 逐字相同。
     * awaitingSale &gt; 0 = 已承接、条款未开售;0 = 还没有承接本俱乐部执行的期次。
     */
    @PostMapping("/editions/empty-reason")
    public AjaxResult editionsEmptyReason(@RequestBody Map<String, Object> body) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception notLoggedIn) {
            return error("请先登录");
        }
        Long clubId = longOf(body, "clubId");
        if (!belongsToClub(clubId, memberId)) return error("非本俱乐部负责人，无法查看期次");
        int awaiting = compensationService.undertakenEditionsAwaitingSale(clubId);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("reason", awaiting > 0 ? "AWAITING_SALE" : "NOT_UNDERTAKEN");
        data.put("awaitingSale", awaiting);
        return success(data);
    }

    // 2026-09-10 用户裁决:探店日不再交质量证据,整条链路删除。
    // 原来这里是 /quality/evidence —— 俱乐部上传一个 SHA-256 摘要,再回读核对。
    // 平台在后台 business:clubquality:confirm 上照常人工判 Q,只是不再要这份附件。

    private boolean belongsToClub(Long clubId, Long memberId) {
        if (clubId == null || memberId == null) return false;
        Club club = clubService.selectClubById(clubId);
        if (club == null) return false;
        if (memberId.equals(club.getMemberId())) return true;
        ClubMember member = clubMemberService.selectByClubAndMember(clubId, memberId);
        return member != null && Integer.valueOf(1).equals(member.getRole());
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Map<String, Object> body, String key) {
        return body == null ? null : Convert.toLong(body.get(key));
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}

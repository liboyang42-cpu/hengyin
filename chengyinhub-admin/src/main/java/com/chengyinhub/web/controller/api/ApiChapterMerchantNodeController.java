package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.IPlayerWxacodeService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.NodeNpcService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 自由探索:承接商家在章节下的自有点位(2026-08-06 拍板 B 方案)。
 *
 * <p>商家提交自己的玩法点位 → 主题发布者审核 → 过审后才对玩家可见。
 * 玩家侧一行不用改:点位就是普通 {@code cms_topic_node},打卡/答题/发分全部复用。
 *
 * <p>可见性闸在 {@code CmsTopicNodeMapper} 的 selectList 里做单一收口 ——
 * 全仓十来处读节点,逐个加闸必漏。
 */
@RestController
@Tag(name = "章节商家点位")
@RequestMapping("/api/merchant/chapter-node")
public class ApiChapterMerchantNodeController extends BaseController {

    @Autowired
    private IChapterMerchantNodeService chapterMerchantNodeService;
    @Autowired
    private ICmsTopicNodeService nodeService;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private IQrcodeService qrcodeService;
    @Autowired
    private IPlayerWxacodeService playerWxacodeService;
    @Autowired
    private IMerchantAccessService merchantAccessService;
    @Autowired
    private IPlayCheckinCodeService playCheckinCodeService;
    @Autowired
    private GameSessionRuntimeMapper gameSessionRuntimeMapper;
    @Autowired
    private ICmsRegistrationMerchantService registrationMerchantService;
    @Autowired
    private NodeNpcService nodeNpcService;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;

    private Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    /** F-L5C-02:点位文案经主办方准入后直接给玩家看,驳回原因发给商家;写入前送检,审不了按 fail-close 拦截。 */
    private String textRisk(Long actorMemberId, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        return com.chengyinhub.business.service.IWxContentSecurityService.checkTextAsMember(wxContentSecurityService, umsMemberService,
                actorMemberId, text);
    }

    @Operation(summary = "商家提交/更新自己在章节下的点位(提交即回到待审)")
    @PostMapping("/submit")
    public AjaxResult submit(@RequestBody ChapterNodeRequest body) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (body == null) return error("参数有误");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);
            String risk = textRisk(memberId, com.chengyinhub.business.service.IWxContentSecurityService.joinText(
                    body.getName(), body.getDescription(), body.getAddress(), body.getBusinessTime()));
            if (risk != null) return error(risk);
            return success(chapterMerchantNodeService.submit(
                    access.getOwnerMemberId(), body.getChapterId(), body.toDraft()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "我的章节点位(含待审与驳回原因)")
    @PostMapping("/mine")
    public AjaxResult mine(Long topicId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.BASIC_READ);
            return success(chapterMerchantNodeService.listMine(access.getOwnerMemberId(), topicId));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "主办方:我主题下待审的商家点位")
    @PostMapping("/pending")
    public AjaxResult pending(Long topicId, String scope) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (topicId == null || topicId <= 0) return error("参数有误");
        Long ownerMemberId;
        try {
            ownerMemberId = resolveProjectOwner(memberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        // 谁发布谁审核。不校验归属的话,任何人都能翻别人主题的待审内容。
        if (topic == null || OwnershipUtils.notOwner(topic.getMemberId(), ownerMemberId)) {
            return error("只有主题发布者可以查看");
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        // 显式带 nodeAuditStatus ⇒ 走管理上下文,能看到未过审的(见 mapper 里那道可见性闸)
        probe.setNodeAuditStatus(com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        List<CmsTopicNode> list = nodeService.selectCmsTopicNodeList(probe);
        return success(list == null ? new ArrayList<CmsTopicNode>() : list);
    }

    /**
     * 批 5 · T2:商家取本店海报码(商家打印张贴,玩家扫)。
     *
     * <p>方向与核销动态码相反:那个是玩家掏出来给商家扫、靠 TTL 防转赠;
     * 这个长期有效,所以**不能**下发给玩家 —— 只有本店店主取得到(归属闸在 service)。
     *
     * <p>图必须是微信小程序码：scene = {@code checkin_code}，落地 {@code pages/index/index}。
     * 普通二维码扫了打不开城瘾。{@code code} 字段仍是 checkin_code 本身，给站内扫码打卡用。
     *
     * <p>⚠️ 两条已知限制,与漫游同源,别当强证据:① 码被拍照转发即可远程「进店」——
     * 真正的钱闸在后面,进店只写 {@code player_node_progress.status=0},券仍要商家扫玩家的动态码才发;
     * ② 没有单点作废 —— 要重印某一家的码,只能给该节点换 {@code checkin_code},
     * 而换码会让已印好的海报全部作废,所以不做成自助按钮。
     */
    @Operation(summary = "取本店到店打卡码(商家打印张贴,玩家扫)")
    @PostMapping("/poster-code")
    public AjaxResult posterCode(Long nodeId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        CmsTopicNode node;
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.BASIC_READ);
            try {
                node = chapterMerchantNodeService.posterCodeNode(access.getOwnerMemberId(), nodeId);
            } catch (IllegalStateException denied) {
                node = assignedStationPosterNode(access.getOwnerMemberId(), nodeId, denied);
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", node.getCheckinCode());
        try {
            data.put("qrcodeUrl", playerWxacodeService.getOrCreateScene(node.getCheckinCode()));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        data.put("nodeId", node.getId());
        data.put("nodeName", node.getName());
        data.put("topicId", node.getTopicId());
        data.put("chapterId", node.getChapterId());
        return success(data);
    }

    /**
     * 商家当场出示游戏打卡码（玩家扫一次即作废）。
     *
     * <p>与 {@link #posterCode} 的海报静态码相反：这个码有 TTL、Redis 消费一次，
     * 签的是 {@code VerifyDynCodeUtil} 动态串，不是 {@code checkin_code} hex。
     */
    @Operation(summary = "出示本站一次性打卡码(商家亮码,玩家扫)")
    @PostMapping("/live-checkin-code")
    public AjaxResult liveCheckinCode(Long nodeId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.VERIFY);
            IPlayCheckinCodeService.Issued issued = playCheckinCodeService.issue(
                    access.getOwnerMemberId(), nodeId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", issued.code);
            data.put("qrcodeUrl", issued.qrcodeUrl);
            data.put("nodeId", issued.nodeId);
            data.put("nodeName", issued.nodeName);
            data.put("ttlMs", issued.ttlMs);
            return success(data);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主办方:审核商家点位(准入,非背书)")
    @PostMapping("/audit")
    public AjaxResult audit(Long nodeId, Boolean approve, String reason, String scope) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        try {
            String risk = textRisk(memberId, reason);
            if (risk != null) return error(risk);
            return success(chapterMerchantNodeService.audit(
                    resolveProjectOwner(memberId, scope), nodeId, Boolean.TRUE.equals(approve), reason));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    // ===== 节点 NPC(CR-927,产品拍板 2026-09-15 #28)=====
    //
    // 数据落 npc_profile scope_type=4;审核口径与门店分身一致(文本同步送检 + 非预设头像异步送检,
    // 先发后审);编辑权与点位内容同一套闸:写入口(save/voice)走 requireOwnLiveNode(归属 + 承接生效 +
    // 主题未开始),读入口(detail/voice status)走 requireOwnNode(归属 + 承接生效,不判冻结)。

    @Operation(summary = "商家读取本节点 NPC(含停用,给编辑回填)")
    @PostMapping("/npc/detail")
    public AjaxResult npcDetail(@RequestBody NodeNpcRequest body) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        // 小程序一律 JSON 体发请求 —— 参数必须从 @RequestBody 绑,漏了它每个请求都会是「参数有误」。
        if (body == null || body.getNodeId() == null || body.getNodeId() <= 0) return error("参数有误");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);
            return success(nodeNpcService.detailForMerchant(access.getOwnerMemberId(), body.getNodeId()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "商家保存本节点 NPC(名称/头像/台词)")
    @PostMapping("/npc/save")
    public AjaxResult npcSave(@RequestBody NodeNpcRequest body) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (body == null || body.getNodeId() == null || body.getNodeId() <= 0) return error("参数有误");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);
            return success(nodeNpcService.save(access.getOwnerMemberId(), body.getNodeId(),
                    body.toDraft(), merchantOpenid(access.getOwnerMemberId())));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "节点 NPC 声音:发起复刻(可选)")
    @PostMapping("/npc/voice/enroll")
    public AjaxResult npcVoiceEnroll(@RequestBody NodeNpcRequest body) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (body == null || body.getNodeId() == null || body.getNodeId() <= 0) return error("参数有误");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);
            return success(nodeNpcService.voiceEnroll(memberId, access.getOwnerMemberId(),
                    body.getNodeId(), body.getVoiceSample(), body.getVoiceRequestId(), body.getVoicePreviousIntentId(),
                    Boolean.TRUE.equals(body.getVoiceDuplicateChargeConsent())));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "节点 NPC 声音:查状态")
    @PostMapping("/npc/voice/status")
    public AjaxResult npcVoiceStatus(@RequestBody NodeNpcRequest body) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (body == null || body.getNodeId() == null || body.getNodeId() <= 0) return error("参数有误");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);
            NpcProfile mine = nodeNpcService.voiceStatus(memberId, access.getOwnerMemberId(), body.getNodeId(), body.getVoiceRequestId());
            // 没配过 NPC 也回 0/空,前端按「未配置」渲染 —— 与门店分身的 status 端点同形状。
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("voiceStatus", mine == null || mine.getVoiceStatus() == null
                    ? NodeNpcService.VOICE_NONE : mine.getVoiceStatus());
            data.put("voiceSample", mine == null ? null : mine.getVoiceSample());
            data.put("voicePreviousIntentId", mine == null ? null : mine.getVoicePreviousIntentId());
            data.put("voiceRequestId", mine == null ? null : mine.getVoiceRequestId());
            data.put("voiceSelected", mine == null ? null : mine.getVoiceSelected());
            data.put("voiceHistory", nodeNpcService.voiceHistory(memberId, access.getOwnerMemberId(), body.getNodeId()));
            data.put("voiceIntentId", mine == null ? null : mine.getVoiceIntentId());
            data.put("voiceIntentState", mine == null ? null : mine.getVoiceIntentState());
            data.put("voiceReasonCode", mine == null ? null : mine.getVoiceReasonCode());
            return success(data);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "节点 NPC 声音:清除(重录前先清)")
    @PostMapping("/npc/voice/reset")
    public AjaxResult npcVoiceReset(@RequestBody NodeNpcRequest body) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (body == null || body.getNodeId() == null || body.getNodeId() <= 0) return error("参数有误");
        try {
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);
            nodeNpcService.voiceReset(access.getOwnerMemberId(), body.getNodeId());
            return AjaxResult.success("已清除");
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /** 内容安全 v2 需要发表者 openid 做风控;取不到时传 null,由服务降级 scene 检测。 */
    private String merchantOpenid(Long memberId) {
        if (umsMemberService == null || memberId == null) return null;
        UmsMember u = umsMemberService.selectUmsMemberById(memberId);
        return u == null ? null : u.getWxOpenId();
    }

    private Long resolveProjectOwner(Long principalMemberId, String scope) {
        if (!MerchantOperationScope.isMerchant(scope)) return principalMemberId;
        return merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                .getOwnerMemberId();
    }

    /**
     * 城市定向中标站往往是主办方节点, {@link IChapterMerchantNodeService#posterCodeNode}
     * 会因 merchant_member_id 对不上而拒。中标报名或可接待的 game_station_run
     * 仍必须给出同一份静态 {@code checkin_code}，不能改签发一次性串。
     */
    private CmsTopicNode assignedStationPosterNode(Long merchantMemberId, Long nodeId,
                                                   IllegalStateException denied) {
        if (!canTakeAssignedStationPoster(merchantMemberId, nodeId)) {
            throw denied;
        }
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getCheckinCode() == null || node.getCheckinCode().trim().isEmpty()) {
            throw denied;
        }
        return node;
    }

    private boolean canTakeAssignedStationPoster(Long merchantMemberId, Long nodeId) {
        if (merchantMemberId == null || nodeId == null || nodeId <= 0) return false;
        if (gameSessionRuntimeMapper != null
                && gameSessionRuntimeMapper.countMerchantOperableStation(merchantMemberId, nodeId) > 0) {
            return true;
        }
        if (registrationMerchantService == null) return false;
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setMemberId(merchantMemberId);
        probe.setNodeId(nodeId);
        probe.setStatus(1L);
        probe.setAuditStatus(1L);
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> rows =
                registrationMerchantService.selectCmsRegistrationMerchantList(probe);
        return rows != null && !rows.isEmpty();
    }

    /**
     * 提交体。**刻意不直接收 CmsTopicNode** —— 那样客户端能连 merchantMemberId、
     * nodeAuditStatus、topicId 一起传进来,而这三样正是权限本身。
     */
    public static class ChapterNodeRequest {
        private Long chapterId;
        private Long templateId;
        private String name;
        private String description;
        private String address;
        private String longitude;
        private String latitude;
        private String imgUrl;
        private String businessTime;
        private Integer xpValue;

        CmsTopicNode toDraft() {
            CmsTopicNode n = new CmsTopicNode();
            n.setTemplateId(templateId);
            n.setName(name);
            n.setDescription(description);
            n.setAddress(address);
            n.setLongitude(longitude);
            n.setLatitude(latitude);
            n.setImgUrl(imgUrl);
            n.setBusinessTime(businessTime);
            n.setXpValue(xpValue);
            return n;
        }

        public Long getChapterId() { return chapterId; }
        public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getLongitude() { return longitude; }
        public void setLongitude(String longitude) { this.longitude = longitude; }
        public String getLatitude() { return latitude; }
        public void setLatitude(String latitude) { this.latitude = latitude; }
        public String getImgUrl() { return imgUrl; }
        public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
        public String getBusinessTime() { return businessTime; }
        public void setBusinessTime(String businessTime) { this.businessTime = businessTime; }
        public Integer getXpValue() { return xpValue; }
        public void setXpValue(Integer xpValue) { this.xpValue = xpValue; }
    }

    /**
     * 节点 NPC 提交体。**刻意不收 scope/审核态** —— 归属、scope_type、audit_status 全由服务端定,
     * 客户端传了也不算(与 {@link ChapterNodeRequest} 同一条规矩)。
     */
    public static class NodeNpcRequest {
        private Long nodeId;
        private String name;
        private String avatar;
        private String greeting;
        private String voiceSample;
        private String voiceRequestId;
        private Long voicePreviousIntentId;
        private Boolean voiceDuplicateChargeConsent;

        NpcProfile toDraft() {
            NpcProfile p = new NpcProfile();
            p.setName(name);
            p.setAvatar(avatar);
            p.setGreeting(greeting);
            return p;
        }

        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getGreeting() { return greeting; }
        public void setGreeting(String greeting) { this.greeting = greeting; }
        public String getVoiceRequestId() { return voiceRequestId; }
        public void setVoiceRequestId(String value) { voiceRequestId = value; }
        public Long getVoicePreviousIntentId() { return voicePreviousIntentId; }
        public void setVoicePreviousIntentId(Long value) { voicePreviousIntentId = value; }
        public Boolean getVoiceDuplicateChargeConsent() { return voiceDuplicateChargeConsent; }
        public void setVoiceDuplicateChargeConsent(Boolean value) { voiceDuplicateChargeConsent = value; }
        public String getVoiceSample() { return voiceSample; }
        public void setVoiceSample(String voiceSample) { this.voiceSample = voiceSample; }
    }
}

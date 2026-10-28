package com.chengyinhub.web.service.play;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.TopicRouteModePolicy;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Completion and registration facts shared by read projections and completion commands. */
@Service
public class PlayCompletionFacts {
    private static final Logger logger = LoggerFactory.getLogger(PlayCompletionFacts.class);

    @Autowired(required = false)
    private com.chengyinhub.business.mapper.WxContentCallbackInboxMapper wxContentCallbackInboxMapper;
    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private ICmsRegistrationService cmsRegistrationService;
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;
    @Autowired
    private PlayNodeContext playNodeContext;

    private final TopicCompletionRulePolicy topicCompletionRulePolicy = new TopicCompletionRulePolicy();

    /**
     * 规则16 历史履约内容:已完成节点不在在线列表(普通下架/停用 status=0)时,按 id 复用已存节点内容。
     *
     * <p>边界(与删除/违规处置分开):
     * <ul>
     *   <li>物理删除(行取不到)或跨主题节点 → null,不猜内容;</li>
     *   <li>商家节点沿用列表可见性闸同判据(merchant_member_id is null or node_audit_status=1):
     *       待审 0 / 驳回 2 不向历史读取公开,防止草稿与违规内容借历史复活;</li>
     *   <li>只应由「本人 status==1 完成行」驱动调用,未参与玩家不触发此读取。</li>
     * </ul>
     * 内容真源是节点行自身(fragmentText/story 等),不读模板、不重算奖励、不改任何进度。
     */
    public CmsTopicNode historyReadableNode(Long nodeId, com.chengyinhub.business.domain.CmsTopic topic)
    {
        if (topic == null || topicHistoryBlocked(topic)) {
            return null;
        }
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTopicId() == null || !node.getTopicId().equals(topic.getId())) {
            return null;
        }
        if (node.getMerchantMemberId() != null) {
            return Integer.valueOf(1).equals(node.getNodeAuditStatus()) ? node : null;
        }
        return node;
    }

    /**
     * 主题是否处于「审核驳回/治理处置/内容安全违规」侧——历史读取必须与之分开:
     * 普通下架(下架但无处置事实)按规则16对历史参与者可读;处置/违规不得借历史读取复活。
     *
     * <p>可靠既有事实:①审核台处置写 status=2 + check_status=2(+reason,见 AuditTaskServiceImpl);
     * ②内容安全回调命中违规自动下架会留 wx_content_callback_inbox(verdict=1)持久记录
     * (WxContentSecurityServiceImpl 仅改 user_status,故必须查该处置事实,不能只看 status)。
     * 处置事实读不到时失败关闭,不把可能违规的内容重新开放。
     */
    public boolean topicHistoryBlocked(com.chengyinhub.business.domain.CmsTopic topic)
    {
        if (topic == null) return true;
        if (Integer.valueOf(2).equals(topic.getStatus())
                || Integer.valueOf(2).equals(topic.getCheckStatus())) {
            return true;
        }
        // 内容安全自动下架是「可逆,仅改状态」:只在当前确处于下架态(user_status=0)时查违规事实;
        // 运营/申诉把 user_status 恢复为 1 后,历史读取随之恢复,不把已撤销的处置当成永久封禁。
        if (!Integer.valueOf(0).equals(topic.getUserStatus())) {
            return false;
        }
        // 下架态必须读处置事实:读取件缺失(mapper 未注入/装配失败)或主题主键缺失
        // 都属于「读不到处置事实」⇒ 失败关闭,不把可能违规的内容借历史读取重新开放(§7-3)。
        if (wxContentCallbackInboxMapper == null || topic.getId() == null) {
            logger.error("内容安全处置事实读取件不可用 topic={},历史读取按失败关闭", topic.getId());
            return true;
        }
        try {
            return wxContentCallbackInboxMapper.countRiskyCallback(
                    IWxContentSecurityService.BIZ_TYPE_TOPIC, topic.getId()) > 0;
        } catch (Exception e) {
            logger.error("内容安全处置事实读取失败 topic={},历史读取按失败关闭", topic.getId(), e);
            return true;
        }
    }

    /**
     * 自玩历史履约事实:持有过该主题通行证(owner_type=1 + 报名成功)即算参与者,
     * 不判 expires_at —— 过期仍是历史履约,不能挡住已有完成者读自己的碎片。
     */
    public boolean hasSelfPlayRegistration(Long memberId, Long topicId)
    {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(probe);
        return regs != null && !regs.isEmpty();
    }

    public boolean isExploreChapterRedeemed(Long memberId, Long activityId, Long chapterId) {
        if (activityId == null || activityId <= 0 || chapterId == null) return false;
        return isExploreChapterRedeemed(exploreRegistration(memberId, activityId), chapterId);
    }

    public boolean isExploreChapterRedeemed(CmsRegistration registration, Long chapterId) {
        if (registration == null || chapterId == null
                || !Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) return false;
        for (RegistrationEntitlement entitlement : perkLedgerService.listEntitlements(registration.getId())) {
            if (chapterId.equals(entitlement.getChapterId()) && Integer.valueOf(1).equals(entitlement.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * [P0-17] ★ 通关口径的**单一真源**。五个判定点全部经它,别在任何一处重写 `done >= total`。
     *
     * <p><b>③ 认核销不认打卡</b>:一张 ③ 通票的「通关」= 全部必选章都在商家那儿核销过,
     * 与他打了多少卡无关。①(经典定向)与 ②(自玩)按主题 complete_rule_json 判定，空规则仍是全节点。
     *
     * <p>分流判据用 {@code resolveMode}(主题级)而不是 purchase_kind:advanceProgress 手上只有
     * memberId/node/actId,拿 purchase_kind 要多查一次 registration;而 ProductType 的值域
     * 刻意与 PlayMode 同值(1/2),派生是恒等映射 ⇒ resolveMode==FREE_COLLECT 就等价于「主题是 ③」。
     * fallback 兜底方向对本处是安全的:product_type 为 NULL 时兜底返 1(线性)= 判不清就走
     * 主题规则为空时回落全节点老口径，不会误把存量主题切到 isExploreCompleted 而永不通关。
     * ⚠️ 已知残留风险(生产实测只涉及 3 个主题):product_type 为 NULL 的**混票**主题里,
     * firstTicketMode 可能返 2 ⇒ ① 单被判成 ③ ⇒ 若 redemption 链没通就永不通关。
     *
     * <p><b>拿不到 ③ 的报名单时回落老口径</b>,不是判 false —— 判 false 会让「主题是 ③ 但这人
     * 走的是别的路子进来玩」的场景永久卡死;回落老口径至少行为与今天一致。
     */
    public boolean isTopicCompleted(Long memberId, Long actId, Long topicId, int total, int done)
    {
        if (playNodeContext.resolveMode(actId, topicId) != PlayMode.FREE_COLLECT) {
            com.chengyinhub.business.domain.CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            return topicCompletionRulePolicy.isCompleted(
                    topic == null ? null : topic.getCompleteRuleJson(), total, done);
        }
        return isTopicCompletedFor(exploreRegistration(memberId, actId), topicId, total, done);
    }

    /**
     * [P0-17] 同一口径的**列表版**:③ 的报名单由调用方批量捞好后传进来。
     *
     * <p>为什么列表场景不走 {@link #isTopicCompleted}:那个版本内部会调 resolveMode(每个不同主题
     * 一次 selectCmsTopicById)+ exploreRegistration(每个活动一次查询)= 妥妥的 N+1。
     * 而列表侧**为了拿 registrationId 本来就要读 registration** ⇒ purchase_kind 随行免费
     * ⇒ 「explore != null」本身就等价于「purchase_kind == EXPLORE_PASS」,不必再查主题。
     * (这与 advanceProgress 那处的结论相反,因为成本方向反了 —— 那处手上有 topicId 没 registration。)
     *
     * <p>⚠️ 已知回落:purchase_kind 为 NULL 的单在这里会被当 ①(explore 取不到)⇒ 走主题节点规则。
     * 可接受且方向正确 —— NULL 的三个来源(存量未回填 / 后台手工建单 / 独立活动无 topicId)
     * 都不会产生「带 entitlement 的 ③ 活动单」:后台手工建单走 insertCmsRegistration,
     * 压根不经 reserveEntitlements ⇒ 它没有 entitlement ⇒ 就算判成 ③ 也恒不通关,回落老口径反而更对。
     */
    public boolean isTopicCompletedFor(CmsRegistration explore, Long topicId, int total, int done)
    {
        if (explore == null) {
            com.chengyinhub.business.domain.CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            return topicCompletionRulePolicy.isCompleted(
                    topic == null ? null : topic.getCompleteRuleJson(), total, done);
        }
        return perkLedgerService.isExploreCompleted(explore.getId(), topicId);
    }

    /**
     * /my-completed 读侧完成事实的单一入口(独立审查 S1):活动与自玩两个循环共用分流,
     * 分支图/会话在线一律看 TopicRouteSession.COMPLETED,线性回落 {@link #isTopicCompletedFor}
     * (③ 看核销,其余看主题 complete_rule_json)。别再各写一份分流,加口径时只改这一处。
     */
    public boolean isCompletedCompletionFact(com.chengyinhub.business.domain.CmsTopic topic,
            Integer routeStatus, List<CmsRegistration> explores, Long topicId, int total, int done)
    {
        if (topic == null || topicHistoryBlocked(topic)) return false;
        boolean hasExplorePass = explores != null && !explores.isEmpty();
        if (Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)
                .equals(topic.getProductType()) && !hasExplorePass) return false;
        if (Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)
                .equals(topic.getProductType())
                && !TopicRouteModePolicy.isEffectiveBranchGraph(topic, null)) {
            for (CmsRegistration explore : explores) {
                if (isTopicCompletedFor(explore, topicId, total, done)) return true;
            }
            return false;
        }
        if (TopicRouteModePolicy.isEffectiveBranchGraph(topic, null) || routeStatus != null) {
            return Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(routeStatus);
        }
        if (hasExplorePass) {
            for (CmsRegistration explore : explores) {
                if (isTopicCompletedFor(explore, topicId, total, done)) return true;
            }
            return false;
        }
        return isTopicCompletedFor(null, topicId, total, done);
    }

    /** 完成列表只使用本人原场次的已付探索票；不以 product_type 反推或补造票。 */
    public boolean completedExplorePassOwnedBy(Long memberId, Long activityId, CmsRegistration registration)
    {
        return registration != null && registration.getId() != null && registration.getId() > 0
                && memberId.equals(registration.getMemberId())
                && Long.valueOf(2L).equals(registration.getOwnerType())
                && activityId.equals(registration.getOwnerId())
                && Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())
                && Integer.valueOf(2).equals(registration.getRegistrationStatus())
                && Integer.valueOf(2).equals(registration.getPaymentStatus());
    }

    /**
     * [P0-17] 该玩家在该活动下的 ③ 报名单;无则 null。
     *
     * <p>③ 定死是 {@code owner_type=2 / owner_id=activityId} 的活动单 —— createRegistration
     * 全程不 setTopicId(全类只有 createSelfPlayPass 设它),且 ApiRegistrationController 把
     * ownerType==1 无条件 early-return 进 createSelfPlayPass ⇒ 「owner_type=1 + ticketId」
     * 那条路从 API 根本不可达。所以探针固定 ownerType=2 就够。
     *
     * <p>⚠️ 同一 (member, activity) 可能有**多行** registration(跨订单重复购买;决策 A 的整单闸
     * 只压住「同一单多张」)⇒ 口径定死:**任一 ③ 单通关即算通关**(与 validSelfPlayPass
     * 「遍历取第一个有效」同风格)。这里返回第一张 ③ 单。
     */
    public CmsRegistration exploreRegistration(Long memberId, Long actId)
    {
        if (memberId == null || actId == null || actId == 0L) {
            return null;   // 自玩会话 actId 恒 0,不走 ③
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);
        probe.setOwnerId(actId);
        probe.setRegistrationStatus(2); // 2=报名成功(已支付)
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (regs == null) {
            return null;
        }
        for (CmsRegistration r : regs) {
            if (r.getPurchaseKind() != null && r.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
                return r;
            }
        }
        return null;
    }

    /** 玩家是否已报名成功该活动 */
    public boolean isRegistered(Long memberId, Long actId)
    {
        CmsRegistration regProbe = new CmsRegistration();
        regProbe.setMemberId(memberId);
        regProbe.setOwnerType(2L); // 2=活动
        regProbe.setOwnerId(actId);
        regProbe.setRegistrationStatus(2); // 2=报名成功
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(regProbe);
        return regs != null && !regs.isEmpty();
    }

    /** 旧入口无指定票时沿用报名查询的 id desc 顺序；有指定票则仅用本次票夹中的本人订单。 */
    public CmsRegistration activityRegistration(Long memberId, Long actId, Long registrationId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);
        probe.setOwnerId(actId);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> registrations = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (registrations != null) {
            for (CmsRegistration registration : registrations) {
                if (registration != null && (registrationId == null || registrationId.equals(registration.getId()))) {
                    return registration;
                }
            }
        }
        return null;
    }

    /** M2 自玩:返回该主题的有效通行证(owner_type=1 主题通行证 + 报名成功 + 未过期);无则 null */
    public CmsRegistration validSelfPlayPass(Long memberId, Long topicId)
    {
        return validSelfPlayPass(memberId, topicId, null);
    }

    public CmsRegistration validSelfPlayPass(Long memberId, Long topicId, Long registrationId)
    {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L); // 1=主题(自玩通行证)
        probe.setOwnerId(topicId);
        probe.setRegistrationStatus(2); // 报名成功=已支付
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (regs == null) return null;
        Date now = new Date();
        for (CmsRegistration r : regs) {
            // expires_at 空=不过期(兼容旧数据);非空须晚于现在
            if ((registrationId == null || registrationId.equals(r.getId()))
                    && (r.getExpiresAt() == null || r.getExpiresAt().after(now))) return r;
        }
        return null;
    }
}

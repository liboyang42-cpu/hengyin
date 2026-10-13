package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.support.OmsTicketWriteValidator;
import com.chengyinhub.business.support.SoldTicketEditGuard;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;

/**
 * 主题/活动票务Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-02
 */
@Service
public class OmsTicketServiceImpl implements IOmsTicketService 
{
    private static final Logger log = LoggerFactory.getLogger(OmsTicketServiceImpl.class);
    private static final Long OWNER_TYPE_TOPIC = 1L;
    private static final Long OWNER_TYPE_ACTIVITY = 2L;

    @Autowired
    private OmsTicketMapper omsTicketMapper;

    @Autowired
    private MemberIdentityService memberIdentityService;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired(required = false)
    private ITopicSaleCanonicalMutationService canonicalMutationService;

    /** 9-15 拍板:已发布免费主题不能转商业。后台票务入口此前只挡 SELLING 冻结,是漏口。 */
    @Autowired
    private CommercialTopicPolicy commercialTopicPolicy;

    /** 平台自营判据(与 D21 计酬闸同口径;无内容槽 fail-closed 当第三方)。 */
    @Autowired
    private IProductRoleService productRoleService;

    /**
     * 查询主题/活动票务
     * 
     * @param id 主题/活动票务主键
     * @return 主题/活动票务
     */
    @Override
    public OmsTicket selectOmsTicketById(Long id)
    {
        return omsTicketMapper.selectOmsTicketById(id);
    }

    @Override
    public BigDecimal selectMinTicketPrice(OmsTicket omsTicket)
    {
        return omsTicketMapper.selectMinTicketPrice(omsTicket);
    }

    @Override
    public List<Map<String, Object>> selectMinTicketPrices(Long ownerType, List<Long> ownerIds) {
        return omsTicketMapper.selectMinTicketPrices(ownerType, ownerIds);
    }

    @Override
    public void applyActivityShellRemaining(Long topicId, List<PublicTicketVO> tickets)
    {
        if (topicId == null || tickets == null || tickets.isEmpty()) return;
        // 主题票库存不是成交库存；投影不完整时保留 unknown，并给运营留下可检索告警。
        for (PublicTicketVO ticket : tickets) {
            if (ticket != null) ticket.setRemainingInventory(null);
        }
        List<ActivityShellTicketInventoryVO> rows =
                omsTicketMapper.selectActivityShellTicketRemainingByTopic(topicId);
        if (rows == null || rows.isEmpty()) {
            log.warn("活动壳余席投影无稳定配对事实 topicId={} publicTicketCount={}", topicId, tickets.size());
            return;
        }

        // 每个 active 活动壳都在 rows 里至少占一行；topicTicketId 为空 = 该壳一张配对票都没有。
        java.util.Set<Long> activeActivityIds = new java.util.TreeSet<>();
        java.util.Set<Long> unpairedShellIds = new java.util.TreeSet<>();
        boolean missingActivityId = false;
        for (ActivityShellTicketInventoryVO row : rows) {
            if (row == null || row.getActivityId() == null) {
                missingActivityId = true;
                continue;
            }
            activeActivityIds.add(row.getActivityId());
            if (row.getTopicTicketId() == null) {
                unpairedShellIds.add(row.getActivityId());
            }
        }
        if (missingActivityId || activeActivityIds.size() != 1 || !unpairedShellIds.isEmpty()) {
            log.warn("活动壳余席投影不可判 topicId={} activeActivityIds={} unpairedShellIds={}"
                            + " missingActivityId={} rowCount={}",
                    topicId, activeActivityIds, unpairedShellIds, missingActivityId, rows.size());
            return;
        }

        Map<Long, Long> remainingByTicket = new java.util.HashMap<>();
        Map<Long, Integer> occurrences = new java.util.HashMap<>();
        for (ActivityShellTicketInventoryVO row : rows) {
            if (row == null || row.getTopicTicketId() == null) continue;
            Long sourceTicketId = row.getTopicTicketId();
            occurrences.put(sourceTicketId, occurrences.getOrDefault(sourceTicketId, 0) + 1);
            if (row.getRemainingInventory() != null) {
                remainingByTicket.put(sourceTicketId, row.getRemainingInventory());
            }
        }

        java.util.Set<Long> unresolved = new java.util.TreeSet<>();
        for (PublicTicketVO ticket : tickets) {
            if (ticket == null || ticket.getId() == null) continue;
            Long ticketId = ticket.getId();
            Long remaining = remainingByTicket.get(ticketId);
            if (remaining != null && Integer.valueOf(1).equals(occurrences.get(ticketId))) {
                ticket.setRemainingInventory(Math.max(0L, remaining));
            } else {
                unresolved.add(ticketId);
            }
        }
        if (!unresolved.isEmpty()) {
            log.warn("活动壳余席投影不完整 topicId={} unresolvedSourceTicketIds={}", topicId, unresolved);
        }
    }

    /**
     * 查询主题/活动票务列表
     * 
     * @param omsTicket 主题/活动票务
     * @return 主题/活动票务
     */
    @Override
    public List<OmsTicket> selectOmsTicketList(OmsTicket omsTicket)
    {
        return omsTicketMapper.selectOmsTicketList(omsTicket);
    }

    /**
     * 新增主题/活动票务
     * 
     * @param omsTicket 主题/活动票务
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertOmsTicket(OmsTicket omsTicket)
    {
        Long parentMemberId = resolveWritableParentMemberId(omsTicket.getOwnerType(), omsTicket.getOwnerId());
        assertActivityShellTicketIsTopicDerived(omsTicket.getOwnerType(), omsTicket.getOwnerId());
        OmsTicketWriteValidator.validateNewTicket(omsTicket);
        // CU-C-48:后台票务写口是第二条能直接写 oms_ticket 的路,越界校验必须与 App 发布同口径。
        assertTicketWindowWithinActivity(omsTicket.getOwnerType(), omsTicket.getOwnerId(),
                omsTicket.getStartTime(), omsTicket.getEndTime());
        Long topicId = resolveTopicId(omsTicket.getOwnerType(), omsTicket.getOwnerId());
        boolean conversionProtected = conversionProtectedFor(topicId);
        // 票务归属只认父主题/活动真源，不能信任 POST 体里的 memberId / remainingInventory。
        omsTicket.setMemberId(parentMemberId);
        omsTicket.setSourceTicketId(null);
        omsTicket.setRemainingInventory(omsTicket.getTotalInventory());
        // 新建的票不该生而带删除标记。与其余五条写入路径(saveTickets / newActivityTicket /
        // publishActivity / ApiActivityController / PricingServiceImpl)对齐显式写 0 ——
        // 在此之前只有这一条路径省略该列(后台三个前端调用方都是白名单 payload，不传 delFlag，
        // mapper 的 <if test="delFlag != null"> 于是跳过它)，落库值只能来自库侧默认;
        // 而 oms_ticket 的建表语句不在本仓、也不在全部 git 历史里，那个默认值本地不可验证。
        omsTicket.setDelFlag(0);
        omsTicket.setCreateTime(DateUtils.getNowDate());
        int rows = omsTicketMapper.insertOmsTicket(omsTicket);
        if (rows > 0) {
            invalidateApprovedCanonical(topicId, "ticket-insert");
        }
        // 写后复核:同一事务内看见收费票即抛 ⇒ 整笔回滚,收费票不会落库。
        if (topicId != null) {
            commercialTopicPolicy.assertNotConvertedToCommercial(topicId, conversionProtected);
        }
        return rows;
    }

    /**
     * 修改主题/活动票务
     *
     * <p>[B4/§5-1] 归属绑定:<b>ownerType/ownerId 是必填声明</b>,必须与票的真实父对象逐项一致 ——
     * 否则「活动 A 的页面」能拿活动 B 的票 id 改到 B 头上。声明缺省或只传一半一律 400:
     * 老口径「不传就按票真实父对象查权限」在类型级权限下挡不住跨归属 id,已废弃;
     * 所有后台/小程序调用方都必须带上声明。无论声明与否,归属字段都在写库前剥离。
     *
     * @param omsTicket 主题/活动票务
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateOmsTicket(OmsTicket omsTicket)
    {
        // 时间可部分更新，必须锁住同一张票后再拼有效窗口，避免两个请求分别改开始/结束时间时绕过校验。
        OmsTicket existing = selectExistingTicketForUpdate(omsTicket.getId());
        // 归属绑定必须发生在 strip 之前:剥离会清掉声明字段,之后就再也证明不了票属于哪张活动。
        assertTicketBelongsToClaimedParent(omsTicket, existing);
        resolveWritableParentMemberId(existing.getOwnerType(), existing.getOwnerId());
        Long topicId = resolveTopicId(existing.getOwnerType(), existing.getOwnerId());
        boolean conversionProtected = conversionProtectedFor(topicId);
        // 即使绕过 Controller 直调 Service，也不能改票务归属或订单流维护的库存计数器。
        stripImmutableTicketFields(omsTicket);
        // 已售锁(裁决 15):App 票务同步之外的第二条写口,不挡就仍能改已售票的时间/集合点/价格/
        // 退款规则,或把已售票软删(del_flag=2)。
        assertSoldActivityTicketUntouched(existing, omsTicket);
        OmsTicketWriteValidator.validateTicketTimeWindow(
                omsTicket.getStartTime() == null ? existing.getStartTime() : omsTicket.getStartTime(),
                omsTicket.getEndTime() == null ? existing.getEndTime() : omsTicket.getEndTime());
        OmsTicketWriteValidator.validatePrice(omsTicket.getPrice());
        if (omsTicket.getSaleStartTime() != null || omsTicket.getSaleEndTime() != null)
        {
            OmsTicketWriteValidator.validateSaleTimeWindow(
                    omsTicket.getSaleStartTime() == null ? existing.getSaleStartTime() : omsTicket.getSaleStartTime(),
                    omsTicket.getSaleEndTime() == null ? existing.getSaleEndTime() : omsTicket.getSaleEndTime());
        }
        // CU-C-48:部分更新时拼的都是有效窗口,越界判定也按有效窗口来。
        assertTicketWindowWithinActivity(existing.getOwnerType(), existing.getOwnerId(),
                omsTicket.getStartTime() == null ? existing.getStartTime() : omsTicket.getStartTime(),
                omsTicket.getEndTime() == null ? existing.getEndTime() : omsTicket.getEndTime());
        if (omsTicket.getTotalInventory() != null)
        {
            OmsTicketWriteValidator.validateTotalInventory(omsTicket.getTotalInventory());
            // totalInventory 是总容量，不是“把 remaining 直接压到这个数”。
            // 改总容量时保持已占用量不变，同步折算可售余量；否则总量降到当前余量后，
            // 任一正常取消都会因回补越界而永远卡成 409。
            omsTicket.setRemainingInventory(
                    OmsTicketWriteValidator.remainingKeepingOccupiedSeats(existing, omsTicket.getTotalInventory()));
        }
        omsTicket.setUpdateTime(DateUtils.getNowDate());
        int rows = omsTicketMapper.updateOmsTicket(omsTicket);
        if (omsTicket.getTotalInventory() != null && rows == 0)
        {
            throw new ServiceException("票务库存已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (rows > 0) {
            invalidateApprovedCanonical(topicId, "ticket-update");
        }
        // 写后复核:0 元改收费票与新增收费票是同一道口子,复核放在同一处。
        if (topicId != null) {
            commercialTopicPolicy.assertNotConvertedToCommercial(topicId, conversionProtected);
        }
        return rows;
    }

    private OmsTicket selectExistingTicketForUpdate(Long ticketId)
    {
        if (ticketId == null)
        {
            throw new ServiceException("票务ID不能为空", HttpStatus.BAD_REQUEST);
        }
        OmsTicket existing = omsTicketMapper.selectOmsTicketByIdForUpdate(ticketId);
        if (existing == null)
        {
            throw new ServiceException("票务不存在或已删除", HttpStatus.NOT_FOUND);
        }
        return existing;
    }

    /**
     * 已售锁(2026-09-15 裁决 15 的总控判定):活动票一旦有用户下单(待支付 0/支付中 1/已支付 2),
     * 后台通用 PUT 也不许改它的时间、集合点、价格、退款规则,更不许软删(del_flag=2) ——
     * 玩家按当时的票面成交,追溯改写等于把已成交订单的履约与退款口径一起挪走。票名等文字仍可改。
     *
     * <p>主题票不在此列:主题侧有 lifecycle 冻结与主题编辑重建两道各自的口径。
     */
    private void assertSoldActivityTicketUntouched(OmsTicket existing, OmsTicket patch)
    {
        if (!OWNER_TYPE_ACTIVITY.equals(existing.getOwnerType()))
        {
            return;
        }
        boolean deleting = Integer.valueOf(2).equals(patch.getDelFlag());
        String lockedChange = deleting ? "删除" : SoldTicketEditGuard.lockedChange(existing, patch);
        if (lockedChange == null)
        {
            return;
        }
        if (!hasOpenSaleRegistration(existing.getOwnerId(), existing.getId()))
        {
            return;
        }
        if (deleting)
        {
            throw new ServiceException("该票已有用户下单，已售出的票不能删除；票名等文字信息仍可改",
                    HttpStatus.CONFLICT);
        }
        throw new ServiceException("该票已有用户下单，已售出的票不能修改" + lockedChange + "；票名等文字信息仍可改",
                HttpStatus.CONFLICT);
    }

    /** 该活动票上是否还有活着的报名(0 待支付/1 支付中/2 已支付)。 */
    private boolean hasOpenSaleRegistration(Long activityId, Long ticketId)
    {
        if (activityId == null || ticketId == null)
        {
            return false;
        }
        List<Long> soldTicketIds = cmsRegistrationMapper.selectOpenSaleTicketIdsByActivity(activityId);
        return soldTicketIds != null && soldTicketIds.contains(ticketId);
    }

    /**
     * 票务写入必须先落回真实父对象：主题票需要主题编辑权，活动票需要活动编辑权。
     * memberId 从父对象派生，避免客户端把票款归属伪造成其他用户。
     */
    private Long resolveWritableParentMemberId(Long ownerType, Long ownerId)
    {
        if (ownerType == null || ownerId == null)
        {
            throw new ServiceException("票务必须归属到主题或活动", HttpStatus.BAD_REQUEST);
        }
        if (OWNER_TYPE_TOPIC.equals(ownerType))
        {
            requireParentEditPermission("business:topic:edit", "主题");
            CmsTopic topic = cmsTopicMapper.selectCmsTopicById(ownerId);
            if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()))
            {
                throw new ServiceException("主题不存在或已删除", HttpStatus.NOT_FOUND);
            }
            if (TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle()))
            {
                throw new ServiceException("主题已进入 SELLING，售卖目录（票种/价格/库存/履约窗）已冻结",
                        HttpStatus.CONFLICT);
            }
            return requireParentMemberId(topic.getMemberId(), "主题");
        }
        if (OWNER_TYPE_ACTIVITY.equals(ownerType))
        {
            requireParentEditPermission("business:activity:edit", "活动");
            CmsActivity activity = cmsActivityMapper.selectCmsActivityById(ownerId);
            if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag()))
            {
                throw new ServiceException("活动不存在或已删除", HttpStatus.NOT_FOUND);
            }
            if (activity.getTopicId() != null)
            {
                CmsTopic sourceTopic = cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
                if (sourceTopic == null || Integer.valueOf(2).equals(sourceTopic.getDelFlag()))
                {
                    throw new ServiceException("活动关联主题不存在或已删除", HttpStatus.NOT_FOUND);
                }
                if (TopicLifecycleState.commercialTermsFrozen(sourceTopic.getLifecycle()))
                {
                    throw new ServiceException("关联主题已进入 SELLING，活动壳售卖目录已冻结",
                            HttpStatus.CONFLICT);
                }
            }
            return requireParentMemberId(activity.getMemberId(), "活动");
        }
        throw new ServiceException("票务归属类型仅支持主题或活动", HttpStatus.BAD_REQUEST);
    }

    private void requireParentEditPermission(String permission, String parentName)
    {
        if (!SecurityUtils.hasPermi(permission))
        {
            throw new ServiceException("无权维护该" + parentName + "的票务", HttpStatus.FORBIDDEN);
        }
    }

    private Long requireParentMemberId(Long memberId, String parentName)
    {
        if (memberId == null)
        {
            throw new ServiceException(parentName + "缺少票务归属主体", HttpStatus.BAD_REQUEST);
        }
        return memberId;
    }

    /**
     * 会被主题同步的活动壳上，票是主题票的服务端投影，不是可以就地新建的独立商品。
     *
     * <p><b>「会被同步」有一条可证明的判据</b>，不需要猜：{@code CmsTopicServiceImpl.refreshSelfHostActivity}
     * 用 {@code (topicId, memberId=主题发布者)} 做探针找活动，找到的才会进 {@code syncTopicTicketsToActivity}。
     * 所以 {@code activity.memberId == topic.memberId}（自办壳）等价于「这个壳会被同步」。探店日(FREE_EXPLORE)
     * 另外还有读侧代价（见下），所以无论自办与否一律拦。<b>商家/俱乐部承接的 ①② 壳</b>
     * （{@code activity.memberId != topic.memberId}）探针永远找不到、永远不会被同步，在上面加独立票合法，
     * 必须放行。
     *
     * <p>后台「活动详情 → 添加票务」建出来的票必然无键（{@code source_ticket_id} 只能由服务端在
     * 复制/同步时写，客户端不许自填 —— 那条安全事实保留不动）。而一张无键壳票的代价是三重的：
     * 读侧 {@link #applyActivityShellRemaining} 判「该壳存在不可归属库存」⇒ 整期余席退回 unknown；
     * 写侧下一次主题编辑在 {@code syncTopicTicketsToActivity} 抛 {@code ACTIVITY_TICKET_UNKEYED}；
     * 而 {@code deleteUnsoldActivityOrphanTicket} 的 where 要求 {@code source_ticket_id} 相等，
     * 所以同步连清都清不掉它，运营只能回后台手工删。
     *
     * <p>「唯一匹配后绑到某张主题票」不是解 —— 每张主题票在壳里已经有一张对应票了，再绑一张只会造出
     * 重复配对（读侧 {@code occurrences != 1} ⇒ 仍是 unknown，写侧 {@code ACTIVITY_SOURCE_DUPLICATED}）。
     * 域里能证明的只有一条：合法壳票只能由主题票同步产生。所以这里 fail-closed 并给出可执行的指路。
     */
    private void assertActivityShellTicketIsTopicDerived(Long ownerType, Long ownerId)
    {
        if (!OWNER_TYPE_ACTIVITY.equals(ownerType) || ownerId == null)
        {
            return;
        }
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(ownerId);
        Long topicId = activity == null ? null : activity.getTopicId();
        if (topicId == null)
        {
            return;
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null)
        {
            return;
        }
        boolean exploreDay = Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType());
        // 自办壳 = 承接方就是主题发布者 = refreshSelfHostActivity 的探针会命中的那一类。
        // 两边都非空才算相等:任一为空时探针也匹配不上(mapper 的 memberId 条件是 <if 非空>)，属可放行。
        boolean selfHostShell = topic.getMemberId() != null
                && topic.getMemberId().equals(activity.getMemberId());
        if (!exploreDay && !selfHostShell)
        {
            return;
        }
        throw new ServiceException("活动壳票源冲突[TOPIC_SHELL_TICKET_NOT_DIRECTLY_CREATABLE]"
                + " activityId=" + ownerId + " topicId=" + topicId
                + " exploreDay=" + exploreDay + " selfHost=" + selfHostShell
                + "：该活动的场次票由主题票同步生成，请回到主题编辑里新增场次，保存后系统会同步到本活动",
                HttpStatus.CONFLICT);
    }

    /**
     * [B4/§5-1] 票 update 不能只信客户端传来的 ticket id:调用方声明的活动/主题必须就是票的真实归属。
     *
     * <p>声明 ownerType/ownerId <b>必填</b>:缺省或只传一半一律 400 —— 这是 fail-closed 的闸,
     * 不再保留「不声明就按票真实父对象查权限」的缺省口径(类型级权限下它挡不住跨归属 id)。
     * 声明齐全但与票的真实归属对不上 —— 比如活动 702 的票被拿到活动 703 的页面提交 —— 直接 403,
     * 而不是把票静默改到别的活动头上。
     */
    private void assertTicketBelongsToClaimedParent(OmsTicket requested, OmsTicket existing)
    {
        if (requested.getOwnerType() == null || requested.getOwnerId() == null)
        {
            throw new ServiceException("票务修改必须声明归属(ownerType/ownerId)", HttpStatus.BAD_REQUEST);
        }
        if (!requested.getOwnerType().equals(existing.getOwnerType())
                || !requested.getOwnerId().equals(existing.getOwnerId()))
        {
            String parentName = OWNER_TYPE_ACTIVITY.equals(existing.getOwnerType()) ? "活动" : "主题";
            throw new ServiceException("票务不属于当前" + parentName + "，拒绝跨归属修改", HttpStatus.FORBIDDEN);
        }
    }

    private void stripImmutableTicketFields(OmsTicket omsTicket)
    {
        omsTicket.setOwnerId(null);
        omsTicket.setOwnerType(null);
        omsTicket.setMemberId(null);
        omsTicket.setSourceTicketId(null);
        omsTicket.setRemainingInventory(null);
    }

    /**
     * 批量删除主题/活动票务
     * 
     * @param ids 需要删除的主题/活动票务主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteOmsTicketByIds(Long[] ids)
    {
        java.util.Set<Long> topicIds = new java.util.TreeSet<>();
        if (ids != null) {
            for (Long id : ids) {
                OmsTicket existing = id == null ? null : omsTicketMapper.selectOmsTicketById(id);
                Long topicId = existing == null ? null : resolveTopicId(existing.getOwnerType(), existing.getOwnerId());
                if (topicId != null) {
                    topicIds.add(topicId);
                }
            }
        }
        int rows = omsTicketMapper.deleteOmsTicketByIds(ids);
        if (rows > 0) {
            for (Long topicId : topicIds) {
                invalidateApprovedCanonical(topicId, "ticket-delete-batch");
            }
        }
        return rows;
    }

    /**
     * 删除主题/活动票务信息
     *
     * @param id 主题/活动票务主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteOmsTicketById(Long id)
    {
        OmsTicket existing = id == null ? null : omsTicketMapper.selectOmsTicketById(id);
        int rows = omsTicketMapper.deleteOmsTicketById(id);
        if (rows > 0 && existing != null) {
            invalidateApprovedCanonical(resolveTopicId(existing.getOwnerType(), existing.getOwnerId()), "ticket-delete");
        }
        return rows;
    }

    /**
     * 把主题票(ownerType=1)复制为某活动的活动票(ownerType=2)，并记录本次复制的来源票键。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int copyTopicTicketsToActivity(Long topicId, Long activityId, Long memberId)
    {
        CmsActivity activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(activityId);
        OmsTicket existQuery = new OmsTicket();
        existQuery.setOwnerType(OWNER_TYPE_ACTIVITY);
        existQuery.setOwnerId(activityId);
        existQuery.setDelFlag(0);
        List<OmsTicket> already = omsTicketMapper.selectOmsTicketList(existQuery);
        if (already != null && !already.isEmpty()) {
            return 0;
        }

        OmsTicket query = new OmsTicket();
        query.setOwnerType(OWNER_TYPE_TOPIC);
        query.setOwnerId(topicId);
        query.setDelFlag(0);
        List<OmsTicket> topicTickets = omsTicketMapper.selectOmsTicketList(query);
        if (topicTickets != null && topicTickets.stream().anyMatch(ticket ->
                ticket != null && ticket.getPrice() != null && ticket.getPrice().signum() > 0)) {
            String error = memberIdentityService.checkRegistered(memberId);
            if (error != null) throw new com.chengyinhub.common.exception.ServiceException(error);
        }
        int count = 0;
        if (topicTickets != null) {
            for (OmsTicket source : topicTickets) {
                OmsTicket target = newActivityTicket(source, activityId, memberId, activity);
                omsTicketMapper.insertOmsTicket(target);
                count++;
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncTopicTicketsToActivity(Long topicId, Long activityId, Long memberId)
    {
        CmsActivity activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(activityId);

        OmsTicket activityQuery = new OmsTicket();
        activityQuery.setOwnerType(OWNER_TYPE_ACTIVITY);
        activityQuery.setOwnerId(activityId);
        activityQuery.setDelFlag(0);
        List<OmsTicket> activityTickets = omsTicketMapper.selectOmsTicketList(activityQuery);

        OmsTicket topicQuery = new OmsTicket();
        topicQuery.setOwnerType(OWNER_TYPE_TOPIC);
        topicQuery.setOwnerId(topicId);
        topicQuery.setDelFlag(0);
        List<OmsTicket> topicTickets = omsTicketMapper.selectOmsTicketList(topicQuery);

        List<OmsTicket> currentTopicTickets = topicTickets == null
                ? java.util.Collections.<OmsTicket>emptyList() : topicTickets;
        java.util.Set<Long> currentSourceIds = new java.util.HashSet<>();
        for (OmsTicket source : currentTopicTickets) {
            if (source == null || source.getId() == null) {
                throw activityShellSyncConflict("TOPIC_TICKET_UNKEYED", topicId, activityId, source);
            }
            if (!currentSourceIds.add(source.getId())) {
                throw activityShellSyncConflict("TOPIC_SOURCE_DUPLICATED", topicId, activityId, source);
            }
        }

        List<OmsTicket> currentActivityTickets = activityTickets == null
                ? java.util.Collections.<OmsTicket>emptyList() : activityTickets;
        Map<Long, OmsTicket> bySource = new java.util.HashMap<>();
        java.util.Set<Long> duplicatedSources = new java.util.HashSet<>();
        for (OmsTicket ticket : currentActivityTickets) {
            if (ticket == null || ticket.getId() == null || ticket.getSourceTicketId() == null) {
                throw activityShellSyncConflict("ACTIVITY_TICKET_UNKEYED", topicId, activityId, ticket);
            }
            if (bySource.put(ticket.getSourceTicketId(), ticket) != null) {
                duplicatedSources.add(ticket.getSourceTicketId());
            }
        }
        for (Long duplicatedSource : duplicatedSources) {
            if (currentSourceIds.contains(duplicatedSource)) {
                throw activityShellSyncConflict("ACTIVITY_SOURCE_DUPLICATED", topicId, activityId,
                        bySource.get(duplicatedSource));
            }
        }

        List<OmsTicket> orphanTickets = new java.util.ArrayList<>();
        for (OmsTicket ticket : currentActivityTickets) {
            if (!currentSourceIds.contains(ticket.getSourceTicketId())) {
                Long total = ticket.getTotalInventory();
                Long remaining = ticket.getRemainingInventory();
                if (total == null || remaining == null || total < 0L || remaining < 0L || remaining > total) {
                    throw activityShellSyncConflict("ORPHAN_INVENTORY_INVALID", topicId, activityId, ticket);
                }
                if (!remaining.equals(total)) {
                    throw activityShellSyncConflict("ORPHAN_ALREADY_SOLD", topicId, activityId, ticket);
                }
                orphanTickets.add(ticket);
            }
        }

        int changed = 0;
        for (OmsTicket orphan : orphanTickets) {
            int rows = omsTicketMapper.deleteUnsoldActivityOrphanTicket(
                    orphan.getId(), activityId, orphan.getSourceTicketId());
            if (rows != 1) {
                throw activityShellSyncConflict("ORPHAN_DELETE_RACE", topicId, activityId, orphan);
            }
            changed += rows;
        }

        for (OmsTicket source : currentTopicTickets) {
            Long sourceId = source.getId();
            OmsTicket existing = bySource.get(sourceId);
            if (existing == null) {
                omsTicketMapper.insertOmsTicket(newActivityTicket(source, activityId, memberId, activity));
                changed++;
                continue;
            }

            OmsTicket patch = newActivityTicket(source, activityId, memberId, activity);
            patch.setId(existing.getId());
            patch.setCreateBy(null);
            patch.setCreateTime(null);
            patch.setTotalInventory(null);
            patch.setRemainingInventory(null);
            if (!java.util.Objects.equals(source.getTotalInventory(), existing.getTotalInventory())) {
                if (source.getTotalInventory() != null && existing.getTotalInventory() != null
                        && existing.getRemainingInventory() != null) {
                    patch.setTotalInventory(source.getTotalInventory());
                    patch.setRemainingInventory(OmsTicketWriteValidator
                            .remainingKeepingOccupiedSeats(existing, source.getTotalInventory()));
                } else {
                    log.warn("活动壳库存口径无法安全切换 topicId={} activityId={} sourceTicketId={}",
                            topicId, activityId, sourceId);
                }
            }
            patch.setUpdateBy("topic_sync");
            patch.setUpdateTime(DateUtils.getNowDate());
            int rows = omsTicketMapper.updateOmsTicket(patch);
            if (rows != 1) {
                throw new ServiceException("活动壳票同步冲突，请刷新后重试", HttpStatus.CONFLICT);
            }
            changed += rows;
        }
        return changed;
    }

    private ServiceException activityShellSyncConflict(String reason, Long topicId, Long activityId,
                                                       OmsTicket ticket)
    {
        // 机器码放最前面保持可检索;已售那条再补一句运营能照做的话,别只丢一串内部 reason code。
        String advice = "ORPHAN_ALREADY_SOLD".equals(reason)
                ? "：该场次已售出席位，不能在本次编辑中被删除或改名/改价/改时间。请保留原场次，"
                        + "或先为已售订单完成退款后再调整。"
                : "";
        return new ServiceException("活动壳票同步冲突[" + reason + "] topicId=" + topicId
                + " activityId=" + activityId
                + " ticketId=" + (ticket == null ? null : ticket.getId())
                + " sourceTicketId=" + (ticket == null ? null : ticket.getSourceTicketId()) + advice,
                HttpStatus.CONFLICT);
    }

    private OmsTicket newActivityTicket(OmsTicket source, Long activityId, Long memberId, CmsActivity activity)
    {
        OmsTicket target = new OmsTicket();
        target.setMemberId(memberId);
        target.setOwnerType(OWNER_TYPE_ACTIVITY);
        target.setOwnerId(activityId);
        target.setSourceTicketId(source.getId());
        target.setName(source.getName());
        target.setPrice(source.getPrice());
        target.setTotalInventory(source.getTotalInventory());
        target.setRemainingInventory(source.getTotalInventory());
        // 俱乐部开团的场次时间独立于主题原票；复制、同步都不能把旧日期带回新场次。
        boolean clubSession = activity != null && activity.getClubId() != null;
        target.setStartTime(clubSession ? activity.getStartDate() : source.getStartTime());
        target.setEndTime(clubSession ? activity.getEndDate() : source.getEndTime());
        target.setMode(source.getMode());
        target.setSubType(source.getSubType());
        target.setMeetingPoint(source.getMeetingPoint());
        target.setTeamSize(source.getTeamSize());
        target.setSignupDeadline(source.getSignupDeadline());
        target.setGatherLng(source.getGatherLng());
        target.setGatherLat(source.getGatherLat());
        target.setRefundRule(source.getRefundRule());
        target.setNeedAudit(source.getNeedAudit());
        target.setDelFlag(0);
        target.setCreateBy("topic_copy");
        target.setCreateTime(DateUtils.getNowDate());
        return target;
    }

    private Long resolveTopicId(Long ownerType, Long ownerId)
    {
        if (OWNER_TYPE_TOPIC.equals(ownerType)) {
            return ownerId;
        }
        if (OWNER_TYPE_ACTIVITY.equals(ownerType) && ownerId != null) {
            CmsActivity activity = cmsActivityMapper.selectCmsActivityById(ownerId);
            return activity == null ? null : activity.getTopicId();
        }
        return null;
    }

    /**
     * CU-C-48:活动票的场次窗口必须落在该活动 start_date–end_date 内。
     *
     * <p>只守活动票;主题票的售卖窗口另有 SaleAvailabilityPolicy。活动查不到时不在这里报错
     * ——归属校验已经先拦过一轮,这里不重复报同一件事。
     */
    private void assertTicketWindowWithinActivity(Long ownerType, Long ownerId,
                                                  Date ticketStartTime, Date ticketEndTime)
    {
        if (!OWNER_TYPE_ACTIVITY.equals(ownerType) || ownerId == null) {
            return;
        }
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(ownerId);
        if (activity == null) {
            return;
        }
        OmsTicketWriteValidator.validateTicketWindowWithinActivity(ticketStartTime, ticketEndTime,
                activity.getStartDate(), activity.getEndDate());
    }

    /**
     * 写前快照:该主题是否属于「已发布免费主题」(9-15 拍板禁转商业)。平台自营主题豁免 ——
     * 平台自己的主题由平台决定定价,与 D21 计酬闸同口径;无内容槽时 isPlatformOwned
     * fail-closed 当第三方,不能借「查不到槽位」逃逸保护。
     */
    private boolean conversionProtectedFor(Long topicId)
    {
        if (topicId == null || productRoleService.isPlatformOwned(topicId)) {
            return false;
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        return topic != null && commercialTopicPolicy.isConversionProtected(topic);
    }

    private void invalidateApprovedCanonical(Long topicId, String mutation)
    {
        if (canonicalMutationService != null && topicId != null) {
            canonicalMutationService.invalidateApprovedCanonical(topicId, mutation);
        }
    }
}

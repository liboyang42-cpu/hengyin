package com.chengyinhub.web.service.registration;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.ExploreTicketUsePolicy;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/** Owned registration/detail/trend projections. Existing readers only: no transaction,
 * initialization, redemption, refund execution, QR generation or external call.
 * The list result remains raw here; HTTP TableDataInfo adaptation stays in the controller. */
@Service
public class RegistrationQueryApplication {
    private static final Logger logger = LoggerFactory.getLogger(RegistrationQueryApplication.class);

    @Autowired
    private ICmsRegistrationService registrationService;
    @Autowired
    private IMerchantAccessService merchantAccessService;
    @Autowired
    private com.chengyinhub.business.mapper.CoopOrderMapper coopOrderMapper;
    @Autowired
    private IClubMemberService clubMemberService;
    @Autowired
    private IRefundService refundService;
    @Autowired
    private com.chengyinhub.business.service.support.RegistrationCancellationReadService cancellationReadService;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private ICmsActivityService activityService;
    @Autowired
    private com.chengyinhub.business.service.IOmsTicketService omsTicketService;
    @Autowired
    private ICmsRegistrationScanService registrationScanService;
    @Autowired
    private ICmsMemberTemplateService memberTemplateService;
    @Autowired
    private ICmsRegistrationMerchantService registrationMerchantService;
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;
    @Autowired
    private com.chengyinhub.business.service.IExploreCompletionReadService exploreCompletionReadService;
    @Autowired
    private ICmsTopicChapterService topicChapterService;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private IMmsMerchantService mmsMerchantService;
    @Autowired
    private ISysCategoryService sysCategoryService;

    /** 「我参与中」(myJoined)列表:活动/主题结束满 N 天后从列表隐藏该单的窗口(天)。仅影响该列表展示,不删除数据。 */
    public static final int HIDE_AFTER_END_DAYS = 7;

    public AjaxResult getRegistrationDetail(Long memberId, String id) {
        try {
            if (memberId <= 0) {
                return AjaxResult.error("请先登录");
            }

            Long registrationId = Convert.toLong(id, 0L);

            CmsRegistration registration = registrationService.selectCmsRegistrationById(registrationId);
            if (registration == null) {
                return AjaxResult.error("报名记录不存在");
            }

            // 验证是否是本人或管理员!SecurityUtils.isAdmin(userId)
            if (OwnershipUtils.notOwner(registration.getMemberId(), memberId)) {
                return AjaxResult.error("无权查看该报名信息");
            }
            if (registration.getOwnerType() == 1) {
                CmsTopic topic = topicService.selectCmsTopicById(registration.getOwnerId());
                if (topic != null) {
                    //查询主题的分类信息
                    String categoryIds = topic.getCategoryIds();
                    if (!StringUtils.isEmpty(categoryIds)) {
                        String[] ids = categoryIds.split(",");
                        List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                        if (listCategory.size() > 0) {
                            topic.setSysCategoryList(listCategory);
                        }
                    }
                    registration.setCmsTopic(topic);
                }

                // 合同二：票种履约窗与无票自玩主题履约窗统一从 FulfillmentWindowResolver 取。
                if (topic != null) {
                    com.chengyinhub.business.domain.OmsTicket ticket = registration.getTicketId() == null
                            ? null : omsTicketService.selectOmsTicketById(registration.getTicketId());
                    attachRefundVerdict(registration,
                            com.chengyinhub.business.util.RefundPolicy.evaluateTopicFulfillmentWindow(
                                    registration.getPaymentStatus(), registration.getVerificationStatus(),
                                    topic, ticket, new java.util.Date()));
                }

                CmsRegistrationScan cmsRegistrationScan = new CmsRegistrationScan();
                cmsRegistrationScan.setRegistrationId(Long.valueOf(id));
                List<CmsRegistrationScan> registrationScanList = registrationScanService.selectCmsRegistrationScanList(cmsRegistrationScan);
                for (int i = 0; i < registrationScanList.size(); i++) {
                    if (registrationScanList.get(i).getRegistrationType()==1){
                        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
                        cmsRegistrationMerchant.setTopicId(topic.getId());
                        cmsRegistrationMerchant.setMemberId(registrationScanList.get(i).getMemberId());
                        List<CmsRegistrationMerchant> registrationMerchantList = registrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
                        if (registrationMerchantList.size() > 0) {
                            registrationScanList.get(i).setRegistrationMerchant(registrationMerchantList.get(0));

                            CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(registrationMerchantList.get(0).getTemplateId());
                            registrationScanList.get(i).setMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate)); // [防作弊] 玩家侧不带答案/提示

                            MmsMerchant mmsMerchant = new MmsMerchant();
                            mmsMerchant = mmsMerchantService.selectMmsMerchantByMemberId(registrationScanList.get(i).getMemberId());
                            registrationScanList.get(i).setMmsMerchant(mmsMerchant);
                        }
                    }else{
                        CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(Long.valueOf(registrationScanList.get(i).getTemplateId()));
                        registrationScanList.get(i).setMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate)); // [防作弊] 玩家侧不带答案/提示
                    }
                }
                registration.setRegistrationScanList(registrationScanList);
            }


            if (registration.getOwnerType() == 2) {
                CmsActivity activity = activityService.selectCmsActivityById(registration.getOwnerId());
                if (activity != null) {
                    String categoryIds = activity.getCategoryIds();
                    if (!StringUtils.isEmpty(categoryIds)) {
                        String[] ids = categoryIds.split(",");
                        List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                        if (listCategory.size() > 0) {
                            activity.setSysCategoryList(listCategory);
                        }
                    }
                    registration.setCmsActivity(activity);
                    // 退款可退判定(活动:活动开始 startDate 前可退),供退款详情页展示,与 cancel 路径同口径
                    attachRefundVerdict(registration,
                            com.chengyinhub.business.util.RefundPolicy.evaluate(
                                    2L, registration.getPaymentStatus(), registration.getVerificationStatus(),
                                    null, null, null, activity.getStartDate(),
                                    registration.getPurchaseKind(), registration.getRefundDeadline(),
                                    registration.getPayableAmount(), registration.getPointUsed(), new java.util.Date()));
                }

                // ③ 探索票:带上权益清单(哪几章待核销/已核销/已失效)。
                // 玩家侧唯一能看到"我这张票还剩什么"的地方 —— verification_status 对 ③ 只表示
                // "被核销过至少一次",看不出还欠几章。归属校验在 :540 已做过,这里不重复。
                //
                // ⚠️ 先判 purchase_kind 再查库:① 的活动票也走 ownerType==2 这条分支,
                //    且生产 12 笔付费单全在这里 —— 无条件查 = 每次订单详情白查一次。
                if (registration.getPurchaseKind() != null
                        && registration.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
                    registration.setEntitlements(perkLedgerService.listEntitlements(registrationId));
                    // 探店日票面要能回答「几点、在哪集合」(总纲 §1.1/§1.2):带上票种快照。
                    if (registration.getTicketId() != null) {
                        registration.setOmsTicket(omsTicketService.selectOmsTicketById(registration.getTicketId()));
                    }
                }
            }



            if (Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) {
                CmsActivity currentActivity = registration.getCmsActivity();
                if (!Integer.valueOf(2).equals(registration.getPaymentStatus())
                        || !Integer.valueOf(2).equals(registration.getRegistrationStatus())
                        || currentActivity == null || currentActivity.getCancelTime() != null
                        || ExploreTicketUsePolicy.rejection(registration.getPurchaseKind(),
                            currentActivity.getEndDate(), new Date()) != null) {
                    registration.setQrcodeUrl(null);
                    registration.setVerificationCode(null);
                }
            }
            attachOrganizer(registration);
            attachManualRefundInfo(registration);
            registration.setRefundApplication(latestRegistrationRefund(registration.getId(), memberId));
            // 订单详情展示 enrich:脱敏手机号 / 支付方式文案 / 退款截止文案 / 权益章节名
            com.chengyinhub.business.util.RegistrationDetailDisplay.enrich(registration, topicChapterService);
            registration.setServerNow(System.currentTimeMillis());
            return AjaxResult.success(registration);
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    public AjaxResult exploreCompletion(Long memberId, String id) {
        if (memberId == null || memberId <= 0) {
            return AjaxResult.error("请先登录");
        }
        Long registrationId = Convert.toLong(id, 0L);
        if (registrationId <= 0) {
            return AjaxResult.error("参数不合规");
        }
        CmsRegistration registration = registrationService.selectCmsRegistrationById(registrationId);
        if (registration == null) {
            return AjaxResult.error("报名记录不存在");
        }
        if (!memberId.equals(registration.getMemberId())) {
            return AjaxResult.error("无权查看该报名信息");
        }
        // ③ 探索票之外的单没有完局面。这里 fail-closed 返错而不是返空壳:
        // 空壳会让 ①② 的订单详情也渲染出一块「图鉴 0/0」的假读面。
        if (registration.getPurchaseKind() == null
                || registration.getPurchaseKind() != PurchaseKind.EXPLORE_PASS) {
            return AjaxResult.error("该订单没有探店日完局面");
        }
        CmsActivity activity = registration.getOwnerId() == null ? null
                : activityService.selectCmsActivityById(registration.getOwnerId());
        Long topicId = activity == null ? null : activity.getTopicId();
        return AjaxResult.success(exploreCompletionReadService.read(
                registrationId, memberId, topicId, registration.getOwnerId()));
    }

    public AjaxResult getJoinRegistrationDetail(Long memberId, String member_id) {
        try {
            if (memberId <= 0) {
                return AjaxResult.error("请先登录");
            }

            // [P0-越权] 一律以 token 身份为准,忽略前端传入的 member_id
            // (原实现 `if(!member_id.equals("")) memberId = Long.valueOf(member_id)` 可传他人 id 越权读其报名/PII,
            //  且 member_id 为 null 时 .equals 还会 NPE)

            CmsRegistration cmsRegistration = new CmsRegistration();
            cmsRegistration.setMemberId(memberId);
            cmsRegistration.setRegistrationStatus(2);
            List<CmsRegistration> cmsRegistrations = registrationService.selectCmsRegistrationList(cmsRegistration);
            if (cmsRegistrations.size() > 0) {

                Long registrationId = Convert.toLong(cmsRegistrations.get(0).getId(), 0L);

                CmsRegistration registration = cmsRegistrations.get(0);
                if (registration == null) {
                    return AjaxResult.error("报名记录不存在");
                }

                // 验证是否是本人或管理员!SecurityUtils.isAdmin(userId)
                if (OwnershipUtils.notOwner(registration.getMemberId(), memberId)) {
                    return AjaxResult.error("无权查看该报名信息");
                }
                if (registration.getOwnerType() == 1) {
                    CmsTopic topic = topicService.selectCmsTopicById(registration.getOwnerId());
                    if (topic != null) {
                        registration.setCmsTopic(topic);
                    }

                    CmsRegistrationScan cmsRegistrationScan = new CmsRegistrationScan();
                    cmsRegistrationScan.setRegistrationId(Long.valueOf(registrationId));
                    List<CmsRegistrationScan> registrationScanList = registrationScanService.selectCmsRegistrationScanList(cmsRegistrationScan);
                    for (int i = 0; i < registrationScanList.size(); i++) {
                        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
                        cmsRegistrationMerchant.setTopicId(topic.getId());
                        cmsRegistrationMerchant.setMemberId(registrationScanList.get(i).getMemberId());
                        List<CmsRegistrationMerchant> registrationMerchantList = registrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
                        if (registrationMerchantList.size() > 0) {
                            registrationScanList.get(i).setRegistrationMerchant(registrationMerchantList.get(0));

                            CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(registrationMerchantList.get(0).getTemplateId());
                            registrationScanList.get(i).setMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate)); // [防作弊] 玩家侧不带答案/提示
                        }
                    }
                    registration.setRegistrationScanList(registrationScanList);
                }
                if (registration.getOwnerType() == 2) {
                    CmsActivity activity = activityService.selectCmsActivityById(registration.getOwnerId());
                    if (activity != null) {
                        registration.setCmsActivity(activity);
                    }
                }
                return AjaxResult.success(registration);
            } else {
                return AjaxResult.success();
            }
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    public AjaxResult countByDate(Long principalMemberId, String owner_type, String owner_id) {
        try {
            if (principalMemberId == null || principalMemberId <= 0) return AjaxResult.error("请先登录");
            Long ownerType = Convert.toLong(owner_type, 0L);
            Long ownerId = Convert.toLong(owner_id, 0L);
            if ((ownerType != 1L && ownerType != 2L) || ownerId <= 0) {
                return AjaxResult.error("参数不合规");
            }
            if (ownerType == 1L) {
                CmsTopic topic = topicService.selectCmsTopicById(ownerId);
                if (topic == null) return AjaxResult.error("主题不存在");
                boolean directOwner = principalMemberId.equals(topic.getMemberId());
                boolean clubOwner = clubMemberService.isTopicOwner(ownerId, principalMemberId);
                if (!directOwner && !clubOwner) {
                    MerchantAccessContext access = merchantAccessService.require(
                            principalMemberId, MerchantPermission.VERIFY_RECORD_READ);
                    CmsRegistrationMerchant awarded = new CmsRegistrationMerchant();
                    awarded.setMemberId(access.getOwnerMemberId());
                    awarded.setTopicId(ownerId);
                    awarded.setAuditStatus(1L);
                    awarded.setDelFlag(0);
                    if (registrationMerchantService.selectCmsRegistrationMerchantList(awarded).isEmpty()
                            && !hasEffectiveMerchantCoopOrder(coopOrderMapper, ownerId, access.getOwnerMemberId())) {
                        return new AjaxResult(HttpStatus.FORBIDDEN, "无权查看该项目趋势");
                    }
                }
            } else {
                CmsActivity activity = activityService.selectCmsActivityById(ownerId);
                if (activity == null) return AjaxResult.error("活动不存在");
                boolean directOwner = principalMemberId.equals(activity.getMemberId());
                boolean clubOwner = activity.getTopicId() != null
                        && clubMemberService.isTopicOwner(activity.getTopicId(), principalMemberId);
                if (!directOwner && !clubOwner) {
                    MerchantAccessContext access = merchantAccessService.require(
                            principalMemberId, MerchantPermission.VERIFY_RECORD_READ);
                    if (!access.getOwnerMemberId().equals(activity.getMemberId())) {
                        return new AjaxResult(HttpStatus.FORBIDDEN, "无权查看该项目趋势");
                    }
                }
            }
            List<Map<String, Object>> data = registrationService.countByParticipateDate(ownerType, ownerId);
            return AjaxResult.success(data);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    public AjaxResult myRegistrationList(Long member_id, String owner_type, String status, Integer is_online) {


        if (member_id == null || member_id <= 0) {
            return AjaxResult.error("请先登录");
        }

        Long ownerType = Convert.toLong(owner_type, 0L);
        if (ownerType < 1) {
            return AjaxResult.error("请选择正确的类型");
        }

        CmsRegistration registration = new CmsRegistration();
        if(ownerType<3){
            registration.setRegistrationStatus(2);
            registration.setOwnerType(ownerType);
        }else{
            // status 可空:投诉页等只传 owner_type=3 不传 status,等同文档「0全部」;
            // 常量在前,避免 status=null 时 String.equals NPE。
            if ("1".equals(status)){
                registration.setRegistrationStatus(1);
            }else  if ("2".equals(status)){
                registration.setRegistrationStatus(2);
            }else  if ("3".equals(status)){
                registration.setRegistrationStatus(2);
            }else  if ("4".equals(status)){
                registration.setRegistrationStatus(3);
            }
        }
        if (is_online!=null && is_online.intValue()==1){
            registration.setIsOnline(is_online.intValue());
        }
        registration.setMemberId(member_id);

        List<CmsRegistration> list;
        //startPage();
        list = registrationService.selectCmsRegistrationList(registration);
        if (ownerType >= 3 && ("2".equals(status) || "3".equals(status))) {
            List<CmsRegistration> filtered = new ArrayList<>();
            for (CmsRegistration row : list) {
                boolean completed = (Integer.valueOf(2).equals(row.getPaymentStatus())
                        && row.getFirstSuccessfulScanAt() != null)
                        || Integer.valueOf(1).equals(row.getVerificationStatus());
                if ("3".equals(status) == completed) filtered.add(row);
            }
            list = filtered;
        }
        Map<Long, RefundApplication> latestRefunds = latestRegistrationRefunds(member_id);
        for (CmsRegistration reg : list) {
            if (reg.getOwnerType() == 1) {
                CmsTopic topic = topicService.selectCmsTopicById(reg.getOwnerId());
                if (topic != null) {
                    // 2026-09-18 UI-20 返修:存量主题 cms_topic.product_type 仍是 NULL 时,订单卡按它出
                    // 「城市定向/自由探索」会整块标签消失;而游玩页 resolveMode 对同一主题已按票 mode 回落
                    // 出「城市定向」,两边自相矛盾。这里复用同一条读侧回落规则,只改响应不下库。
                    if (topic.getProductType() == null) {
                        topic.setProductType(readTopicProductType(topic.getId()));
                    }
                    reg.setCmsTopic(topic);
                }
            }
            if (reg.getOwnerType() == 2) {
                CmsActivity activity = activityService.selectCmsActivityById(reg.getOwnerId());
                if (activity != null) {
                    reg.setCmsActivity(activity);
                }
            }
            attachRefundInfoForList(reg);
            attachManualRefundInfo(reg);
            reg.setRefundApplication(latestRefunds.get(reg.getId()));
        }

        // ⚠️ 这里**不再**套 filterEndedOverWeek(2026-09-16 截图冒烟实证):
        // 本接口就是「我的订单」账本,客户端七态 Tab 里的「已完成 / 已退款 / 不可退款」天生指结束过的单;
        // 按 owner.end_date 隐藏结束超 7 天的单,会让这些 Tab 恒空 —— 真实商家号(有已支付订单)打开看到整屏空列表。
        // 7 天视觉收纳只为「我参与中」(myJoined,进行中 Tab)保留,那条链路独立调用该 helper。
        stampServerNow(list);
        return AjaxResult.success(list);
    }

    public AjaxResult myJoined(Long memberId) {
        if (memberId == null || memberId <= 0) {
            return AjaxResult.error("请先登录");
        }

        List<CmsRegistration> all = new ArrayList<>();
        for (long type = 1; type <= 2; type++) {
            CmsRegistration q = new CmsRegistration();
            q.setMemberId(memberId);
            q.setRegistrationStatus(2); // 已支付/进行中
            q.setOwnerType(type);
            all.addAll(registrationService.selectCmsRegistrationList(q));
        }

        Map<Long, RefundApplication> latestRefunds = latestRegistrationRefunds(memberId);
        // 附加主题/活动详情与真实退款资格；首次扫码字段由统一 registration mapper 读回。
        for (CmsRegistration reg : all) {
            if (reg.getOwnerType() == 1) {
                CmsTopic topic = topicService.selectCmsTopicById(reg.getOwnerId());
                if (topic != null) reg.setCmsTopic(topic);
            } else if (reg.getOwnerType() == 2) {
                CmsActivity activity = activityService.selectCmsActivityById(reg.getOwnerId());
                if (activity != null) reg.setCmsActivity(activity);
            }
            attachRefundInfoForList(reg);
            attachManualRefundInfo(reg);
            reg.setRefundApplication(latestRefunds.get(reg.getId()));
        }

        all = filterEndedOverWeek(all, new Date());
        all.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));
        stampServerNow(all);
        return AjaxResult.success(all);
    }

    /** U-033:订单状态标签(未开始/进行中/已完成)按服务端时刻判,手机时钟只作缺字段时的兜底。 */
    private static void stampServerNow(List<CmsRegistration> rows) {
        if (rows == null) return;
        long now = System.currentTimeMillis();
        for (CmsRegistration row : rows) {
            if (row != null) row.setServerNow(now);
        }
    }

    /** 存量主题未写 product_type 时，与游玩页一致地按第一张票的 mode 回落。 */
    private Integer readTopicProductType(Long topicId) {
        if (topicId == null) {
            return ProductType.ORIENTEERING;
        }
        com.chengyinhub.business.domain.OmsTicket probe = new com.chengyinhub.business.domain.OmsTicket();
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        List<com.chengyinhub.business.domain.OmsTicket> tickets = omsTicketService.selectOmsTicketList(probe);
        if (tickets != null) {
            for (com.chengyinhub.business.domain.OmsTicket ticket : tickets) {
                if (ticket != null && ticket.getMode() != null) {
                    return ticket.getMode();
                }
            }
        }
        return ProductType.ORIENTEERING;
    }

    /** 只按报名所属玩家和本报名单查询最新退款单，打款状态不能由 registrationStatus 推断。 */
    private RefundApplication latestRegistrationRefund(Long registrationId, Long memberId) {
        if (registrationId == null || memberId == null || memberId <= 0) {
            return null;
        }
        RefundApplication query = new RefundApplication();
        query.setSourceType("registration");
        query.setSourceId(registrationId);
        query.setMemberId(memberId);
        List<RefundApplication> rows = refundService.selectRefundList(query);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** 订单列表一次取回当前玩家的退款单，按 mapper 的倒序结果保留每笔报名最新一条，避免 N+1 查询。 */
    private Map<Long, RefundApplication> latestRegistrationRefunds(Long memberId) {
        Map<Long, RefundApplication> result = new HashMap<>();
        if (memberId == null || memberId <= 0) {
            return result;
        }
        RefundApplication query = new RefundApplication();
        query.setSourceType("registration");
        query.setMemberId(memberId);
        List<RefundApplication> rows = refundService.selectRefundList(query);
        if (rows == null) {
            return result;
        }
        for (RefundApplication row : rows) {
            if (row != null && row.getSourceId() != null && !result.containsKey(row.getSourceId())) {
                result.put(row.getSourceId(), row);
            }
        }
        return result;
    }

    /** 订单详情只投影公开昵称，避免把主办方完整会员资料(手机号等)暴露给报名者。 */
    private void attachOrganizer(CmsRegistration registration) {
        if (registration == null) {
            return;
        }
        Long organizerId = null;
        if (registration.getCmsTopic() != null) {
            organizerId = registration.getCmsTopic().getMemberId();
        } else if (registration.getCmsActivity() != null) {
            organizerId = registration.getCmsActivity().getMemberId();
        }
        if (organizerId == null || organizerId <= 0) {
            return;
        }
        UmsMember organizer = umsMemberService.selectUmsMemberById(organizerId);
        if (organizer != null && !StringUtils.isEmpty(organizer.getNickname())) {
            registration.setOrganizerName(organizer.getNickname());
        }
    }

    /** 列表也复用订单详情的退款口径，前端据此展示不可退款而非再按固定 24 小时猜测。 */
    private void attachRefundInfoForList(CmsRegistration registration) {
        if (registration == null || registration.getRegistrationStatus() == null
                || registration.getRegistrationStatus() != 2) {
            return;
        }
        if (registration.getOwnerType() != null && registration.getOwnerType() == 1L) {
            CmsTopic topic = registration.getCmsTopic() != null ? registration.getCmsTopic()
                    : topicService.selectCmsTopicById(registration.getOwnerId());
            com.chengyinhub.business.domain.OmsTicket ticket = registration.getTicketId() == null
                    ? null : omsTicketService.selectOmsTicketById(registration.getTicketId());
            if (topic != null) {
                attachRefundVerdict(registration,
                        com.chengyinhub.business.util.RefundPolicy.evaluateTopicFulfillmentWindow(
                                registration.getPaymentStatus(), registration.getVerificationStatus(), topic, ticket, new Date()));
            }
            return;
        }
        if (registration.getOwnerType() != null && registration.getOwnerType() == 2L
                && registration.getCmsActivity() != null) {
            attachRefundVerdict(registration,
                    com.chengyinhub.business.util.RefundPolicy.evaluate(
                            2L, registration.getPaymentStatus(), registration.getVerificationStatus(),
                            null, null, null, registration.getCmsActivity().getStartDate(),
                            registration.getPurchaseKind(), registration.getRefundDeadline(),
                            registration.getPayableAmount(), registration.getPointUsed(), new Date()));
        }
    }

    /**
     * 退款判定结果落到详情/列表上的唯一出口。
     *
     * <p>⚠️ {@code manualReview}(算不出来)这一档**没有真队列** —— 判定若只塞进展示就丢了,
     * 用户看到一句「需人工核实,请联系客服」,平台侧却零观测。这里先做到「不静默」:
     * 每有一条转人工的单被玩家读到,就打一条可 grep 的 {@code [退款转人工]} 日志
     * (玩家侧的取消按钮会被客户端提前拦住,所以读侧是后端唯一能看到这些单的地方)。
     */
    private void attachRefundVerdict(CmsRegistration registration,
                                     com.chengyinhub.business.util.RefundPolicy.Verdict verdict) {
        if (verdict != null && verdict.manualReview) {
            logger.warn("[退款转人工] registrationId={} ownerType={} ownerId={} reason={}",
                    registration.getId(), registration.getOwnerType(), registration.getOwnerId(),
                    verdict.reason);
        }
        // 1-21 F07:政策文案不看金额,零元单没有钱可「原路退回」。只改单张单(无父单号):
        // 多票父单的现金挂在首单上,从单自身金额不代表整单有没有付钱。
        // ponytail: 零元多票父单仍显示原文案,要改需按父单号查首单
        // 存量单 wechat_payment_amount 可能为空 ⇒ 回落应付额;两者都空就不下「零元」结论。
        java.math.BigDecimal cash = registration.getWechatPaymentAmount() != null
                ? registration.getWechatPaymentAmount() : registration.getPayableAmount();
        if (verdict != null && verdict.refundable && StringUtils.isEmpty(registration.getParentOrderNo())
                && cash != null && cash.signum() <= 0) {
            boolean usedPoints = registration.getPointUsed() != null && registration.getPointUsed() > 0;
            verdict = new com.chengyinhub.business.util.RefundPolicy.Verdict(true,
                    usedPoints ? "可免费取消,已用积分退回账户" : "可免费取消", verdict.deadline, verdict.manualReview);
        }
        registration.setRefundInfo(verdict);
    }

    private void attachManualRefundInfo(CmsRegistration registration) {
        if (registration != null && StringUtils.isNotEmpty(registration.getParentOrderNo())) {
            cancellationReadService.attachManualRefundInfo(registration);
        }
    }

    /**
     * 过滤「我参与中」列表(myJoined):owner(主题/活动)结束时间早于 now - HIDE_AFTER_END_DAYS 天的单不返回。
     * 结束时间未知(end_date 为 null 或 owner 未挂载)一律保留 —— 保守处理,避免误隐藏。
     * ⚠️ 只给 myJoined 用;订单列表(myRegistrationList)是账本,不能套(见方法内注释)。
     */
    public static List<CmsRegistration> filterEndedOverWeek(List<CmsRegistration> list, Date now) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        long cutoff = now.getTime() - HIDE_AFTER_END_DAYS * 24L * 60L * 60L * 1000L;
        List<CmsRegistration> visible = new ArrayList<>();
        for (CmsRegistration reg : list) {
            Date endDate = resolveOwnerEndDate(reg);
            if (endDate != null && endDate.getTime() < cutoff) {
                continue; // 结束超过 N 天 → 隐藏
            }
            visible.add(reg);
        }
        return visible;
    }

    /** 取 owner 结束时间:owner_type=1 主题→cms_topic.end_date;=2 活动→cms_activity.end_date;其余/未挂载→null。 */
    private static Date resolveOwnerEndDate(CmsRegistration reg) {
        if (reg == null || reg.getOwnerType() == null) {
            return null;
        }
        long ownerType = reg.getOwnerType();
        if (ownerType == 1L && reg.getCmsTopic() != null) {
            return reg.getCmsTopic().getEndDate();
        }
        if (ownerType == 2L && reg.getCmsActivity() != null) {
            return reg.getCmsActivity().getEndDate();
        }
        return null;
    }

    public static boolean hasEffectiveMerchantCoopOrder(com.chengyinhub.business.mapper.CoopOrderMapper coopOrderMapper, Long topicId, Long memberId) {
        if (topicId == null || memberId == null) {
            return false;
        }
        for (CoopOrder o : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if ("merchant".equals(o.getToType()) && memberId.equals(o.getToId())) {
                return true;
            }
        }
        return false;
    }
}

package com.chengyinhub.web.controller.business;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.domain.dto.RegistrationRejectDTO;
import com.chengyinhub.business.domain.vo.MerchantDispatchCandidateVO;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 商家主题报名Controller
 * 
 * @author webxh
 * @date 2025-09-14
 */
@RestController
@RequestMapping("/business/registrationmerchant")
public class CmsRegistrationMerchantController extends BaseController
{
    @Autowired
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;

    @Autowired
    private IDispatchService dispatchService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /**
     * 查询商家主题报名列表
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsRegistrationMerchant cmsRegistrationMerchant)
    {
        if (!canViewTopicOrGlobal(cmsRegistrationMerchant == null ? null : cmsRegistrationMerchant.getTopicId())) {
            return getDataTable(java.util.Collections.<CmsRegistrationMerchant>emptyList());
        }
        startPage();
        List<CmsRegistrationMerchant> list = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
        for (int i = 0; i < list.size(); i++) {
            CmsTopic  cmsTopic = cmsTopicService.selectCmsTopicById(list.get(i).getTopicId());
            if (cmsTopic != null) {
                list.get(i).setTopicName(cmsTopic.getName());
            }

            CmsTopicNode cmsTopicNode = cmsTopicNodeService.selectCmsTopicNodeById(list.get(i).getNodeId());
            if (cmsTopicNode != null) {
                list.get(i).setNodeName(cmsTopicNode.getName());
            }

            CmsMemberTemplate cmsMemberTemplate = cmsMemberTemplateService.selectCmsMemberTemplateById(list.get(i).getTemplateId());
            if (cmsMemberTemplate != null) {
                list.get(i).setTemplateName(cmsMemberTemplate.getTitle());
            }
        }
        return getDataTable(list);
    }

    /**
     * 查询商家主题报名列表
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:list')")
    @GetMapping("/list_join")
    public TableDataInfo listJoin(String topicId)
    {
        Long parsedTopicId;
        try {
            parsedTopicId = topicId == null ? null : Long.valueOf(topicId);
        } catch (NumberFormatException e) {
            return getDataTable(java.util.Collections.<CmsRegistrationMerchant>emptyList());
        }
        if (!canViewTopicOrGlobal(parsedTopicId)) {
            return getDataTable(java.util.Collections.<CmsRegistrationMerchant>emptyList());
        }
        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
        cmsRegistrationMerchant.setStatus(1L);
        cmsRegistrationMerchant.setTopicId(parsedTopicId);
        List<CmsRegistrationMerchant> list = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
        for (int i = 0; i < list.size(); i++) {
            CmsTopic  cmsTopic = cmsTopicService.selectCmsTopicById(list.get(i).getTopicId());
            if (cmsTopic != null) {
                list.get(i).setCmsTopic(cmsTopic);
                list.get(i).setTopicName(cmsTopic.getName());
            }

            CmsTopicNode cmsTopicNode = cmsTopicNodeService.selectCmsTopicNodeById(list.get(i).getNodeId());
            if (cmsTopicNode != null) {
                list.get(i).setNodeName(cmsTopicNode.getName());
            }

            CmsMemberTemplate cmsMemberTemplate = cmsMemberTemplateService.selectCmsMemberTemplateById(list.get(i).getTemplateId());
            if (cmsMemberTemplate != null) {
                list.get(i).setTemplateName(cmsMemberTemplate.getTitle());
            }
        }
        return getDataTable(list);
    }


    /**
     * 导出商家主题报名列表
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:export')")
    @Log(title = "商家主题报名", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsRegistrationMerchant cmsRegistrationMerchant)
    {
        List<CmsRegistrationMerchant> list = canViewTopicOrGlobal(
                cmsRegistrationMerchant == null ? null : cmsRegistrationMerchant.getTopicId())
                ? cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant)
                : java.util.Collections.<CmsRegistrationMerchant>emptyList();
        ExcelUtil<CmsRegistrationMerchant> util = new ExcelUtil<CmsRegistrationMerchant>(CmsRegistrationMerchant.class);
        util.exportExcel(response, list, "商家主题报名数据");
    }

    /**
     * 获取商家主题报名详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        CmsRegistrationMerchant registration = cmsRegistrationMerchantService.selectCmsRegistrationMerchantById(id);
        if (!canViewRegistration(registration)) {
            return error("商家报名不存在或当前账号无查看范围");
        }
        return success(registration);
    }

    /**
     * 新增商家主题报名
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:add')")
    @Log(title = "商家主题报名", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsRegistrationMerchant cmsRegistrationMerchant)
    {
        if (!canViewTopic(cmsRegistrationMerchant == null ? null : cmsRegistrationMerchant.getTopicId())) {
            return error("主题不存在或当前账号无操作范围");
        }
        // A2-3(P0):后台裸建单不得直接置中标(audit_status=1)——中标只能走 select/confirmWinnerIfPending
        // 的 CAS + 逐节点唯一守卫(A-13)。强制新建=待调配(0),堵「凭空造一行 audit_status=1 绕过防双中标」。
        // 后台裸建单与 C 端报名同口径：只能建立干净的待调配候选，不能借 status/audit_status
        // 或历史分润/驳回字段伪造已承接、已结算或已处置状态。中标/落选只走调配状态机。
        cmsRegistrationMerchant.setStatus(0L);
        cmsRegistrationMerchant.setAuditStatus(0L);
        cmsRegistrationMerchant.setReason(null);
        cmsRegistrationMerchant.setSharingRate(null);
        cmsRegistrationMerchant.setSharingAmount(null);
        cmsRegistrationMerchant.setDelFlag(0);
        return toAjax(cmsRegistrationMerchantService.insertCmsRegistrationMerchant(cmsRegistrationMerchant));
    }

    /**
     * 旧通用报名编辑入口已停用。
     *
     * <p>报名中的择优状态、驳回原因和任何历史分润字段都不能再由通用 CRUD 写入；
     * 选定和驳回应统一走调配状态机。兼容路由保留为明确拒绝，避免旧调用静默改写报名事实。</p>
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:edit')")
    @Log(title = "已废弃的商家报名通用编辑入口", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsRegistrationMerchant cmsRegistrationMerchant)
    {
        return AjaxResult.error("商家报名不再支持通用编辑；选定或驳回请使用商家调配流程");
    }


    /**
     * 旧报名分润入口已停用。
     *
     * <p>报名调配只决定承接资格，分账条款必须随合作邀约冻结；此兼容路由保留为明确拒绝，
     * 防止历史调用方把 {@code sharing_rate} 重新写回报名记录。</p>
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:edit')")
    @Log(title = "已废弃的商家报名分润入口", businessType = BusinessType.UPDATE)
    @PutMapping("/sharingRates")
    public AjaxResult setSharingRates()
    {
        return AjaxResult.error("商家报名不再设置分润比例；请在带条款的合作邀约中配置结算");
    }

    /**
     * 删除商家主题报名
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:remove')")
    @Log(title = "商家主题报名", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        if (ids == null || ids.length == 0) {
            return error("商家报名ID不能为空");
        }
        // 中标承接记录关联已发布履约，不允许通用删除破坏承接链路；须先走取消承接流程。
        for (Long id : ids) {
            CmsRegistrationMerchant existing = id == null ? null
                    : cmsRegistrationMerchantService.selectCmsRegistrationMerchantById(id);
            if (!canViewRegistration(existing)) {
                return error("商家报名不存在或当前账号无操作范围");
            }
            if (Long.valueOf(1L).equals(existing.getAuditStatus())) {
                return AjaxResult.error("已中标报名不可直接删除，请先取消承接");
            }
        }
        try {
            // Service 在同一事务中按稳定顺序锁全批、复验当前 scope/中标态；异常已回滚后再返回拒绝。
            return toAjax(cmsRegistrationMerchantService.deleteCmsRegistrationMerchantByIds(ids));
        } catch (ServiceException conflict) {
            return AjaxResult.error(conflict.getCode(), conflict.getMessage());
        }
    }

    /**
     * 调配审核树：主题 → 商家报名（平铺）。
     *
     * <p>⚠️ 不再按「模板」分层 —— 模板是错误粒度（7-04 A-09）：真实竞争位是
     * {@code (topicId, nodeId)}（node_id=0/null 不占竞争位），择优判定在 DispatchService 的
     * {@code slotHasOtherWinner}/win_key。本树只负责把候选按主题摊平，行内自带 nodeId/templateId
     * 供前端展示竞争位与内容模板。
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:list')")
    @GetMapping("/dispatch_tree")
    public AjaxResult dispatchTree(Long topicId)
    {
        if (!canViewTopicOrGlobal(topicId)) {
            return error("当前账号无主题商家调配查看范围");
        }
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setDelFlag(0);
        if (topicId != null) probe.setTopicId(topicId);
        List<CmsRegistrationMerchant> list = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(probe);

        Map<Long, Map<String, Object>> topicMap = new LinkedHashMap<>();
        for (CmsRegistrationMerchant r : list) {
            Long tid = r.getTopicId();
            Map<String, Object> topicNode = topicMap.get(tid);
            if (topicNode == null) {
                topicNode = new LinkedHashMap<>();
                topicNode.put("topicId", tid);
                CmsTopic topic = tid != null ? cmsTopicService.selectCmsTopicById(tid) : null;
                topicNode.put("topicName", topic != null ? topic.getName() : ("主题#" + tid));
                topicNode.put("merchantStatus", topic != null ? topic.getMerchantStatus() : null);
                topicNode.put("registrations", new ArrayList<MerchantDispatchCandidateVO>());
                topicMap.put(tid, topicNode);
            }
            @SuppressWarnings("unchecked")
            List<MerchantDispatchCandidateVO> regs = (List<MerchantDispatchCandidateVO>) topicNode.get("registrations");
            regs.add(MerchantDispatchCandidateVO.from(r));
        }
        return AjaxResult.success(new ArrayList<>(topicMap.values()));
    }

    /**
     * 调配-选定中标:选定该商家承接此(主题,地点),同地点其余待调配报名置落选。
     *
     * <p><b>★ Task 4:这里拆掉了「复制一层」</b>(建 CmsActivity + 把主题票复制成商家的活动票)。
     *
     * <p><b>那一层是怎么来的</b>(用户 2026-07-17 口述,代码里读不出来):
     * 「本质上是 web 端后台没办法发主题,之前外包的旧帐。目的就是简单。因为商家报名活动的表单没有,
     * 所以就直接复制了一层 —— 我那边开,就复制。本质上这样是不对的。」
     * ⇒ 它是 workaround,不是产品设计。成因已由 Task 2(后台能发主题)消除,所以这一层可以拆了。
     *
     * <p><b>它的危害</b>:活动票 owner_type=2 且 memberId=中标商家 ⇒ 票款判给了商家,
     * 而定稿 §6.5-B 是「票款归内容槽持有者」= 发布者。「官方发的却不是官方的」正是这么来的。
     *
     * <p><b>★ 选中 ≠ 成交,这里也不生成 coop_order</b>(施工图 §3.6):
     * 条款不挂在报名上、挂在【邀约】上 —— 定稿 §6.5-C:「主理人/俱乐部在 coop 邀约页选商家、
     * 随邀约带分账条款,对方接受即生成 coop_order(条款快照冻结,结算只认合作单)」。
     * <ol>
     *   <li>定价权归吃残差的人(D15):发布者拿 margin、卖不掉是他亏 ⇒ 他定价;</li>
     *   <li>商家有否决权:接受即成交、不接受就不接;</li>
     *   <li>★ 报名那一刻定价所需的信息还不存在 ⇒ 逼他此刻报价 = 逼他瞎猜。
     *       这正是 {@code sharing_rate} 至今没人填、最后被结算兜底污染成 100 的根因。</li>
     * </ol>
     * 所以本方法只做两件事:<b>占住竞争位 + 清扫同地点其余待调配报名置落选</b>。
     * 合作单由发布者发 type0 邀约(带条款)、商家接受后经 {@code acceptInviteWithLock} 生成 ——
     * 那条路还自带四道闸(主题锁价禁入 / 主题级合作方上限 / 被邀商家封顶 5 家 / 保证金),
     * 从报名里抄条款直接成单会把这四道闸全绕开。
     *
     * <p>⚠️ 存量主题(2/19/21)已有的活动与票<b>不动</b>:它们是既成事实,拆的是【今后】的路。
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:edit')")
    @Log(title = "商家报名调配-选定中标", businessType = BusinessType.UPDATE)
    @PutMapping("/select/{id}")
    public AjaxResult select(@PathVariable Long id)
    {
        CmsRegistrationMerchant registration = cmsRegistrationMerchantService.selectCmsRegistrationMerchantById(id);
        if (!canViewRegistration(registration)) {
            return error("商家报名不存在或当前账号无操作范围");
        }
        DispatchResult result = dispatchService.confirmRegistration(id);
        if (!result.isSelected()) return AjaxResult.error(result.getMessage());
        return AjaxResult.success("已选定中标商家。请向该商家发出带条款的合作邀约 —— 接受后才成合作单,结算只认合作单");
    }

    /**
     * 调配-驳回：单条报名置为落选
     */
    @PreAuthorize("@ss.hasPermi('business:registrationmerchant:edit')")
    @Log(title = "商家报名调配-驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/reject/{id}")
    public AjaxResult reject(@PathVariable Long id, @Valid @RequestBody RegistrationRejectDTO request)
    {
        CmsRegistrationMerchant registration = cmsRegistrationMerchantService.selectCmsRegistrationMerchantById(id);
        if (!canViewRegistration(registration)) {
            return error("商家报名不存在或当前账号无操作范围");
        }
        if (request == null || StringUtils.isBlank(request.getReason())) {
            return AjaxResult.error("请填写驳回原因");
        }
        String reason = request.getReason().trim();
        if (reason.length() > 200) {
            return AjaxResult.error("驳回原因不能超过200字");
        }
        DispatchResult result = dispatchService.rejectRegistration(id, reason);
        return result.isRejected() ? AjaxResult.success(result.getMessage()) : AjaxResult.error(result.getMessage());
    }


    private boolean canViewRegistration(CmsRegistrationMerchant registration) {
        return registration != null && canViewTopic(registration.getTopicId());
    }

    private boolean canViewTopicOrGlobal(Long topicId) {
        return topicId == null ? topicOperationsScopeAuthorizer.hasGlobalTopicScope() : canViewTopic(topicId);
    }

    private boolean canViewTopic(Long topicId) {
        return topicId != null && topicOperationsScopeAuthorizer.canViewTopic(topicId);
    }
}

package com.chengyinhub.web.controller.business;

import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsTopicCreateDTO;
import com.chengyinhub.business.domain.dto.CmsTopicEditDTO;
import com.chengyinhub.business.domain.dto.TopicContentSlotReassignDTO;
import com.chengyinhub.business.domain.dto.TopicOperationsSnapshotDTO;
import com.chengyinhub.business.domain.dto.TopicPlazaRecommendationDTO;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.mapper.RoamMapper;
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
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 主题Controller
 *
 * @author webxh
 * @date 2025-09-12
 */
@RestController
@RequestMapping("/business/topic")
public class CmsTopicController extends BaseController
{

    @Autowired
    private ITopicAdminCommandService topicAdminCommandService;
    @Autowired
    private ITopicContentOwnershipService topicContentOwnershipService;
    @Autowired
    private IProductRoleService productRoleService;
    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @Autowired
    private ITopicWorkspaceService topicWorkspaceService;


    /** 主题列表的跨服务只读投影。 */
    @Autowired
    private ITopicListProjectionService topicListProjectionService;

    @Autowired
    private ITopicOperationsSnapshotService topicOperationsSnapshotService;

    @Autowired
    private RoamMapper roamMapper;

    /** C-6 漫游据点奖励调证（只读，最多返回最近500条）。 */
    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping("/roam-rewards")
    public AjaxResult roamRewards(Long poiId, Long memberId, Integer status)
    {
        if (!topicOperationsScopeAuthorizer.hasGlobalTopicScope()) {
            return error("当前账号无漫游奖励调证范围");
        }
        return success(roamMapper.selectAdminRewardEvidence(poiId, memberId, status));
    }

    @Autowired
    private ClubLeadMapper clubLeadMapper;


    @Autowired
    private IViewCollaboratorsService viewCollaboratorsService;

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private IOmsTicketService omsTicketService;

    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;


    /**
     * 复制官方主题。普通会员/俱乐部主题由各自发布链路复制，CMS 不得代写其内容或票款归属。
     */
    @PreAuthorize("@ss.hasPermi('business:topic:copy')")
    @Log(title = "主题", businessType = BusinessType.INSERT)
    @PostMapping("/copy/{id}")
    public AjaxResult copy(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params)
    {
        if (!topicOperationsScopeAuthorizer.canViewTopic(id)) {
            return error("主题不存在或当前账号无操作范围");
        }
        try {
            return success(cmsTopicService.copyOfficialCmsTopic(id, params));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            logger.error("复制主题失败", e);
            return error("复制主题失败：" + e.getMessage());
        }
    }


    /**
     * 查询主题列表
     */
    @PreAuthorize("@ss.hasPermi('business:topic:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsTopic cmsTopic)
    {
        if (!topicOperationsScopeAuthorizer.hasGlobalTopicScope()) {
            return getDataTable(java.util.Collections.<CmsTopic>emptyList());
        }
        startPage();
        cmsTopic.setDelFlag(0);
        List<CmsTopic> list = topicListProjectionService.project(
                cmsTopicService.selectCmsTopicList(cmsTopic));
        return getDataTable(list);
    }

    /**
     * 导出主题列表
     */
    @PreAuthorize("@ss.hasPermi('business:topic:export')")
    @Log(title = "主题", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsTopic cmsTopic)
    {
        ExcelUtil<CmsTopic> util = new ExcelUtil<CmsTopic>(CmsTopic.class);
        if (!topicOperationsScopeAuthorizer.hasGlobalTopicScope()) {
            util.exportExcel(response, java.util.Collections.<CmsTopic>emptyList(), "主题数据");
            return;
        }
        List<CmsTopic> candidates = cmsTopicService.selectCmsTopicList(cmsTopic);
        List<CmsTopic> list = new java.util.ArrayList<CmsTopic>();
        if (candidates != null) {
            for (CmsTopic topic : candidates) {
                if (topic != null && topicOperationsScopeAuthorizer.canViewTopic(topic.getId())) {
                    list.add(topic);
                }
            }
        }
        util.exportExcel(response, list, "主题数据");
    }

    /**
     * 获取主题详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        if (!topicOperationsScopeAuthorizer.canViewTopic(id)) {
            return error("主题不存在或当前账号无查看范围");
        }
        CmsTopic cmsTopic = cmsTopicService.selectCmsTopicById(id);
        if (cmsTopic!=null){
            cmsTopic.setRoamReferralEnabled(cmsTopicService.selectRoamReferralEnabledById(id));
            ViewCollaborators cmsCollaborators = new ViewCollaborators();
            cmsCollaborators.setDataType(1L);
            cmsCollaborators.setDataId(id);
            List<ViewCollaborators> cmsCollaboratorsList = viewCollaboratorsService.selectViewCollaboratorsList(cmsCollaborators);
            cmsTopic.setCmsCollaboratorsList(cmsCollaboratorsList);

//            CmsMemberTemplate cmsMemberTemplate = new CmsMemberTemplate();
//            cmsMemberTemplate.setId(cmsTopic.getTemplateId());
//            List<CmsMemberTemplate> cmsMemberTemplateList = cmsMemberTemplateService.selectCmsMemberTemplateList(cmsMemberTemplate);
//            cmsTopic.setCmsMemberTemplateList(cmsMemberTemplateList);

            OmsTicket omsTicket = new OmsTicket();
            omsTicket.setOwnerType(1L);
            omsTicket.setOwnerId(id);
            List<OmsTicket> omsTicketList = omsTicketService.selectOmsTicketList(omsTicket);
            cmsTopic.setOmsTicketList(omsTicketList);

            CmsTopicChapter cmsTopicChapter = new CmsTopicChapter();
            cmsTopicChapter.setTopicId(id);
            List<CmsTopicChapter> cmsTopicChapterList = cmsTopicChapterService.selectCmsTopicChapterList(cmsTopicChapter);
            for (int i = 0; i < cmsTopicChapterList.size(); i++) {
                CmsTopicNode cmsTopicNode =new CmsTopicNode();
                cmsTopicNode.setTopicId(id);
                cmsTopicNode.setChapterId(cmsTopicChapterList.get(i).getId());
                List<CmsTopicNode> cmsTopicNodeList = cmsTopicNodeService.selectCmsTopicNodeList(cmsTopicNode);
                cmsTopicChapterList.get(i).setCmsTopicNodeList(cmsTopicNodeList);
            }
            cmsTopic.setCmsTopicChapterList(cmsTopicChapterList);
        }
        return success(cmsTopic);
    }

    /** 主题工作台履约 Tab：活动、责任领队、阶段和到场名单只读聚合。 */
    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping("/{id}/lead-fulfillment")
    public AjaxResult leadFulfillment(@PathVariable Long id)
    {
        if (!topicOperationsScopeAuthorizer.canViewTopic(id)) {
            return error("主题不存在或当前账号无查看范围");
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (com.chengyinhub.business.domain.ClubLeadState state : clubLeadMapper.selectStatesByTopic(id)) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("activityId", state.getActivityId());
            row.put("leaderMemberId", state.getLeaderMemberId());
            row.put("currentChapterId", state.getCurrentChapterId());
            row.put("status", state.getStatus());
            row.put("broadcastText", state.getBroadcastText());
            row.put("broadcastAt", state.getBroadcastAt());
            row.put("checkins", clubLeadMapper.selectCheckins(state.getActivityId()));
            rows.add(row);
        }
        return success(rows);
    }



    /**
     * 主题运营工作台只读聚合快照。
     *
     * <p>与 getInfo 的编辑详情不同：此接口把候选、合作、履约和结算投影到白名单 DTO，
     * 不返回报名邮箱、验证答案、权益快照和结算报名主键快照。</p>
     */
    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping("/{id}/workspace")
    public AjaxResult workspace(@PathVariable("id") Long id)
    {
        com.chengyinhub.business.domain.dto.TopicWorkspaceDTO workspace =
                topicWorkspaceService.getWorkspace(id);
        return workspace == null ? error("主题不存在或当前账号无查看范围") : success(workspace);
    }

    /**
     * 主题 360 的统一待办只读快照。
     *
     * <p>此端点只返回来源引用、派生展示状态和动作可用性；它不接收 sourceType/action，
     * 也不提供任何通用写入口。对象级受托范围尚未有权威真源的动作由服务层返回禁用原因。</p>
     */
    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping("/{id}/operations")
    public AjaxResult operations(@PathVariable("id") Long id)
    {
        TopicOperationsSnapshotDTO snapshot = topicOperationsSnapshotService.getSnapshot(id);
        return snapshot == null ? error("主题不存在或当前账号无查看范围") : success(snapshot);
    }

    /**
     * 高风险内容归属改判：内容槽决定票款归属，使用独立权限并由服务层校验生命周期与主体有效性。
     *
     * <p>旧槽只会失效不会删除，接口审计记录操作人，角色槽服务记录前后值。</p>
     */
    @PreAuthorize("@ss.hasPermi('business:topic:content-slot:edit')")
    @Log(title = "主题内容归属改判", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/content-slot")
    public AjaxResult reassignContentSlot(@PathVariable Long id,
                                           @RequestBody TopicContentSlotReassignDTO request)
    {
        if (request == null) {
            return error("内容槽改判参数不能为空");
        }
        try {
            boolean ok = topicContentOwnershipService.reassignContentSlot(
                    id, request.getEntityType(), request.getEntityId());
            return ok ? AjaxResult.success()
                    : AjaxResult.error("内容槽改判失败:entityType 须为 PLATFORM/MEMBER/CLUB,"
                            + "且 PLATFORM 不得带 entityId、其余必须带 entityId");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /** ORGANIZER actor 高风险改判；声明人只能取服务端登录账号，生命周期/范围闸在角色槽深模块。 */
    @PreAuthorize("@ss.hasPermi('business:topic:content-slot:edit')")
    @Log(title = "主题组织方 actor 改判", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/organizer-actor")
    public AjaxResult declareShopDayOrganizerActor(@PathVariable Long id)
    {
        try {
            return productRoleService.declareRequiredOrganizerSlot(
                    id, getUserId())
                    ? AjaxResult.success() : AjaxResult.error("organizer actor 改判失败");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 新增官方主题。俱乐部/会员主题必须通过各自发布链路创建，CMS 只负责监管与处置。
     *
     * @return 新主题 id
     */
    @PreAuthorize("@ss.hasPermi('business:topic:add')")
    @Log(title = "主题", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsTopicCreateDTO request)
    {
        if (request == null || !RoleEntityType.PLATFORM.equals(request.getPublisherType())) {
            return error("官方主题入口仅允许创建 PLATFORM 内容主体；俱乐部/会员主题请走各自发布链路");
        }
        if (!topicOperationsScopeAuthorizer.hasGlobalTopicScope()) {
            return error("当前账号无官方主题创建范围");
        }
        try {
            return success(cmsTopicService.createTopicByPublisher(request, getUsername()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 修改受托范围内的主题字段。
     *
     * <p>官方主题可修改普通编辑白名单；俱乐部/会员主题仅可调整逐期漫游导流开关。
     * 字段白名单、内容主体和生命周期状态机均在受控服务命令中执行。</p>
     */
    @PreAuthorize("@ss.hasPermi('business:topic:edit')")
    @Log(title = "主题", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsTopicEditDTO request)
    {
        try {
            return toAjax(topicAdminCommandService.editTopicWithinScope(request));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 主题广场的发现治理：只允许调整推荐位，不能借普通编辑改写用户内容或价格。
     */
    @PreAuthorize("@ss.hasPermi('business:topic:plaza:recommend')")
    @Log(title = "主题广场推荐", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/plaza-recommendation")
    public AjaxResult updatePlazaRecommendation(@PathVariable Long id,
                                                 @RequestBody TopicPlazaRecommendationDTO request)
    {
        try {
            return toAjax(topicAdminCommandService.updatePlazaRecommendation(id, request == null ? null : request.getIsRecommend()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 删除主题
     */
    @PreAuthorize("@ss.hasPermi('business:topic:remove')")
    @Log(title = "主题", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        try {
            return toAjax(topicAdminCommandService.deleteOfficialTopics(ids));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }
}

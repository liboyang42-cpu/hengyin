package com.chengyinhub.web.controller.business;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
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
import com.chengyinhub.common.utils.CoordinateUtils;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 活动Controller
 * 
 * @author webxh
 * @date 2025-08-31
 */
@RestController
@RequestMapping("/business/activity")
public class CmsActivityController extends BaseController
{
    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private IViewCollaboratorsService viewCollaboratorsService;


    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private IOmsTicketService omsTicketService;

    /**
     * 查询活动列表
     */
    @PreAuthorize("@ss.hasPermi('business:activity:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsActivity cmsActivity)
    {
        startPage();
        cmsActivity.setDelFlag(0);
        cmsActivity.setSortType(1);
        List<CmsActivity> list = cmsActivityService.selectCmsActivityList(cmsActivity);
        return getDataTable(list);
    }

    /**
     * 导出活动列表
     */
    @PreAuthorize("@ss.hasPermi('business:activity:export')")
    @Log(title = "活动", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsActivity cmsActivity)
    {
        List<CmsActivity> list = cmsActivityService.selectCmsActivityList(cmsActivity);
        ExcelUtil<CmsActivity> util = new ExcelUtil<CmsActivity>(CmsActivity.class);
        util.exportExcel(response, list, "活动数据");
    }

    /**
     * 获取活动详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:activity:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        CmsActivity cmsActivity = cmsActivityService.selectCmsActivityById(id);
        if (cmsActivity!=null){
            ViewCollaborators cmsCollaborators = new ViewCollaborators();
            cmsCollaborators.setDataType(2L);
            cmsCollaborators.setDataId(id);
            List<ViewCollaborators> cmsCollaboratorsList = viewCollaboratorsService.selectViewCollaboratorsList(cmsCollaborators);
            cmsActivity.setCmsCollaboratorsList(cmsCollaboratorsList);

            CmsMemberTemplate cmsMemberTemplate = new CmsMemberTemplate();
            cmsMemberTemplate.setId(cmsActivity.getTemplateId());
            List<CmsMemberTemplate> cmsMemberTemplateList = cmsMemberTemplateService.selectCmsMemberTemplateList(cmsMemberTemplate);
            cmsActivity.setCmsMemberTemplateList(cmsMemberTemplateList);

            OmsTicket omsTicket = new OmsTicket();
            omsTicket.setOwnerType(2L);
            omsTicket.setOwnerId(id);
            List<OmsTicket> omsTicketList = omsTicketService.selectOmsTicketList(omsTicket);
            cmsActivity.setOmsTicketList(omsTicketList);
        }
        return success(cmsActivity);
    }

    /**
     * 新增活动
     */
    @PreAuthorize("@ss.hasPermi('business:activity:add')")
    @Log(title = "活动", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsActivity cmsActivity)
    {
        return toAjax(cmsActivityService.insertCmsActivity(cmsActivity));
    }

    /**
     * 修改活动
     */
    @PreAuthorize("@ss.hasPermi('business:activity:edit')")
    @Log(title = "活动", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsActivity cmsActivity)
    {
        CmsActivity editable = toEditableActivity(cmsActivity);
        if (!hasEditableActivityFields(editable)) {
            return error("未提供可编辑的活动字段；审核决定请在统一审核台处理");
        }
        // CR-63(2026-09-15 裁决 15):已售出(有已支付报名)的活动,后台也不许改时间与地点 ——
        // 退款钟按集合时间−24h 算,改期会把已售订单的退款窗口一起追溯挪走。
        // P1-2(审查D):「已售出」含待支付/支付中 —— 钱随时可能进来,等回调的窗口不是免锁区。
        // 文字/图片/音频仍可改;判据与 App 侧同一套(只比传了的字段,按秒比时间)。
        if (cmsActivityService.countOpenSaleRegistrations(editable.getId()) > 0) {
            String lockedChange = soldTimeLocationChange(
                    cmsActivityService.selectCmsActivityById(editable.getId()), editable);
            if (lockedChange != null) {
                return error("活动已售出，" + lockedChange + "不能修改；文字、图片、音频仍可改");
            }
        }
        return toAjax(cmsActivityService.updateCmsActivity(editable));
    }

    /**
     * CR-63:已售活动被锁的字段 —— 时间(开始/结束)与地点(名称/详细地址/坐标)。
     * 未传(null)按「没改」处理;时间按秒比较,客户端毫秒误差不算改;
     * 经纬度按数值比较(审查 §12.3):121.47 与 121.470000 是同一个点。
     */
    private String soldTimeLocationChange(CmsActivity current, CmsActivity patch)
    {
        if (current == null) {
            return null;
        }
        if (patch.getStartDate() != null && !sameSecond(current.getStartDate(), patch.getStartDate())
                || patch.getEndDate() != null && !sameSecond(current.getEndDate(), patch.getEndDate())) {
            return "时间";
        }
        if (patch.getAddressName() != null && !java.util.Objects.equals(current.getAddressName(), patch.getAddressName())
                || patch.getAddress() != null && !java.util.Objects.equals(current.getAddress(), patch.getAddress())
                || patch.getLongitude() != null
                        && !CoordinateUtils.sameCoordinate(current.getLongitude(), patch.getLongitude())
                || patch.getLatitude() != null
                        && !CoordinateUtils.sameCoordinate(current.getLatitude(), patch.getLatitude())) {
            return "地点";
        }
        return null;
    }

    private boolean sameSecond(java.util.Date left, java.util.Date right)
    {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getTime() / 1000L == right.getTime() / 1000L;
    }

    /**
     * 普通编辑只接收活动内容字段。审核态、发布态、归属、结算、取消与删除事实
     * 均由各自专用流程写入，不能借通用 PUT 夹带。
     */
    private CmsActivity toEditableActivity(CmsActivity request)
    {
        CmsActivity editable = new CmsActivity();
        editable.setId(request.getId());
        editable.setName(request.getName());
        editable.setDescription(request.getDescription());
        editable.setImgUrl(request.getImgUrl());
        editable.setImgArr(request.getImgArr());
        editable.setCategoryIds(request.getCategoryIds());
        editable.setAddressName(request.getAddressName());
        editable.setLongitude(request.getLongitude());
        editable.setLatitude(request.getLatitude());
        editable.setAddress(request.getAddress());
        editable.setDayNote(request.getDayNote());
        editable.setStartDate(request.getStartDate());
        editable.setEndDate(request.getEndDate());
        editable.setTemplateId(request.getTemplateId());
        return editable;
    }

    private boolean hasEditableActivityFields(CmsActivity activity)
    {
        return activity.getName() != null || activity.getDescription() != null
                || activity.getImgUrl() != null || activity.getImgArr() != null
                || activity.getCategoryIds() != null || activity.getAddressName() != null
                || activity.getLongitude() != null || activity.getLatitude() != null
                || activity.getAddress() != null || activity.getDayNote() != null
                || activity.getStartDate() != null || activity.getEndDate() != null
                || activity.getTemplateId() != null;
    }

    /**
     * 删除活动
     */
    @PreAuthorize("@ss.hasPermi('business:activity:remove')")
    @Log(title = "活动", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        // [资损闸] 与 App 删除同口径:软删 del_flag=2,不碰 registration、不退款。
        // 有已付款或待支付报名即整批拒绝,避免「活动没了、钱还在」。先检后改,不能先删干净的再撞上脏的。
        for (int i = 0; i < ids.length; i++) {
            Long cid = ids[i];
            if (cmsActivityService.countPaidRegistrations(cid) > 0) {
                return error("该活动已有用户付费报名,不能直接删除。请先「取消活动」(将全额退款给所有未核销报名),再删除。");
            }
            if (cmsActivityService.countPendingRegistrations(cid) > 0) {
                return error("该活动有用户的报名待支付,此刻删除会导致「钱收了、活动没了」。未支付的单会在支付窗口过期后由系统自动关单(约 30 分钟),届时即可删除。");
            }
        }
        for (int i = 0; i < ids.length; i++) {
            CmsActivity cmsActivity = new CmsActivity();
            cmsActivity.setDelFlag(2);
            cmsActivity.setId(ids[i]);
            cmsActivityService.updateCmsActivity(cmsActivity);
        }
        return toAjax(1);
    }
}

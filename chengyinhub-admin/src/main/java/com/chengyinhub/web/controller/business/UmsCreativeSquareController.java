package com.chengyinhub.web.controller.business;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 创意广场-用户发布Controller
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@RestController
@RequestMapping("/business/creativesquare")
public class UmsCreativeSquareController extends BaseController {
    @Autowired
    private IUmsCreativeSquareService umsCreativeSquareService;

    @Autowired
    private IAuditTaskService auditTaskService;

    /**
     * 查询创意广场-用户发布列表
     */
    @PreAuthorize("@ss.hasPermi('business:square:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsCreativeSquare umsCreativeSquare) {
        umsCreativeSquare.setDelFlag(0);
        startPage();
        List<UmsCreativeSquare> list = umsCreativeSquareService.selectUmsCreativeSquareList(umsCreativeSquare);
        return getDataTable(list);
    }

    /**
     * 导出创意广场-用户发布列表
     */
    @PreAuthorize("@ss.hasPermi('business:square:export')")
    @Log(title = "创意广场-用户发布", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsCreativeSquare umsCreativeSquare) {
        List<UmsCreativeSquare> list = umsCreativeSquareService.selectUmsCreativeSquareList(umsCreativeSquare);
        ExcelUtil<UmsCreativeSquare> util = new ExcelUtil<UmsCreativeSquare>(UmsCreativeSquare.class);
        util.exportExcel(response, list, "创意广场-用户发布数据");
    }

    /**
     * 获取创意广场-用户发布详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:square:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(umsCreativeSquareService.selectUmsCreativeSquareById(id));
    }

    /**
     * 新增创意广场-用户发布
     */
    @PreAuthorize("@ss.hasPermi('business:square:add')")
    @Log(title = "创意广场-用户发布", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsCreativeSquare umsCreativeSquare) {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "后台不创建用户帖文，请由用户端发布并进入统一审核流程");
    }

    /**
     * 修改创意广场-用户发布
     */
    @PreAuthorize("@ss.hasPermi('business:square:edit')")
    @Log(title = "创意广场-用户发布", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsCreativeSquare umsCreativeSquare) {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "帖文内容与上下架状态不支持后台直改，请提交统一审核");
    }

    /** 把指定帖文送入统一审核台；已有未结任务时复用，不重复刷单。 */
    @PreAuthorize("@ss.hasPermi('business:square:edit')")
    @Log(title = "创意广场-提交统一审核", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/review")
    public AjaxResult submitReview(@PathVariable Long id) {
        UmsCreativeSquare square = umsCreativeSquareService.selectUmsCreativeSquareById(id);
        if (square == null || Integer.valueOf(2).equals(square.getDelFlag())) {
            return error("帖文不存在或已删除");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bizType", AuditTask.BIZ_SQUARE);
        snapshot.put("bizId", square.getId());
        snapshot.put("memberId", square.getMemberId());
        snapshot.put("contents", square.getContents());
        snapshot.put("pics", square.getPics());
        snapshot.put("dataType", square.getDataType());
        snapshot.put("dataId", square.getDataId());
        Long taskId = auditTaskService.submitSafetyHold(AuditTask.BIZ_SQUARE, square.getId(),
                square.getMemberId(), "NORMAL", toSnapshotJson(snapshot));
        return success(taskId);
    }

    private String toSnapshotJson(Map<String, Object> snapshot) {
        try {
            return new ObjectMapper().writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{\"bizType\":\"SQUARE\",\"bizId\":" + snapshot.get("bizId") + "}";
        }
    }

    /**
     * 删除创意广场-用户发布
     */
    @PreAuthorize("@ss.hasPermi('business:square:remove')")
    @Log(title = "创意广场-用户发布", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "帖文不支持业务页软删，请提交统一审核决定是否下架");
    }
}

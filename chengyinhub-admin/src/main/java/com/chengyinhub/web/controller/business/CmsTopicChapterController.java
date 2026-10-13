package com.chengyinhub.web.controller.business;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
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
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO;
import com.chengyinhub.business.domain.dto.ChapterServiceCapacityDTO;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 主题章节Controller
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
@RestController
@RequestMapping("/business/topicchapter")
public class CmsTopicChapterController extends BaseController
{
    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /**
     * 查询主题章节列表
     */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsTopicChapter cmsTopicChapter)
    {
        if (!canViewTopic(cmsTopicChapter == null ? null : cmsTopicChapter.getTopicId())) {
            return getDataTable(java.util.Collections.<CmsTopicChapter>emptyList());
        }
        startPage();
        List<CmsTopicChapter> list = cmsTopicChapterService.selectCmsTopicChapterList(cmsTopicChapter);
        return getDataTable(list);
    }

    /**
     * 导出主题章节列表
     */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:export')")
    @Log(title = "主题章节", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsTopicChapter cmsTopicChapter)
    {
        List<CmsTopicChapter> list = canViewTopic(cmsTopicChapter == null ? null : cmsTopicChapter.getTopicId())
                ? cmsTopicChapterService.selectCmsTopicChapterList(cmsTopicChapter)
                : java.util.Collections.<CmsTopicChapter>emptyList();
        ExcelUtil<CmsTopicChapter> util = new ExcelUtil<CmsTopicChapter>(CmsTopicChapter.class);
        util.exportExcel(response, list, "主题章节数据");
    }

    /**
     * 获取主题章节详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(id);
        if (!canViewChapter(chapter)) {
            return error("章节不存在或当前账号无查看范围");
        }
        return success(chapter);
    }

    /**
     * 新增主题章节
     */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:add')")
    @Log(title = "主题章节", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsTopicChapter cmsTopicChapter)
    {
        if (!canViewTopic(cmsTopicChapter == null ? null : cmsTopicChapter.getTopicId())) {
            return error("主题不存在或当前账号无操作范围");
        }
        return toAjax(cmsTopicChapterService.insertCmsTopicChapter(cmsTopicChapter));
    }

    /**
     * 修改主题章节
     */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:edit')")
    @Log(title = "主题章节", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsTopicChapter cmsTopicChapter)
    {
        CmsTopicChapter existing = cmsTopicChapter == null || cmsTopicChapter.getId() == null
                ? null : cmsTopicChapterService.selectCmsTopicChapterById(cmsTopicChapter.getId());
        if (!canViewChapter(existing)) {
            return error("章节不存在或当前账号无操作范围");
        }
        cmsTopicChapter.setTopicId(existing.getTopicId());
        return toAjax(cmsTopicChapterService.updateCmsTopicChapter(cmsTopicChapter));
    }

    /**
     * 删除主题章节
     */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:remove')")
    @Log(title = "主题章节", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        if (ids == null || ids.length == 0) {
            return error("章节ID不能为空");
        }
        for (Long id : ids) {
            CmsTopicChapter chapter = id == null ? null : cmsTopicChapterService.selectCmsTopicChapterById(id);
            if (!canViewChapter(chapter)) {
                return error("章节不存在或当前账号无操作范围");
            }
        }
        return toAjax(cmsTopicChapterService.deleteCmsTopicChapterByIds(ids));
    }

    /**
     * [D21] 配置章节的**供给条款** —— <b>「③ 的一章怎么让商家参与」的唯一入口</b>。
     *
     * <p><b>什么时候用</b>:③-自营 的路线里,决定这一章走哪个档、平台掏不掏钱、掏多少封顶、
     * 最多让几家商家接。发布器只能标权益档/引流档 —— 计酬是**平台掏钱**的采购决策,
     * 不能让发布者(普通会员)替平台承诺;且发布器建主题那一刻内容槽还是 MEMBER,校验不了自营。
     *
     * <p><b>四个字段必须一起配</b>:它们是一套形状,不是四个开关。尤其
     * <b>计酬档没配人头费上限 = 商家入驻恒被拒</b>(enroll 的 {@code assertFeeWithinChapterCap}
     * 对 cap==null 是 fail-closed)⇒ 等于配了一个谁也进不来的章,而且零告警。
     *
     * <p><b>正确顺序</b>:先 {@code PUT /business/topic/{id}/content-slot} 把内容槽改判成 PLATFORM,
     * <b>再</b>用本接口标计酬档。顺序反了会被自营闸拒 —— <b>拒了的正确反应是把槽改对,不是把闸放宽。</b>
     * ⚠️ 后台发的主题天然是自营(insertCmsTopic 会声明 PLATFORM 槽),正常不会被拒。
     *
     * <p>⚠️ <b>改档位</b>时该章有生效商家供给会被拒(档位决定容量闸怎么走,而 offer 的档是入驻时的
     * 快照,中途改档会让存量 PERK 供给的接待上限失守 = 超发)。<b>同档位下调整上限/门槛不受此限</b> ——
     * 那正是给已在营的章补配人头费上限的场景。
     *
     * <p>权限借用 {@code topicchapter:edit} —— 能改章节就能配它的条款,不另立权限点
     * (多一个没人配的权限点等于这个接口没人能用)。与 P0-35 内容槽改判同一条口径。
     */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:edit')")
    @Log(title = "章节供给条款配置", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/supply-terms")
    public AjaxResult configureSupplyTerms(@PathVariable Long id, @RequestBody ChapterSupplyTermsDTO terms)
    {
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(id);
        if (!canViewChapter(chapter)) {
            return error("章节不存在或当前账号无操作范围");
        }
        // 闸不过时 service 抛 IllegalStateException,消息里写明是哪一道闸 ——
        // 别让运营对着一个笼统的 false 猜是档位不合法、形状不对、主题不自营、还是有存量供给。
        cmsTopicChapterService.configureSupplyTerms(id, terms);
        return AjaxResult.success();
    }

    /** 按章节读取人工批次容量，复用章节 query 权限与对象范围。 */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:query')")
    @GetMapping("/{id}/service-capacity")
    public AjaxResult getServiceCapacity(@PathVariable Long id)
    {
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(id);
        if (!canViewChapter(chapter)) {
            return error("章节不存在或当前账号无查看范围");
        }
        return success(cmsTopicChapterService.getServiceCapacity(id));
    }

    /** 一次性保存三项正整数容量，复用章节 edit 权限与对象范围。 */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:edit')")
    @Log(title = "章节人工批次容量配置", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/service-capacity")
    public AjaxResult configureServiceCapacity(@PathVariable Long id,
                                               @RequestBody ChapterServiceCapacityDTO capacity)
    {
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(id);
        if (!canViewChapter(chapter)) {
            return error("章节不存在或当前账号无操作范围");
        }
        cmsTopicChapterService.configureServiceCapacity(id, capacity);
        return AjaxResult.success();
    }

    /** F05 按章节读取商家工作包，复用章节 query 权限与对象范围。 */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:query')")
    @GetMapping("/{id}/work-package")
    public AjaxResult getWorkPackage(@PathVariable Long id)
    {
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(id);
        if (!canViewChapter(chapter)) {
            return error("章节不存在或当前账号无查看范围");
        }
        return success(cmsTopicChapterService.getWorkPackage(id));
    }

    /** F05 一次性保存商家工作包六项（开售快照事实），复用章节 edit 权限与对象范围。 */
    @PreAuthorize("@ss.hasPermi('business:topicchapter:edit')")
    @Log(title = "章节商家工作包配置", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/work-package")
    public AjaxResult configureWorkPackage(@PathVariable Long id,
                                           @RequestBody CmsTopicChapterTemplateMeta workPackage)
    {
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(id);
        if (!canViewChapter(chapter)) {
            return error("章节不存在或当前账号无操作范围");
        }
        cmsTopicChapterService.configureWorkPackage(id, workPackage);
        return AjaxResult.success();
    }


    private boolean canViewChapter(CmsTopicChapter chapter) {
        return chapter != null && canViewTopic(chapter.getTopicId());
    }

    private boolean canViewTopic(Long topicId) {
        return topicId != null && topicOperationsScopeAuthorizer.canViewTopic(topicId);
    }
}

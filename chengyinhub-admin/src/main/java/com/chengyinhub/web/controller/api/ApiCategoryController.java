package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.util.CategoryPublicProjection;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.common.core.domain.AjaxResult;

import java.util.List;

/**
 * 主题、活动等分类
 *
 * @author webxh
 * @date 2025-08-31
 */
@RestController
@Tag(name = "分类")
@RequestMapping("/api/category")
public class ApiCategoryController extends BaseController {

    @Autowired
    private ISysCategoryService sysCategoryService;

    @PostMapping("/list")
    @Operation(summary = "分类列表")
    @Parameters({
            @Parameter(name = "type", description = "类型1、主题  2、活动  3、创意广场"),
            @Parameter(name = "parentid", description = "上级分类ID，默认为0"),
            @Parameter(name = "keyword", description = "关键字")
    })
    public AjaxResult categoryList(String type, String parentid, String keyword) {
        SysCategory category = new SysCategory();

        Long parentId = Convert.toLong(parentid, 0L);
        category.setParentId(parentId);
        category.setStatus(1);
        category.setDelFlag(0);

        if (StringUtils.isNotEmpty(keyword)) {
            category.setCategoryName(keyword);
        }
        if (StringUtils.isNotEmpty(type)) {
            category.setType(Convert.toLong(type, 0L));
        }


        List<SysCategory> sysCategoryList = sysCategoryService.selectSysCategoryList(category);
        if (parentId != 0L) {
            for (SysCategory entity : sysCategoryList) {
                category = new SysCategory();
                category.setParentId(entity.getId());
                category.setStatus(1);
                category.setDelFlag(0);

                List<SysCategory> subCategoryList = sysCategoryService.selectSysCategoryList(category);
                entity.setSubCategoryList(subCategoryList);
            }
        }

        return success(CategoryPublicProjection.strip(sysCategoryList));
    }
}

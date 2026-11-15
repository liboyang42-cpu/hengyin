package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.NodeTaskDispatch;
import com.chengyinhub.business.domain.NodeTaskPool;
import com.chengyinhub.business.domain.NodeTaskPoolItem;
import com.chengyinhub.business.service.IRandomDispatchService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 盲盒任务池管理后台接口。
 */
@RestController
@RequestMapping("/business/node-task-pool")
public class NodeTaskPoolController extends BaseController {

    @Autowired
    private IRandomDispatchService dispatchService;

    @PreAuthorize("@ss.hasPermi('business:topicnode:list')")
    @GetMapping("/list")
    public TableDataInfo list(NodeTaskPool filter) {
        startPage();
        List<NodeTaskPool> list = dispatchService.selectPoolList(filter);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        NodeTaskPool pool = dispatchService.selectPoolById(id);
        if (pool == null) {
            return AjaxResult.error("任务池不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pool", pool);
        data.put("items", dispatchService.selectItemsByPoolId(id));
        return AjaxResult.success(data);
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:add')")
    @Log(title = "盲盒任务池", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NodeTaskPool pool) {
        try {
            if (pool != null && (pool.getCreateBy() == null || pool.getCreateBy().isBlank())) {
                pool.setCreateBy(safeUsername());
            }
            return AjaxResult.success(dispatchService.savePool(pool));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:edit')")
    @Log(title = "盲盒任务池", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NodeTaskPool pool) {
        try {
            return AjaxResult.success(dispatchService.savePool(pool));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:remove')")
    @Log(title = "盲盒任务池", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id) {
        return toAjax(dispatchService.deletePoolById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:list')")
    @GetMapping("/{poolId}/items")
    public AjaxResult listItems(@PathVariable Long poolId) {
        return AjaxResult.success(dispatchService.selectItemsByPoolId(poolId));
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:edit')")
    @Log(title = "盲盒任务池条目", businessType = BusinessType.UPDATE)
    @PostMapping("/items")
    public AjaxResult saveItem(@RequestBody NodeTaskPoolItem item) {
        try {
            return AjaxResult.success(dispatchService.savePoolItem(item));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:remove')")
    @Log(title = "盲盒任务池条目", businessType = BusinessType.DELETE)
    @DeleteMapping("/items/{id}")
    public AjaxResult removeItem(@PathVariable Long id) {
        return toAjax(dispatchService.deletePoolItemById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:topicnode:list')")
    @GetMapping("/dispatch/list")
    public AjaxResult listDispatches(NodeTaskDispatch filter) {
        return AjaxResult.success(dispatchService.selectDispatchList(filter));
    }

    private String safeUsername() {
        try {
            return getUsername();
        } catch (Exception e) {
            return "admin";
        }
    }
}

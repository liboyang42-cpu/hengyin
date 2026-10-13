package com.chengyinhub.web.controller.business;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.service.IAiGenerationLogService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * AI 生成日志 Controller(只读:审计 / 成本复盘 / 申诉追溯)
 *
 * 日志表禁写,故不提供 add/edit/remove。
 *
 * @author chengyinhub
 */
@RestController
@RequestMapping("/business/ailog")
public class AiGenerationLogController extends BaseController
{
    @Autowired
    private IAiGenerationLogService aiGenerationLogService;

    /**
     * 查询 AI 生成日志列表
     */
    @PreAuthorize("@ss.hasPermi('business:ailog:list')")
    @GetMapping("/list")
    public TableDataInfo list(AiGenerationLog aiGenerationLog)
    {
        startPage();
        List<AiGenerationLog> list = aiGenerationLogService.selectAiGenerationLogList(aiGenerationLog);
        return getDataTable(list);
    }

    /**
     * 获取 AI 生成日志详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:ailog:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aiGenerationLogService.selectAiGenerationLogById(id));
    }

    /**
     * 按 traceId 下钻组题候选快照(没有则返回 null,不是错误)
     */
    @PreAuthorize("@ss.hasPermi('business:ailog:query')")
    @GetMapping(value = "/snapshot/{traceId}")
    public AjaxResult snapshot(@PathVariable("traceId") String traceId)
    {
        return success(aiGenerationLogService.selectSnapshotByTraceId(traceId));
    }
}

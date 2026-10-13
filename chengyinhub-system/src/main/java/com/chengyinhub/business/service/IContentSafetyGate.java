package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;

/**
 * 统一 UGC 安全门禁(合规实施方案 §3 · 深模块)。
 * 控制器只提交内容元信息,门禁内部负责自动检测、风险分级、审核任务、状态裁决和审计。
 * 调用方不接触检测供应商 / 状态映射 / 审核任务细节。
 */
public interface IContentSafetyGate {
    /**
     * 发布内容安全裁决:
     * PASS  = 文本检测通过且无需人工 → 公开可见;
     * HOLD  = 有图/结构化商业字段/检测不可用 → 仅作者可见并建审核单;
     * BLOCK = 文本命中风险 → 不公开,返回拒绝原因。
     * 检测异常一律 fail-safe HOLD(绝不 fail-open 直发)。
     */
    SafetyDecision decide(PublishSafetyRequest req);
}

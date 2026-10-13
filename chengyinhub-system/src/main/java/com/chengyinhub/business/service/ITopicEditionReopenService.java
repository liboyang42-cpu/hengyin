package com.chengyinhub.business.service;

/** 合同一专用命令；它不复用模板/移交的通用复制语义。 */
public interface ITopicEditionReopenService {
    Long reopenNextEdition(Long sourceTopicId, String idempotencyKey);
}

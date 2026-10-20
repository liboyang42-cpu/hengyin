package com.chengyinhub.business.exception;

/** 旧版客户端的章节写契约已不可用（需更新小程序）；API 层将其映射为 426，与普通契约错误的 400 区分。 */
public class ChapterFlowClientUpgradeRequiredException extends ChapterFlowValidationException
{
    private static final long serialVersionUID = 1L;

    public ChapterFlowClientUpgradeRequiredException(String message)
    {
        super(message);
    }
}

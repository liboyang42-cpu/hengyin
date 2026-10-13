package com.chengyinhub.business.exception;

/** 章节故事流写契约不合法；API 层将其稳定映射为 400。 */
public class ChapterFlowValidationException extends IllegalArgumentException
{
    private static final long serialVersionUID = 1L;

    public ChapterFlowValidationException(String message)
    {
        super(message);
    }
}

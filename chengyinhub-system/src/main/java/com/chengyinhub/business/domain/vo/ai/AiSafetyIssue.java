package com.chengyinhub.business.domain.vo.ai;

/**
 * 安全预检问题项。
 */
public class AiSafetyIssue
{
    /** error / warn / info */
    private String level;
    private String type;
    private String message;

    public AiSafetyIssue() {}

    public AiSafetyIssue(String level, String type, String message)
    {
        this.level = level;
        this.type = type;
        this.message = message;
    }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}

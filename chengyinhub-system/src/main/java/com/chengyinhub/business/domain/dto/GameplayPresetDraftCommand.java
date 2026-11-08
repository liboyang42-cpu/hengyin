package com.chengyinhub.business.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Map;

/**
 * 从固定玩法预设创建作者个人草稿的请求。
 *
 * <p>只有三样东西：用哪个预设、这次请求的幂等号、以及作者按预设声明自己填的值。
 * 没有 scope（预设只建个人草稿，商家草稿走原有模板入口）、没有模板 ID、
 * 没有 advancedConfigJson —— 服务端结构只从受控目录取，客户端不能自带一份玩法配置。
 */
@Schema(description = "固定预设创建个人玩法草稿")
public class GameplayPresetDraftCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "预设标识，如 T08-multi-clue-investigation", required = true)
    private String presetKey;

    @Schema(description = "幂等请求号（同一作者同一号只建一次）", required = true)
    private String requestId;

    @Schema(description = "作者按预设声明填写的参数：键必须是预设声明的字段")
    private Map<String, Object> fields;

    public String getPresetKey() { return presetKey; }
    public void setPresetKey(String presetKey) { this.presetKey = presetKey; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Map<String, Object> getFields() { return fields; }
    public void setFields(Map<String, Object> fields) { this.fields = fields; }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RoamAiContent;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.mapper.RoamAiContentMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/** CMS 侧的漫游内容写入与审核状态机；玩家消费只经 {@link RoamAiContentService}。 */
@Service
public class RoamAiContentAdminService {
    private static final int MAX_KEY_LENGTH = 64;

    @Autowired
    private RoamAiContentMapper roamAiContentMapper;
    @Autowired
    private RoamMapper roamMapper;

    public List<RoamAiContent> list(String contentType, Integer auditStatus, Long poiId) {
        return roamAiContentMapper.selectRoamAiContentList(contentType, auditStatus, poiId);
    }

    public AjaxResult getInfo(Long id) {
        RoamAiContent content = roamAiContentMapper.selectRoamAiContentById(id);
        return content == null ? AjaxResult.error("内容不存在") : AjaxResult.success(content);
    }

    public AjaxResult add(RoamAiContent content) {
        String invalid = normalizeNewContent(content);
        if (invalid != null) {
            return AjaxResult.error(invalid);
        }
        Date now = new Date();
        content.setAuditStatus(0);
        content.setEnabled(content.getEnabled() == null ? 1 : content.getEnabled());
        content.setCreateTime(now);
        content.setUpdateTime(now);
        return toAjax(roamAiContentMapper.insertRoamAiContent(content));
    }

    public AjaxResult edit(RoamAiContent content) {
        if (content == null || content.getId() == null) {
            return AjaxResult.error("参数有误");
        }
        RoamAiContent stored = roamAiContentMapper.selectRoamAiContentById(content.getId());
        if (stored == null) {
            return AjaxResult.error("内容不存在");
        }
        boolean changed = false;
        if (content.getContentText() != null) {
            String text = normalizedText(content.getContentText());
            if (text == null) {
                return AjaxResult.error("内容需为1-80字");
            }
            if (RoamAiContent.TYPE_ROUTE_STORY.equals(stored.getContentType()) && !isVerifiedRouteTemplate(text)) {
                return AjaxResult.error("路线故事只能使用 {{tiles}}、{{pois}} 两个已验证指标");
            }
            if ((RoamAiContent.TYPE_ROUTE_STORY.equals(stored.getContentType())
                    || RoamAiContent.TYPE_POI_MEANING.equals(stored.getContentType()))
                    && RoamRouteStoryPolicy.containsForwardGuidance(text)) {
                return AjaxResult.error(RoamRouteStoryPolicy.FORWARD_GUIDANCE_ERROR);
            }
            content.setContentText(text);
            content.setAuditStatus(0);
            changed = true;
        } else {
            // 普通 edit 权限不能借请求体修改审核状态；仅 audit 入口可写该列。
            content.setAuditStatus(null);
        }
        if (content.getEnabled() != null) {
            if (!isEnabled(content.getEnabled())) {
                return AjaxResult.error("启用状态有误");
            }
            changed = true;
        }
        if (!changed) {
            return AjaxResult.error("没有可编辑字段");
        }
        // 普通编辑不能把已审条目换类型/地点。
        content.setContentType(null);
        content.setContentKey(null);
        content.setPoiId(null);
        content.setCreateTime(null);
        content.setUpdateTime(new Date());
        return toAjax(roamAiContentMapper.updateRoamAiContent(content));
    }

    public AjaxResult audit(Long id, Integer status) {
        if (status == null || status < 0 || status > 2) {
            return AjaxResult.error("审核状态有误");
        }
        if (roamAiContentMapper.selectRoamAiContentById(id) == null) {
            return AjaxResult.error("内容不存在");
        }
        return toAjax(roamAiContentMapper.updateRoamAiContentAuditStatus(id, status));
    }

    public AjaxResult remove(Long id) {
        if (roamAiContentMapper.selectRoamAiContentById(id) == null) {
            return AjaxResult.error("内容不存在");
        }
        return toAjax(roamAiContentMapper.deleteRoamAiContentById(id));
    }

    private String normalizeNewContent(RoamAiContent content) {
        if (content == null || !isSupportedType(content.getContentType())) {
            return "内容类型有误";
        }
        String text = normalizedText(content.getContentText());
        if (text == null) {
            return "内容需为1-80字";
        }
        if (content.getEnabled() != null && !isEnabled(content.getEnabled())) {
            return "启用状态有误";
        }
        content.setContentText(text);
        if (RoamAiContent.TYPE_POI_MEANING.equals(content.getContentType())) {
            if (RoamRouteStoryPolicy.containsForwardGuidance(text)) {
                return RoamRouteStoryPolicy.FORWARD_GUIDANCE_ERROR;
            }
            if (content.getPoiId() == null || content.getPoiId() <= 0) {
                return "地点意义必须绑定有效 POI";
            }
            if (!isEligiblePoi(content.getPoiId())) {
                return "地点意义只能绑定有效的非商家 POI";
            }
            content.setContentKey("poi-" + content.getPoiId());
            return null;
        }
        if (content.getPoiId() != null) {
            return "路线故事不能绑定 POI";
        }
        String key = content.getContentKey() == null ? "" : content.getContentKey().trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return "路线故事内容 key 需为1-64字";
        }
        if (!isVerifiedRouteTemplate(text)) {
            return "路线故事只能使用 {{tiles}}、{{pois}} 两个已验证指标";
        }
        if (RoamRouteStoryPolicy.containsForwardGuidance(text)) {
            return RoamRouteStoryPolicy.FORWARD_GUIDANCE_ERROR;
        }
        content.setContentKey(key);
        return null;
    }

    private boolean isEligiblePoi(Long poiId) {
        List<RoamPoi> pois = roamMapper.selectPoisByIds(Collections.singletonList(poiId));
        if (pois == null || pois.isEmpty()) {
            return false;
        }
        RoamPoi poi = pois.get(0);
        return poi != null && poiId.equals(poi.getId()) && poi.getMerchantId() == null
                && !Integer.valueOf(2).equals(poi.getType());
    }

    private boolean isSupportedType(String contentType) {
        return RoamAiContent.TYPE_ROUTE_STORY.equals(contentType)
                || RoamAiContent.TYPE_POI_MEANING.equals(contentType);
    }

    private String normalizedText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isEmpty() || normalized.length() > RoamAiContent.MAX_TEXT_LENGTH ? null : normalized;
    }

    private boolean isEnabled(Integer enabled) {
        return enabled == 0 || enabled == 1;
    }

    private boolean isVerifiedRouteTemplate(String text) {
        String withoutAllowedTokens = text.replace("{{tiles}}", "").replace("{{pois}}", "");
        return !withoutAllowedTokens.contains("{{") && !withoutAllowedTokens.contains("}}");
    }

    private AjaxResult toAjax(int rows) {
        return rows > 0 ? AjaxResult.success() : AjaxResult.error();
    }
}

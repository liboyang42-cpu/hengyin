package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IBusinessAuditLogQueryService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
<<<<<<< public
import java.util.Locale;
=======
import java.util.regex.Pattern;
>>>>>>> private-codex/batch1

@Service
public class BusinessAuditLogQueryServiceImpl implements IBusinessAuditLogQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?i)(?<![0-9A-Z])\\d{17}[0-9X](?![0-9A-Z])");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{15,19}(?!\\d)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(bearer\\s+|(?:token|password|secret|authorization)\\s*[:=]\\s*)[^\\s,;]+" );

    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    @Override
    public BusinessAuditLogPageVO query(BusinessAuditLogQueryDTO query, boolean platformAdmin) {
        if (!platformAdmin) {
            throw new ServiceException("无权查看平台操作审计", HttpStatus.FORBIDDEN);
        }
        BusinessAuditLogQueryDTO actual = query == null ? new BusinessAuditLogQueryDTO() : query;
        int limit = actual.getLimit() == null ? DEFAULT_LIMIT : actual.getLimit();
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new ServiceException("分页大小必须在 1 到 100 之间", HttpStatus.BAD_REQUEST);
        }
        actual.setLimit(limit);
        actual.setFetchLimit(limit + 1);
        List<BusinessAuditEvent> fetched = businessAuditEventMapper.selectAuditLogPage(actual);
        if (fetched == null) fetched = Collections.emptyList();

        BusinessAuditLogPageVO page = new BusinessAuditLogPageVO();
        boolean hasMore = fetched.size() > limit;
        List<BusinessAuditEvent> source = hasMore ? fetched.subList(0, limit) : fetched;
        List<BusinessAuditEvent> items = new ArrayList<>();
        for (BusinessAuditEvent event : source) items.add(safeProjection(event));
        page.setItems(items);
        page.setNextCursor(hasMore && !items.isEmpty() ? items.get(items.size() - 1).getId() : null);
        return page;
    }

    private BusinessAuditEvent safeProjection(BusinessAuditEvent source) {
        BusinessAuditEvent safe = new BusinessAuditEvent();
        safe.setId(source.getId());
        safe.setDomainType(source.getDomainType());
        safe.setDomainId(source.getDomainId());
        safe.setOwnerMemberId(source.getOwnerMemberId());
        safe.setActorType(source.getActorType());
        safe.setActorMemberId(source.getActorMemberId());
        safe.setActorRoleCode(source.getActorRoleCode());
        safe.setAction(source.getAction());
        safe.setTargetType(source.getTargetType());
        safe.setTargetId(source.getTargetId());
        safe.setRelationId(source.getRelationId());
        safe.setBeforeJson(redactJson(source.getBeforeJson()));
        safe.setAfterJson(redactJson(source.getAfterJson()));
        safe.setReason(redactText(source.getReason()));
        safe.setRequestId(source.getRequestId());
        safe.setCreateTime(source.getCreateTime());
        return safe;
    }

    private String redactText(String value) {
        if (value == null) return null;
        String safe = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1***");
        safe = PHONE.matcher(safe).replaceAll("***");
        safe = ID_CARD.matcher(safe).replaceAll("***");
        return BANK_CARD.matcher(safe).replaceAll("***");
    }

    private String redactJson(String value) {
        if (value == null) return null;
        try {
            JsonNode root = JSON.readTree(value);
            redactNode(root);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    private void redactNode(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) object.put(field.getKey(), "***");
                else if (field.getValue().isTextual()) object.put(field.getKey(), redactText(field.getValue().asText()));
                else redactNode(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) redactNode(child);
        }
    }

    private String redactText(String value) {
        if (value == null) return null;
        return value.replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "***")
                .replaceAll("(?<![0-9])[0-9]{15,19}(?![0-9])", "***")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "***");
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("token") || normalized.contains("password")
                || normalized.contains("phone") || normalized.contains("mobile")
                || normalized.contains("openid") || normalized.contains("secret")
                || normalized.contains("credential") || normalized.contains("authorization")
                || normalized.contains("unionid") || normalized.contains("idcard")
                || normalized.contains("identity") || normalized.contains("realname")
                || normalized.contains("bank") || normalized.contains("cardnumber") || normalized.contains("cardno")
                || normalized.contains("address") || normalized.contains("email")
                || normalized.contains("latitude") || normalized.contains("longitude")
                || normalized.contains("location") || normalized.contains("nickname")
                || "name".equals(normalized) || "lat".equals(normalized) || "lng".equals(normalized);
    }
}

package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.mapper.AuthorWriteReceiptMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 回执和原业务写入共用事务；只有同一操作人、同一原号、同一输入可以重放。 */
@Service
public class AuthorWriteReceiptService {
    private static final Set<String> OPERATIONS = Set.of("ACTIVITY_PUBLISH", "ACTIVITY_UPDATE",
            "TEMPLATE_PUBLISH", "TEMPLATE_DRAFT", "NPC_CHAT_DELETE");
    private final AuthorWriteReceiptMapper mapper;
    private final TransactionTemplate transaction;

    public AuthorWriteReceiptService(AuthorWriteReceiptMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    public AjaxResult execute(Long actor, String operation, String requestId, Object input, Supplier<AjaxResult> write) {
        validate(actor, operation, requestId);
        String hash = hash(JSON.toJSONString(canonical(JSON.toJSON(input))));
        return transaction.execute(status -> {
            boolean inserted = false;
            try { mapper.insert(actor, operation, requestId, hash); inserted = true; }
            catch (DuplicateKeyException duplicate) { /* 原事务提交前，这里由唯一键等待。 */ }
            Map<String, Object> row = mapper.lock(actor, operation, requestId);
            if (row == null || !hash.equals(row.get("requestHash"))) throw new ServiceException("原请求号对应的内容不一致", 409);
            if (!inserted) {
                if (row.get("resultJson") == null) throw new ServiceException("原请求结果待确认", 409);
                return decode(row.get("resultJson").toString());
            }
            AjaxResult result = write.get();
            if (result == null) throw new IllegalStateException("写入结果缺失");
            boolean completed = Integer.valueOf(200).equals(result.get("code"));
            result.put("writeReceipt", Map.of("operation", operation, "requestId", requestId,
                    "state", completed ? "COMPLETED" : "REJECTED"));
            if (!completed) { status.setRollbackOnly(); return result; }
            if (mapper.complete(actor, operation, requestId, JSON.toJSONString(result)) != 1)
                throw new IllegalStateException("原请求回执未保存");
            return result;
        });
    }

    public Map<String, Object> receipt(Long actor, String operation, String requestId) {
        validate(actor, operation, requestId);
        Map<String, Object> row = mapper.read(actor, operation, requestId);
        if (row == null || row.get("resultJson") == null)
            return Map.of("state", "UNKNOWN", "operation", operation, "requestId", requestId);
        return Map.of("state", "FOUND", "operation", operation, "requestId", requestId,
                "response", decode(row.get("resultJson").toString()));
    }

    private static void validate(Long actor, String operation, String requestId) {
        if (actor == null || actor <= 0) throw new ServiceException("请先登录", 401);
        if (!OPERATIONS.contains(operation == null ? "" : operation) || requestId == null
                || !requestId.matches("[A-Za-z0-9._:-]{8,64}")) throw new ServiceException("原请求参数无效", 400);
    }
    private static AjaxResult decode(String raw) {
        Map<String, Object> fields = JSON.parseObject(raw);
        if (fields == null || !Integer.valueOf(200).equals(fields.get("code")))
            throw new ServiceException("原请求回执不完整", 409);
        AjaxResult result = new AjaxResult(); result.putAll(fields); return result;
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(key.toString(), canonical(item))); return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AuthorWriteReceiptService::canonical).toList();
        return value;
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

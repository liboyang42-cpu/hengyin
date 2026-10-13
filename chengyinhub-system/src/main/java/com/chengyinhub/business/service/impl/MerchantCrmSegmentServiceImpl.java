package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.MerchantCrmSegment;
import com.chengyinhub.business.domain.dto.MerchantCrmSegmentFilter;
import com.chengyinhub.business.domain.dto.MerchantCrmSegmentRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentVO;
import com.chengyinhub.business.mapper.MerchantCrmSegmentMapper;
import com.chengyinhub.business.service.IMerchantCrmSegmentService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class MerchantCrmSegmentServiceImpl implements IMerchantCrmSegmentService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{6,64}");
    private static final List<String> SEGMENTS = Arrays.asList("repeat", "dormant", "pending", "abnormal", "new", "noted");

    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private MerchantCrmSegmentMapper merchantCrmSegmentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantCrmSegmentVO save(Long principalMemberId, MerchantCrmSegmentRequest request) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_SEGMENT);
        String name = text(request == null ? null : request.getName(), 30, "请填写分群名称");
        String requestId = requestId(request == null ? null : request.getRequestId());
        MerchantCrmSegmentFilter filter = normalize(request == null ? null : request.getFilter());
        String filterJson = json(filter);
        MerchantCrmSegment replay = merchantCrmSegmentMapper.selectByRequestId(access.getMerchantId(), requestId);
        if (replay != null) return exactReplay(replay, principalMemberId, name, filterJson);
        MerchantCrmSegment row = new MerchantCrmSegment();
        row.setMerchantId(access.getMerchantId());
        row.setActorMemberId(principalMemberId);
        row.setName(name);
        row.setFilterJson(filterJson);
        row.setRequestId(requestId);
        try {
            if (merchantCrmSegmentMapper.insertSegment(row) != 1) throw new ServiceException("保存分群失败", 409);
        } catch (DuplicateKeyException e) {
            MerchantCrmSegment existing = merchantCrmSegmentMapper.selectByRequestId(
                    access.getMerchantId(), requestId);
            if (existing == null) throw new ServiceException("分群名称已存在", 409);
            return exactReplay(existing, principalMemberId, name, filterJson);
        }
        return toVO(row);
    }

    @Override
    public List<MerchantCrmSegmentVO> list(Long principalMemberId) {
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, MerchantPermission.CRM_READ);
        List<MerchantCrmSegment> rows = merchantCrmSegmentMapper.selectActiveSegments(access.getMerchantId(), 50);
        List<MerchantCrmSegmentVO> out = new ArrayList<>();
        if (rows != null) for (MerchantCrmSegment row : rows) out.add(toVO(row));
        return out;
    }

    private MerchantCrmSegmentVO exactReplay(MerchantCrmSegment row, Long actor, String name, String filterJson) {
        if (!actor.equals(row.getActorMemberId()) || !name.equals(row.getName())
                || !jsonEquivalent(filterJson, row.getFilterJson())) {
            throw new ServiceException("请求标识已用于其他分群", 409);
        }
        return toVO(row);
    }

    private MerchantCrmSegmentFilter normalize(MerchantCrmSegmentFilter input) {
        MerchantCrmSegmentFilter out = new MerchantCrmSegmentFilter();
        String segment = input == null || input.getSegment() == null ? "" : input.getSegment().trim();
        if ("refunded".equals(segment)) segment = "abnormal";
        if (segment.isEmpty() || "all".equals(segment)) segment = null;
        if (segment != null && !SEGMENTS.contains(segment)) throw new ServiceException("分群条件无效", 400);
        out.setSegment(segment);
        Long tagId = input == null ? null : input.getTagId();
        if (tagId != null && tagId <= 0) throw new ServiceException("标签条件无效", 400);
        out.setTagId(tagId);
        Integer sourceType = input == null ? null : input.getSourceType();
        if (sourceType != null && sourceType != 1 && sourceType != 2) throw new ServiceException("来源条件无效", 400);
        out.setSourceType(sourceType);
        LocalDate start = date(input == null ? null : input.getSourceStart(), "来源开始日期无效");
        LocalDate end = date(input == null ? null : input.getSourceEnd(), "来源结束日期无效");
        if (start != null && end != null && (end.isBefore(start) || start.plusDays(366).isBefore(end))) {
            throw new ServiceException("来源时间窗无效", 400);
        }
        out.setSourceStart(start == null ? null : start.toString());
        out.setSourceEnd(end == null ? null : end.toString());
        return out;
    }

    private MerchantCrmSegmentVO toVO(MerchantCrmSegment row) {
        MerchantCrmSegmentVO out = new MerchantCrmSegmentVO();
        out.setId(row.getId());
        out.setName(row.getName());
        out.setFilter(readFilter(row.getFilterJson()));
        out.setCreateTime(row.getCreateTime());
        return out;
    }

    private String json(MerchantCrmSegmentFilter value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new ServiceException("分群条件序列化失败", 500); }
    }

    private MerchantCrmSegmentFilter readFilter(String value) {
        try { return JSON.readValue(value, MerchantCrmSegmentFilter.class); }
        catch (Exception e) { throw new ServiceException("分群条件损坏", 500); }
    }

    private boolean jsonEquivalent(String left, String right) {
        try { return JSON.readTree(left).equals(JSON.readTree(right)); }
        catch (Exception e) { return false; }
    }

    private LocalDate date(String value, String message) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) { throw new ServiceException(message, 400); }
    }

    private String text(String value, int max, String message) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty() || out.length() > max) throw new ServiceException(message, 400);
        return out;
    }

    private String requestId(String value) {
        String out = text(value, 64, "请求标识无效");
        if (!REQUEST_ID.matcher(out).matches()) throw new ServiceException("请求标识无效", 400);
        return out;
    }
}

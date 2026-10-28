package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MyAppealRow;
import com.chengyinhub.business.mapper.MemberAccountMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档二 L8:玩家「我的申诉」跨局列表(C1,先只放计分申诉与成绩争议)与账号信誉(X7,只有正常 / 受限)。
 * 只读本人数据;不返回分数、门槛或封禁原因。
 */
@Service
public class MemberAccountService {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLOSED = "closed";
    public static final String REPUTATION_NORMAL = "normal";
    public static final String REPUTATION_RESTRICTED = "restricted";

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final MemberAccountMapper mapper;

    public MemberAccountService(MemberAccountMapper mapper) {
        this.mapper = mapper;
    }

    /** status:null 全部 / open 处理中 / closed 已有结论;其它值拒。 */
    public Map<String, Object> myAppeals(Long memberId, String status, Integer pageNum, Integer pageSize) {
        Boolean open;
        if (status == null || status.trim().isEmpty()) open = null;
        else if (STATUS_OPEN.equals(status)) open = Boolean.TRUE;
        else if (STATUS_CLOSED.equals(status)) open = Boolean.FALSE;
        else throw new ServiceException("参数不合规");
        int size = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, pageSize);
        int page = pageNum == null || pageNum <= 0 ? 1 : pageNum;
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new ServiceException("参数不合规");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<MyAppealRow> found = mapper.selectMyAppeals(memberId, open, (int) offset, size);
        if (found != null) for (MyAppealRow row : found) rows.add(view(row));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("rows", rows);
        result.put("total", Integer.valueOf(mapper.countMyAppeals(memberId, open)));
        return result;
    }

    public Map<String, Object> reputation(Long memberId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", mapper.countEffectiveBans(memberId, new Date()) > 0 ? REPUTATION_RESTRICTED : REPUTATION_NORMAL);
        return result;
    }

    static String status(Integer status) {
        if (status == null || status.intValue() == 0) return STATUS_OPEN;
        return status.intValue() == 1 ? "accepted" : "rejected";
    }

    private static Map<String, Object> view(MyAppealRow row) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("kind", row.getKind());
        view.put("id", row.getId());
        view.put("status", status(row.getStatus()));
        view.put("sessionId", row.getSessionId());
        view.put("topicId", row.getTopicId());
        view.put("topicName", row.getTopicName());
        view.put("nodeId", row.getNodeId());
        view.put("createTime", row.getCreateTime());
        view.put("resolutionNote", row.getResolutionNote());
        return view;
    }
}

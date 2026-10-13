package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** 发现事实持锁提交；可选掉落沿用调用方原有的独立失败处理。 */
@Service
public class RoamSessionWriteService {
    @Autowired
    private RoamMapper roamMapper;

    @Transactional(rollbackFor = Exception.class)
    public int recordPoiFound(Long memberId, Long sessionId, Long poiId) {
        RoamSession session = roamMapper.selectOwnedSessionForUpdate(sessionId, memberId);
        if (session == null || !memberId.equals(session.getMemberId())) throw new ServiceException("会话不存在");
        if (!Integer.valueOf(0).equals(session.getStatus())) throw new ServiceException("本次漫游已结算");
        return roamMapper.insertPoiFoundIgnore(memberId, poiId, sessionId, new Date());
    }
}

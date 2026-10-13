package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.dto.ClubFulfillmentWorkspaceDTO;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.service.IClubFulfillmentWorkspaceService;
import com.chengyinhub.business.service.IClubService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 以生效俱乐部合作单为准，批量聚合带队、签到与团核销证据。 */
@Service
public class ClubFulfillmentWorkspaceServiceImpl implements IClubFulfillmentWorkspaceService {

    private final IClubService clubService;
    private final ClubLeadMapper clubLeadMapper;
    private final CoopGroupVerifyMapper groupVerifyMapper;

    public ClubFulfillmentWorkspaceServiceImpl(IClubService clubService, ClubLeadMapper clubLeadMapper,
                                               CoopGroupVerifyMapper groupVerifyMapper) {
        this.clubService = clubService;
        this.clubLeadMapper = clubLeadMapper;
        this.groupVerifyMapper = groupVerifyMapper;
    }

    @Override
    public ClubFulfillmentWorkspaceDTO getFulfillment(Long clubId) {
        Club club = clubService.selectClubById(clubId);
        if (club == null) {
            return null;
        }
        ClubFulfillmentWorkspaceDTO workspace = new ClubFulfillmentWorkspaceDTO();
        workspace.setClubId(clubId);
        List<ClubFulfillmentWorkspaceDTO.Session> sessions = clubLeadMapper.selectFulfillmentSessionsByClub(clubId);
        List<ClubFulfillmentWorkspaceDTO.Attendance> attendances = clubLeadMapper.selectFulfillmentAttendanceByClub(clubId);
        Map<Long, List<ClubFulfillmentWorkspaceDTO.Attendance>> attendeesByActivity = new HashMap<>();
        for (ClubFulfillmentWorkspaceDTO.Attendance attendance : orEmpty(attendances)) {
            attendeesByActivity.computeIfAbsent(attendance.getActivityId(), key -> new ArrayList<>()).add(attendance);
        }
        for (ClubFulfillmentWorkspaceDTO.Session session : orEmpty(sessions)) {
            session.setAttendees(attendeesByActivity.getOrDefault(session.getActivityId(), Collections.emptyList()));
        }
        workspace.setSessions(orEmpty(sessions));
        workspace.setGroupVerifications(orEmpty(groupVerifyMapper.selectFulfillmentVerificationsByClub(clubId)));
        return workspace;
    }

    private <T> List<T> orEmpty(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}

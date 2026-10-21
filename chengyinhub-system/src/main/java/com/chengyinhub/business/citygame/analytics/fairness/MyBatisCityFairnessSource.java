package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads one approved scope. A draft row and a different scope are not this query. */
public final class MyBatisCityFairnessSource implements CityFairnessReader.Source {
    private final CityFairnessMapper mapper;
    private final int maxEvents;
    private final String boardId;
    private final String regionId;
    private final String seasonId;
    private final String rulesVersion;

    public MyBatisCityFairnessSource(CityFairnessMapper mapper, int maxEvents,
                                      String boardId, String regionId, String seasonId, String rulesVersion) {
        this.mapper = mapper;
        this.maxEvents = maxEvents;
        this.boardId = boardId;
        this.regionId = regionId;
        this.seasonId = seasonId;
        this.rulesVersion = rulesVersion;
    }

    public static CityFairnessReader.ParameterRow oneApproved(List<CityFairnessReader.ParameterRow> rows,
                                                               String boardId, String regionId, String seasonId,
                                                               String rulesVersion) {
        List<CityFairnessReader.ParameterRow> approved = new ArrayList<>();
        if (rows != null) {
            for (CityFairnessReader.ParameterRow row : rows) {
                if (row != null && "APPROVED".equals(row.state)
                        && Objects.equals(boardId, row.boardId) && Objects.equals(regionId, row.regionId)
                        && Objects.equals(seasonId, row.seasonId) && Objects.equals(rulesVersion, row.rulesVersion)) {
                    approved.add(row);
                }
            }
        }
        if (approved.isEmpty()) return null;
        if (approved.size() != 1) throw new CityFairnessReader.FairnessReadException("parameter-ambiguous");
        return approved.get(0);
    }

    public CityFairnessReader.ParameterRow parameters() {
        return oneApproved(mapper.selectParameters(boardId, regionId, seasonId, rulesVersion),
                boardId, regionId, seasonId, rulesVersion);
    }

    public List<CityFairnessReader.EventRow> events(long asOfInclusive) {
        if (maxEvents <= 0) throw new CityFairnessReader.FairnessReadException("maxEvents");
        List<CityFairnessReader.EventRow> rows = mapper.selectEvents(boardId, regionId, seasonId, rulesVersion,
                asOfInclusive, maxEvents + 1);
        if (rows == null) return List.of();
        if (rows.size() > maxEvents) throw new CityFairnessReader.FairnessReadException("event-bound");
        for (CityFairnessReader.EventRow row : rows) {
            if (row == null || row.actorId == null || row.actorId <= 0
                    || !boardId.equals(row.boardId) || !regionId.equals(row.regionId)
                    || !seasonId.equals(row.seasonId) || !rulesVersion.equals(row.rulesVersion)) {
                throw new CityFairnessReader.FairnessReadException("scope");
            }
        }
        return rows;
    }

    public List<String> unavailableStreams() {
        return List.of();
    }
}

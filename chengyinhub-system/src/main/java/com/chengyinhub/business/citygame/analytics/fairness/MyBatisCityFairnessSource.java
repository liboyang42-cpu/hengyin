package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;

import java.util.ArrayList;
import java.util.List;

/** Reads candidate tables for one account. A draft row is not an approved parameter. */
public final class MyBatisCityFairnessSource implements CityFairnessReader.Source {
    private final CityFairnessMapper mapper;
    private final long actorId;
    private final int maxEvents;

    public MyBatisCityFairnessSource(CityFairnessMapper mapper, long actorId, int maxEvents) {
        this.mapper = mapper;
        this.actorId = actorId;
        this.maxEvents = maxEvents;
    }

    public static CityFairnessReader.ParameterRow oneApproved(List<CityFairnessReader.ParameterRow> rows) {
        List<CityFairnessReader.ParameterRow> approved = new ArrayList<>();
        if (rows != null) {
            for (CityFairnessReader.ParameterRow row : rows) {
                if (row != null && "APPROVED".equals(row.state)) approved.add(row);
            }
        }
        if (approved.isEmpty()) return null;
        if (approved.size() != 1) throw new CityFairnessReader.FairnessReadException("parameter-ambiguous");
        return approved.get(0);
    }

    public CityFairnessReader.ParameterRow parameters() {
        return oneApproved(mapper.selectParameters());
    }

    public List<CityFairnessReader.EventRow> events(long windowStartExclusive, long asOfInclusive) {
        if (maxEvents <= 0) throw new CityFairnessReader.FairnessReadException("maxEvents");
        List<CityFairnessReader.EventRow> rows = mapper.selectEvents(actorId, windowStartExclusive, asOfInclusive, maxEvents + 1);
        if (rows == null) return List.of();
        if (rows.size() > maxEvents) throw new CityFairnessReader.FairnessReadException("event-bound");
        for (CityFairnessReader.EventRow row : rows) {
            if (row == null || row.actorId == null || row.actorId != actorId) {
                throw new CityFairnessReader.FairnessReadException("scope");
            }
        }
        return rows;
    }

    public List<String> unavailableStreams() {
        return List.of();
    }
}

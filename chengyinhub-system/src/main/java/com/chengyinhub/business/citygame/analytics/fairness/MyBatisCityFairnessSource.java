package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;

import java.util.List;

/** Reads candidate tables. A failed or ambiguous read is not an empty success. */
public final class MyBatisCityFairnessSource implements CityFairnessReader.Source {
    private final CityFairnessMapper mapper;

    public MyBatisCityFairnessSource(CityFairnessMapper mapper) {
        this.mapper = mapper;
    }

    public CityFairnessReader.ParameterRow parameters() {
        List<CityFairnessReader.ParameterRow> rows = mapper.selectParameters();
        if (rows == null || rows.isEmpty()) return null;
        if (rows.size() != 1) throw new CityFairnessReader.FairnessReadException("parameter-ambiguous");
        return rows.get(0);
    }

    public List<CityFairnessReader.EventRow> events(long windowStartExclusive, long asOfInclusive) {
        List<CityFairnessReader.EventRow> rows = mapper.selectEvents(windowStartExclusive, asOfInclusive);
        return rows == null ? List.of() : rows;
    }

    public List<String> unavailableStreams() {
        return List.of();
    }
}

package com.chengyinhub.business.citygame.analytics.fairness;

import java.util.List;

/** No approved parameters and no event producer. Calling read reports the missing items. */
public final class UnconnectedFairnessSource implements CityFairnessReader.Source {
    public CityFairnessReader.ParameterRow parameters() { return null; }

    public List<CityFairnessReader.EventRow> events(long windowStartExclusive, long asOfInclusive) {
        return List.of();
    }

    public List<String> unavailableStreams() { return FairnessFactContract.STREAMS; }
}

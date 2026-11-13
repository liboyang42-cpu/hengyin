package com.chengyinhub.business.citygame;

import java.util.Map;

/** Public view of a stored r491 command outcome for the web layer. */
public final class CityR491Outcomes {
    private CityR491Outcomes() {}

    public static final class View {
        public final int status;
        public final String errorCode;
        public final Map<String, Object> data;

        View(int status, String errorCode, Map<String, Object> data) {
            this.status = status;
            this.errorCode = errorCode;
            this.data = data;
        }
    }

    public static View view(Object outcome) {
        CityR491Store.Outcome o = (CityR491Store.Outcome) outcome;
        return new View(o.status, o.errorCode, o.data);
    }
}

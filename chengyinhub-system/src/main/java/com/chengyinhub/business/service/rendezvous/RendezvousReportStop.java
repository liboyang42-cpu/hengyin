package com.chengyinhub.business.service.rendezvous;

import java.time.Clock;
import java.util.Objects;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.stopBlockedPair;

/** Durable report receipt plus pair stop, in the consent guard transaction.
 * Does not call ImServiceImpl.report and does not open an audit task.
 * Explicitly constructed. Not a Spring bean. */
public final class RendezvousReportStop {
    private final MyBatisRendezvousConsentStore store;
    private final RendezvousProtocolMapper reports;
    private final Clock clock;
    public RendezvousReportStop(MyBatisRendezvousConsentStore.Mode mode, SqlSessionTemplate session,
                                DataSourceTransactionManager manager, Clock clock) {
        store = new MyBatisRendezvousConsentStore(mode, session, manager);
        reports = session.getMapper(RendezvousProtocolMapper.class);
        this.clock = Objects.requireNonNull(clock);
    }
    public void report(long reporter, long reported) {
        if (reporter <= 0 || reported <= 0 || reporter == reported) {
            throw new RendezvousConsentService.Rejection("INVALID_INPUT");
        }
        store.transaction(tx -> {
            if (reports.reportExists(reporter, reported) == null) reports.insertReport(reporter, reported, clock.millis());
            stopBlockedPair(tx, reporter, reported);
            return null;
        });
    }
}

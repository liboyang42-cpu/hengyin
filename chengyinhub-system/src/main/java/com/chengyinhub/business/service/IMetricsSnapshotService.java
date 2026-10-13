package com.chengyinhub.business.service;

import java.util.Date;
import java.util.List;
import com.chengyinhub.business.domain.MetricsSnapshot;

public interface IMetricsSnapshotService {
    int upsertSnapshots(List<MetricsSnapshot> snapshots);

    Date selectLatestSuccessfulSampledAt();
}

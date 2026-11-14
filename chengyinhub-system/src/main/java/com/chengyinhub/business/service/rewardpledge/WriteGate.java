package com.chengyinhub.business.service.rewardpledge;

/** Test hook that runs immediately before one transactional write. Production passes none. */
public interface WriteGate {
    void before(String point);
}

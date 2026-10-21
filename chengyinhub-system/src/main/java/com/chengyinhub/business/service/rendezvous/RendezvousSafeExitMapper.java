package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;

/** Exit ledger statements. Not a scanned mapper bean and not an authorization API. */
public interface RendezvousSafeExitMapper {
    Integer lockGuard();
    RendezvousSafeExitService.ExitRow find(@Param("ticketId") String ticketId);
    int insert(RendezvousSafeExitService.ExitRow row);
    int markLaunch(RendezvousSafeExitService.ExitRow row);
}

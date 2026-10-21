package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.Actor;

/** Identity is derived from the server's current authenticated request, never a caller-supplied member ID. */
public interface PrivateHomeAccess {
    Actor current();
    void revalidate(Actor actor);
}

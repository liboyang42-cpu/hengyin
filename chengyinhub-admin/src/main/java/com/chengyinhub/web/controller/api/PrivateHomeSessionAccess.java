package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.home.PrivateHomeAccess;
import com.chengyinhub.business.home.PrivateHomeContracts.Actor;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.ServletUtils;
import com.chengyinhub.framework.web.service.AppTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Reuses existing authenticated app identity and session validation; does not create new auth/RBAC rules. */
@Component
public class PrivateHomeSessionAccess implements PrivateHomeAccess {
    @Autowired private AppTokenService tokens;
    @Override public Actor current() {
        LoginAppUser user = SecurityUtils.getLoginAppUser();
        if (user == null || user.getUserId() == null || user.getSessionEpoch() == null) deny();
        Actor actor = new Actor(user.getUserId(),user.getSessionEpoch());
        revalidate(actor); return actor;
    }
    @Override public void revalidate(Actor actor) {
        LoginAppUser principal = SecurityUtils.getLoginAppUser();
        LoginAppUser live = tokens.getLoginAppUser(ServletUtils.getRequest());
        if (live == null || principal == null || !Objects.equals(live.getUserId(),actor.ownerId)
                || !Objects.equals(live.getSessionEpoch(),actor.epoch)
                || !Objects.equals(principal.getUserId(),live.getUserId())
                || !Objects.equals(principal.getToken(),live.getToken()) || !tokens.verifyToken(live)) deny();
    }
    private static void deny() { throw new ServiceException("HOME_UNAUTHORIZED"); }
}

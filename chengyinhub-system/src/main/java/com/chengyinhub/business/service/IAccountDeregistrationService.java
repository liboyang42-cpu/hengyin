package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;

import java.util.Date;

public interface IAccountDeregistrationService {
    DeregistrationStatusVO precheck(Long memberId);

    DeregistrationStatusVO apply(Long memberId);

    DeregistrationStatusVO cancel(Long memberId);

    DeregistrationStatusVO status(Long memberId);

    int executeDue(Date now);
}

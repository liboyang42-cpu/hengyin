package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.CreatorPayoutRequest;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 创作者打款请求：按（来源类型, 来源 id, 创作者）幂等建单，再交给选中的服务商。
 * 开关关闭或服务商为 none 时拒绝，不建单。份额来源仍是内部账本，本服务只负责「怎么打出去」。
 */
@Service
public class CreatorPayoutService {
    private static final Logger log = LoggerFactory.getLogger(CreatorPayoutService.class);
    private final EcomPayoutMapper mapper;
    private final PayoutSwitches switches;
    private final List<CreatorPayoutProvider> providers;

    public CreatorPayoutService(EcomPayoutMapper mapper, PayoutSwitches switches, List<CreatorPayoutProvider> providers) {
        this.mapper = mapper;
        this.switches = switches;
        this.providers = providers;
    }

    CreatorPayoutProvider provider() {
        if (!switches.creatorEnabled()) throw new ServiceException("创作者打款未开启", 409);
        for (CreatorPayoutProvider p : providers) if (p.code().equals(switches.creatorProvider())) return p;
        throw new ServiceException("创作者打款服务商未配置", 409);
    }

    public CreatorPayoutRequest request(long creatorMemberId, String sourceType, long sourceId, long amountFen) {
        if (creatorMemberId <= 0 || sourceId <= 0 || amountFen <= 0 || sourceType == null || !sourceType.matches("[A-Z_]{1,32}"))
            throw new ServiceException("打款请求无效", 400);
        CreatorPayoutProvider provider = provider();
        CreatorPayoutRequest row = mapper.selectCreatorPayout(sourceType, sourceId, creatorMemberId);
        if (row == null) {
            row = new CreatorPayoutRequest();
            row.setOutPayoutNo("CYCP" + sourceType.charAt(0) + sourceId + "M" + creatorMemberId);
            row.setCreatorMemberId(creatorMemberId);
            row.setSourceType(sourceType);
            row.setSourceId(sourceId);
            row.setAmountFen(amountFen);
            row.setProvider(provider.code());
            try {
                mapper.insertCreatorPayout(row);
            } catch (DuplicateKeyException concurrent) {
                // 以已存在的为准
            }
            row = mapper.selectCreatorPayout(sourceType, sourceId, creatorMemberId);
        }
        if (row.getAmountFen() != amountFen) throw new ServiceException("同一来源的打款金额不一致", 409);
        if (!"REQUESTED".equals(row.getState())) return row;
        try {
            CreatorPayoutProvider.Result r = provider.submit(new CreatorPayoutProvider.Request(row.getOutPayoutNo(),
                    creatorMemberId, amountFen, sourceType, sourceId));
            if (r != null && ("SUBMITTED".equals(r.state()) || "SUCCEEDED".equals(r.state()) || "FAILED".equals(r.state())))
                mapper.updateCreatorPayoutState(row.getId(), "REQUESTED", r.state(), r.providerRef(), r.failReason());
        } catch (RuntimeException uncertain) {
            // 结果未知：保持 REQUESTED，服务商按 outPayoutNo 幂等，重试或查询即可。
            log.warn("creator payout submit uncertain id={} type={}", row.getId(), uncertain.getClass().getSimpleName());
        }
        return mapper.selectCreatorPayout(sourceType, sourceId, creatorMemberId);
    }
}

package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.business.service.IPointsAwardService;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 资金和资产执行适配。注入现有积分发放服务，但本包不调用它。
 * 策略关闭，或没有已批准的资产指令时，都返回不可执行。
 */
@Component
public class CityGameOperationsAssetExecution {

    private final IPointsAwardService awards;
    private final CityGameOperationsExecutionPolicy policy;
    private final AtomicInteger executions = new AtomicInteger();

    public CityGameOperationsAssetExecution(IPointsAwardService awards, CityGameOperationsExecutionPolicy policy) {
        this.awards = awards;
        this.policy = policy;
    }

    public int executions() {
        return executions.get();
    }

    public Result execute(String kind) {
        executions.incrementAndGet();
        if (awards == null) {
            return Result.blocked("ASSET_EXECUTION_DISABLED");
        }
        if (!policy.assetExecutionEnabled()) {
            return Result.blocked("ASSET_EXECUTION_DISABLED");
        }
        return Result.blocked("ASSET_INSTRUCTION_NOT_ACCEPTED");
    }

    public static final class Result {
        public final String blocker;

        private Result(String blocker) {
            this.blocker = blocker;
        }

        static Result blocked(String blocker) {
            return new Result(blocker);
        }
    }
}

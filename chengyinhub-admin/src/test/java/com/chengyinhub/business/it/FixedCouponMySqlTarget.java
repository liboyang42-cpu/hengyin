package com.chengyinhub.business.it;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import java.util.Map;

/** Test-only target selection. Validation completes before any JDBC connection exists. */
final class FixedCouponMySqlTarget {
    static MysqlIntegrationTestConfig from(Map<String,String> values) {
        require("127.0.0.1".equals(values.get("CHENGYIN_TEST_MYSQL_HOST")));
        String port=values.get("CHENGYIN_TEST_MYSQL_PORT"), user=values.get("CHENGYIN_TEST_MYSQL_USER");
        require(port!=null && port.matches("[0-9]+") && user!=null && !user.trim().isEmpty()
                && values.containsKey("CHENGYIN_TEST_MYSQL_PASSWORD"));
        String base=values.get("CHENGYIN_TEST_MYSQL_DATABASE_BASE");
        String suffix=values.get("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX");
        require(suffix!=null && !suffix.isEmpty());
        boolean reviewedLocal=base!=null && base.startsWith("w12_isolated");
        String run=values.get("GITHUB_RUN_ID");
        boolean exactCi="true".equals(values.get("GITHUB_ACTIONS")) && run!=null
                && run.matches("[1-9][0-9]{0,19}") && (base==null || "chengyin".equals(base))
                && ("_gh"+run).equals(suffix);
        require(reviewedLocal || exactCi);
        MysqlIntegrationTestConfig result=MysqlIntegrationTestConfig.from(values,"fixedcoupon");
        if(exactCi)require(result.databaseName().equals("chengyin_fixedcoupon_gh"+run));
        return result;
    }
    private static void require(boolean condition) {
        if(!condition)throw new IllegalArgumentException("Fixed coupon IT requires an explicit isolated loopback target");
    }
    private FixedCouponMySqlTarget() {}
}

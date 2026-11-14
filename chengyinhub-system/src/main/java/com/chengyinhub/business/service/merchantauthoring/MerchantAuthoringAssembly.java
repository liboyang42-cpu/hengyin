package com.chengyinhub.business.service.merchantauthoring;

import java.util.function.Function;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.mapper.merchantauthoring.MerchantAuthoringMapper;

/** Wires the authoring transaction to the existing MyBatis session and transaction manager. */
@Configuration
public class MerchantAuthoringAssembly {
    @Bean
    public AuthoringTx merchantAuthoringTx(PlatformTransactionManager transactions, SqlSessionTemplate sessions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        return new AuthoringTx() {
            public <T> T call(Function<MerchantAuthoringMapper, T> work) {
                return template.execute(status -> work.apply(sessions.getMapper(MerchantAuthoringMapper.class)));
            }
        };
    }
}

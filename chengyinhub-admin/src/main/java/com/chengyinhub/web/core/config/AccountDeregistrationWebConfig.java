package com.chengyinhub.web.core.config;

import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.web.interceptor.AccountDeregistrationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AccountDeregistrationWebConfig implements WebMvcConfigurer {
    private final IAccountDeregistrationService deregistrationService;

    public AccountDeregistrationWebConfig(IAccountDeregistrationService deregistrationService) {
        this.deregistrationService = deregistrationService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccountDeregistrationInterceptor(deregistrationService))
                .addPathPatterns("/api/**");
    }
}

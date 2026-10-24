package com.chengyinhub.framework.config;

import java.io.IOException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Loads the shipped YAML without starting its external database/Redis profile. */
final class SupportedStackApplicationProperties
{
    static final String SOURCE_NAME = "supported-stack-application-yaml";

    private SupportedStackApplicationProperties()
    {
    }

    static void initialize(ConfigurableApplicationContext context)
    {
        try
        {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                    SOURCE_NAME, new ClassPathResource("application.yml")))
            {
                context.getEnvironment().getPropertySources().addLast(source);
            }
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("Cannot load the shipped application.yml", exception);
        }
    }
}

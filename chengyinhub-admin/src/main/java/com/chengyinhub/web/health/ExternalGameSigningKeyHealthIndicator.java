package com.chengyinhub.web.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.externalgame.ExternalGameSigner;

/**
 * Reports whether the Ed25519 signing key for external game packages and expiring download links is loaded.
 */
@Component("externalGameSigningKey")
public class ExternalGameSigningKeyHealthIndicator implements HealthIndicator
{
    private final ExternalGameSigner signer;
    private final boolean required;

    @Autowired
    public ExternalGameSigningKeyHealthIndicator(
            ExternalGameSigner signer,
            @Value("${chengyin.external-game.require-signing-key:false}") boolean required)
    {
        this.signer = signer;
        this.required = required;
    }

    @Override
    public Health health()
    {
        boolean configured = signer != null && signer.configured();
        Health.Builder builder = (!configured && required)
                ? Health.down().withDetail("reason", "SIGNING_KEY_MISSING")
                : Health.up();
        return builder
                .withDetail("configured", configured)
                .withDetail("required", required)
                .withDetail("failClosed", true)
                .withDetail("algorithm", ExternalGameSigner.ALGORITHM)
                .withDetail("keyId", signer == null ? "none" : signer.keyId())
                .build();
    }
}

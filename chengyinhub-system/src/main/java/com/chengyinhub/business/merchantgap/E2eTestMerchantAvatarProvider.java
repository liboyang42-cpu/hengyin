package com.chengyinhub.business.merchantgap;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TEST-only avatar provider: the "model" is the approved source image itself. Loaded only with profile
 * {@code e2e-test} AND {@code chengyin.e2e.test-providers=true}, like the other E2E test providers.
 */
@Component
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestMerchantAvatarProvider implements MerchantAvatarProvider {
    @Override
    public String id() { return "e2e-test-passthrough"; }

    @Override
    public List<String> styles() { return List.of("CARTOON", "REALISTIC"); }

    @Override
    public Result generate(long merchantId, String imageUrl, String style) {
        return new Result("SUCCEEDED", imageUrl, imageUrl, null);
    }
}

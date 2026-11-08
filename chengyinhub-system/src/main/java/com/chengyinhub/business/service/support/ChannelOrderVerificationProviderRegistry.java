package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.ChannelOrderVerificationProvider;
import com.chengyinhub.business.service.impl.NoopChannelOrderVerificationProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 {@code providerKey} 选择真实渠道实现；找不到时回落到 Noop（未启用）。
 * 回落是刻意 fail-closed，不允许把任意 channelKey 猜成某个真实实现。
 */
@Component
public class ChannelOrderVerificationProviderRegistry {

    private final Map<String, ChannelOrderVerificationProvider> providers = new HashMap<>();
    private final ChannelOrderVerificationProvider noop;

    public ChannelOrderVerificationProviderRegistry(List<ChannelOrderVerificationProvider> discovered) {
        ChannelOrderVerificationProvider fallback = null;
        if (discovered != null) {
            for (ChannelOrderVerificationProvider provider : discovered) {
                if (provider == null || provider.providerKey() == null) continue;
                if (NoopChannelOrderVerificationProvider.PROVIDER_KEY.equals(provider.providerKey())) {
                    fallback = provider;
                    continue;
                }
                providers.put(provider.providerKey(), provider);
            }
        }
        this.noop = fallback != null ? fallback : new NoopChannelOrderVerificationProvider();
    }

    public ChannelOrderVerificationProvider require(String providerKey) {
        if (providerKey == null) return noop;
        return providers.getOrDefault(providerKey, noop);
    }

    public boolean hasRealProvider(String providerKey) {
        return providerKey != null && providers.containsKey(providerKey);
    }
}

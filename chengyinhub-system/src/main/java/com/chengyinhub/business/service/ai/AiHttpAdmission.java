package com.chengyinhub.business.service.ai;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import com.chengyinhub.common.utils.http.HttpUtils;

/** Fixed startup origins only. No executor, waiting queue, dynamic registration, or credential state. */
public final class AiHttpAdmission
{
    private static final Semaphore GLOBAL = new Semaphore(4);
    private static final AiHttpAdmission CLOSED = new AiHttpAdmission(java.util.List.of());
    private final Map<String, Semaphore> origins;

    public AiHttpAdmission(Collection<String> endpoints)
    {
        Map<String, Semaphore> configured = new HashMap<>();
        for (String endpoint : endpoints)
        {
            try
            {
                configured.computeIfAbsent(origin(endpoint), ignored -> new Semaphore(2));
            }
            catch (AiProviderNotSentException invalid)
            {
                // Invalid startup configuration stays unregistered and can only reject locally.
            }
        }
        origins = Map.copyOf(configured);
    }

    static AiHttpAdmission closed() { return CLOSED; }

    public HttpUtils.AttemptLease acquire(String endpoint)
    {
        Semaphore provider = origins.get(origin(endpoint));
        if (provider == null || Thread.currentThread().isInterrupted() || !GLOBAL.tryAcquire())
        {
            throw new AiProviderNotSentException();
        }
        if (!provider.tryAcquire())
        {
            GLOBAL.release();
            throw new AiProviderNotSentException();
        }
        return new HttpUtils.AttemptLease(() -> {
            provider.release();
            GLOBAL.release();
        });
    }

    /** Canonical configured origin; paths/models/bean names cannot create more capacity. */
    static String origin(String endpoint)
    {
        try
        {
            URI uri = new URI(endpoint);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("https".equals(scheme) || "http".equals(scheme)) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
            {
                throw new AiProviderNotSentException();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) throw new AiProviderNotSentException();
            int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
            return scheme + "://" + host + ":" + port;
        }
        catch (Exception invalid)
        {
            throw new AiProviderNotSentException();
        }
    }
}

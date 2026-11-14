package com.chengyinhub.web.health;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.payout.CreatorPayoutProvider;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.WechatEcommercePayGateway;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;

/**
 * Exposes payout switch and provider readiness (creator payout, WeChat e-commerce pay gateway, cash pledge switch)
 * without leaking merchant IDs, keys, or certificate paths.
 */
@Component("payoutProvider")
public class PayoutProviderHealthIndicator implements HealthIndicator
{
    private final PayoutSwitches switches;
    private final List<CreatorPayoutProvider> creatorProviders;
    private final CashPayoutSwitch cashPayoutSwitch;
    private final WechatEcommercePayGateway ecommerceGateway;

    @Autowired
    public PayoutProviderHealthIndicator(
            PayoutSwitches switches,
            ObjectProvider<CreatorPayoutProvider> creatorProviders,
            ObjectProvider<CashPayoutSwitch> cashPayoutSwitch,
            ObjectProvider<WechatEcommercePayGateway> ecommerceGateway)
    {
        this(
                switches,
                creatorProviders.stream().toList(),
                cashPayoutSwitch.getIfAvailable(),
                ecommerceGateway.getIfAvailable());
    }

    PayoutProviderHealthIndicator(
            PayoutSwitches switches,
            List<CreatorPayoutProvider> creatorProviders,
            CashPayoutSwitch cashPayoutSwitch,
            WechatEcommercePayGateway ecommerceGateway)
    {
        this.switches = switches;
        this.creatorProviders = creatorProviders == null ? List.of() : List.copyOf(creatorProviders);
        this.cashPayoutSwitch = cashPayoutSwitch;
        this.ecommerceGateway = ecommerceGateway;
    }

    @Override
    public Health health()
    {
        boolean creatorEnabled = switches != null && switches.creatorEnabled();
        String creatorProvider = switches == null ? "none" : switches.creatorProvider();
        List<String> availableProviders = new ArrayList<>();
        boolean creatorResolved = false;
        for (CreatorPayoutProvider provider : creatorProviders)
        {
            if (provider == null || provider.code() == null) continue;
            availableProviders.add(provider.code());
            if (provider.code().equalsIgnoreCase(creatorProvider) && !"none".equalsIgnoreCase(provider.code()))
            {
                creatorResolved = true;
            }
        }

        boolean ecommerceEnabled = switches != null && switches.ecommerceEnabled();
        boolean ecommerceCredentialsConfigured = ecommerceGateway != null && ecommerceGateway.credentialsConfigured();
        boolean cashOpen = cashPayoutSwitch != null && cashPayoutSwitch.open();

        Health.Builder builder;
        if (creatorEnabled && !creatorResolved)
        {
            builder = Health.down().withDetail("reason", "CREATOR_PAYOUT_PROVIDER_MISSING");
        }
        else if (ecommerceEnabled && !ecommerceCredentialsConfigured)
        {
            builder = Health.down().withDetail("reason", "ECOMMERCE_CREDENTIAL_MISSING");
        }
        else
        {
            builder = Health.up();
        }

        return builder
                .withDetail("creatorEnabled", creatorEnabled)
                .withDetail("creatorProvider", creatorProvider)
                .withDetail("creatorProviderResolved", creatorResolved)
                .withDetail("availableCreatorProviders", availableProviders)
                .withDetail("ecommerceEnabled", ecommerceEnabled)
                .withDetail("sellerRoutingEnabled", switches != null && switches.sellerRoutingEnabled())
                .withDetail("profitSharingEnabled", switches != null && switches.profitSharingEnabled())
                .withDetail("combinedEnabled", switches != null && switches.combinedEnabled())
                .withDetail("ecommerceCredentialsConfigured", ecommerceCredentialsConfigured)
                .withDetail("cashPayoutOpen", cashOpen)
                .build();
    }
}

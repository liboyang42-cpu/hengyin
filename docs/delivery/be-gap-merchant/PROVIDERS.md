# feature/be-gap-merchant — model and avatar providers (R3)

No secret is in the repo. Every key below is read from configuration; secrets come from environment variables.

## Avatar (`/api/merchant/npc/avatar/{status,generate}`)

Interface: `com.chengyinhub.business.merchantgap.MerchantAvatarProvider` (`id()`, `styles()`, `generate(merchantId, imageUrl, style) → {status SUCCEEDED|PENDING|FAILED, modelUrl, thumbUrl, failReason}`). `MerchantNpcGapService` takes at most one bean; with none, `status.available=false` and `generate` answers 503 `AVATAR_PROVIDER_NOT_CONFIGURED`.

| Bean | Active when | Use |
|---|---|---|
| `E2eTestMerchantAvatarProvider` (kept) | profile `e2e-test` **and** `chengyin.e2e.test-providers=true` | TEST only: the approved image is returned as model and thumb |
| `HttpMerchantAvatarProvider` (new) | `chengyin.merchant.avatar.provider=http` | production: one JSON `POST {merchantId, imageUrl, style}` with `Authorization: Bearer <key>` to the configured HTTPS endpoint; answer `{status, modelUrl, thumbUrl, failReason}`; result URLs must be `https://` |

Production keys:

```properties
chengyin.merchant.avatar.provider=http
chengyin.merchant.avatar.http.base-url=https://<vendor endpoint>        # HTTPS only; anything else = PROVIDER_NOT_CONFIGURED
chengyin.merchant.avatar.http.api-key=${CHENGYIN_MERCHANT_AVATAR_API_KEY:}
chengyin.merchant.avatar.http.timeout-ms=15000                          # clamped to 1000..60000
chengyin.merchant.avatar.http.styles=CARTOON,REALISTIC                  # shown to the app as the choice list
```

Failure mapping (job stored as `FAILED`, never retried by the server): missing URL/key `PROVIDER_NOT_CONFIGURED`, transport error `PROVIDER_UNAVAILABLE`, non-2xx `PROVIDER_HTTP_<code>`, bad body or non-HTTPS URL `PROVIDER_MALFORMED`, vendor `FAILED` → its `failReason` if `[A-Z0-9_]{1,64}` else `PROVIDER_FAILED`. Do not enable both beans in one runtime (TEST never sets `provider=http`).

## Model (W05 authoring generation and the store-bound template fill)

Interface: `com.chengyinhub.business.service.ai.LlmClient`, picked per business type by `AiSceneRouter` through `AiGatewayService` (quota, admission, audit). W05 `POST /api/merchant/authoring/jobs` uses biz `theme_draft`; the source producer's store-bound `POST /api/ai/template/fill` (`merchantFacts` + `merchantConstraints`) uses `template_fill`. Both route to the `deepseek` OpenAI-compatible client configured by `ai.model.*`:

```yaml
ai:
  model:
    enabled: true
    baseUrl: https://api.deepseek.com          # OpenAI-compatible; /chat/completions appended
    model: deepseek-chat
    apiKey: ${CHENGYIN_AI_API_KEY:${CHENGYIN_DEEPSEEK_API_KEY:}}   # production env only
    timeoutMs: 8000
    dailyLimit: 20                              # per-user creation pool shared by these business types
chengyin:
  merchant:
    authoring:
      enabled: true                             # W05 policy bean (MerchantAuthoringPolicyConfiguration); off by default
      # + the chengyin.merchant.authoring.* policy values the owner approves (see RESULT.md §2)
```

Another vendor = another `LlmClient` bean (see `LlmClientConfig`, e.g. `ai.providers.minimax.*`) and a route change in `AiSceneRouter`; no code in the merchant services changes. TEST: no key is injected, so W05 generation answers `FAILED UPSTREAM_UNAVAILABLE` and the store-bound fill answers the gateway's "AI 繁忙" error. The TEST check of the source producer therefore records its candidate through the service with a fixed model result (unit test) and checks the confirm → draft-selection path on 8127 with a candidate row (see RESULT.md §R3).

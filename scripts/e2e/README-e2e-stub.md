# ★桩:支付可用性(环境依赖)

`E2eWechatPayStub.java.stub` **不是产品码,也不参与编译**(扩展名刻意不是 `.java`)。
它只在本地 e2e 跑链路时,被编译进【exploded jar】里用一次:

```bash
SP=<你的 scratchpad>            # 放 exploded jar 的目录
mkdir -p $SP/stub/com/chengyinhub/e2e
cp scripts/e2e/E2eWechatPayStub.java.stub $SP/stub/com/chengyinhub/e2e/E2eWechatPayStub.java
CP="$SP/app/BOOT-INF/classes:$(ls $SP/app/BOOT-INF/lib/*.jar | tr '\n' ':')"
javac -encoding UTF-8 -cp "$CP" -d "$SP/app/BOOT-INF/classes" \
      $SP/stub/com/chengyinhub/e2e/E2eWechatPayStub.java
```

**它桩掉的只有外部支付通道这一层**:商户凭据、SDK 构造、官方验签、预下单 ——
本机没有微信支付商户私钥,这一层无解(EXTERNAL_GATE)。

**它没有桩掉的**:「支付成功」仍走真实回调入口
`POST /api/registration/callback` → `ICmsRegistrationService.handleWechatPayCallback`,
所以金额冻结校验(`matchesFrozenWechatAmount`)、订单状态机、entitlement 一条都没绕过。
**绝不直接改 `payment_status`** —— 那等于把这一段验证做成假的。

⇒ 报告口径:**支付通道可用性本身没验**(生产已有真实成交为旁证);
**验了的是**报名单落库、entitlement 0→1、redemption 落行、等待事实、通关图鉴。
这两句话不是一回事,不要写成「玩家链路全通」。

---

# ★桩:故障注入(只用于注入③)

`E2eFaultInjectionFilter.java.stub` 同样**不是产品码、不参与编译**。编译进 exploded jar 后,
由系统属性打开:

```bash
java -De2e.fail.path=/api/play/checkin ... org.springframework.boot.loader.JarLauncher
```

不设该属性时整个过滤器透明。2026-08-18 实测:
- 打开后 `POST /api/play/checkin` → **HTTP 500** `{"code":500,"msg":"e2e 注入:打卡服务异常"}`;
- 同时 `GET /api/play/nodes` 仍 **200**(控制组,证明注入范围没有外溢);
- 撤掉属性重启后同一端点回到 **200**(负控)。

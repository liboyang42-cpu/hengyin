# VPN 协议与核心选型（2026-07-28）

## 结论

当前最合理的不是继续在原配置上轮换密钥或 MTU，而是把“可达性”和“协议实现”分开处理：

1. **不换 VPS/IP 的主方案：Cloudflare 代理域名 + VLESS gRPC + TLS，服务端使用 Xray-core；WebSocket/HTTPUpgrade 作兼容备用。** 客户端连接 Cloudflare Anycast，而不是直连 `45.63.127.24`；只要 Cloudflare 能回源到旧 VPS，就能绕开“万达云到旧 IP 的 TCP 443/8443 在 VPS 抓不到包”这条坏路径。Cloudflare 官方在所有计划支持代理 gRPC，要求 443、TLS、HTTP/2/ALPN。因此旧 IP 可以保留为隐藏源站 IP，但需要一个域名、Cloudflare 代理和有效 TLS 配置。[Cloudflare gRPC](https://developers.cloudflare.com/network/grpc-connections/)；[Cloudflare 网络端口](https://developers.cloudflare.com/fundamentals/reference/network-ports/)
2. **追求最高速度的主方案：新 IP/新 VPS + Xray VLESS Reality RAW TCP 443。** 在一条可达的直连 TCP 路径上，Reality + Vision 层次更少、没有 CDN 中转，通常会比 WebSocket/gRPC + CDN 更低延迟、更高吞吐。Xray 官方把 Reality 描述为普通 Web 流量外观，并说明与合适的 XTLS Vision 组合可获得显著性能收益。[Xray Reality](https://xtls.github.io/en/config/transports/reality.html)；[Xray 传输配置](https://xtls.github.io/en/config/transport.html)
3. **Hysteria2 仅作为旧 IP 的 UDP 实验路线，不先升为主力。** 它基于 QUIC，官方目标就是在不可靠、丢包网络上维持速度；理论上比 WireGuard 的裸 UDP 隧道更能通过拥塞控制和可靠 QUIC stream 适应丢包。但当前同一旧 IP 上 WireGuard 已经出现“大流量回程明显丢失”，所以 Hysteria2 必须先做持续大流量实测，不能因握手成功或延迟变绿就认定可用。[Hysteria 2 协议](https://v2.hysteria.network/docs/developers/Protocol/)；[Hysteria 2 官方首页](https://v2.hysteria.network/)
4. **TUIC、WireGuard 不作为当前主方案。** TUIC 同样主要依赖 QUIC/UDP，会继承当前 UDP 路径的不确定性；WireGuard 已被真实大流量负控证明不稳定。sing-box 是核心而不是协议，换核心本身既不会绕过旧 IP 的路径黑洞，也不会自动提升速度。

如果只选一个落地顺序：**先部署 Cloudflare + VLESS gRPC + TLS，让旧 VPS 恢复稳定可用，并保留 WS/HTTPUpgrade 兼容节点；同时准备一个不同 IP/地区的小规格 VPS，用 Xray Reality TCP 443 做速度主节点；旧 WireGuard 留作应急，不再承担默认出口。**

## 已知网络事实与推论边界

本次选型使用以下已经观测到的前提：

- 排查期间万达云必须保持开启，否则当前会话会断网。
- 万达云开启时，本机对旧 VPS `45.63.127.24` 发起 TCP 443/8443 连接，VPS 同步 `tcpdump` 为 **0 包**。
- 旧 VPS 的 WireGuard UDP 51820 可以握手，小请求偶尔成功，但持续下载会在约 0.3–0.4 MiB 后卡住；把 MTU 从 1280 降到 1200 没有改善。
- 当前客户端是 Clash Verge / Mihomo v1.19.29；Mihomo 官方配置文档明确支持 VLESS 的 WebSocket/gRPC/XHTTP 传输，也支持 Hysteria2、TUIC 和 WireGuard。[Mihomo 传输配置](https://wiki.metacubex.one/en/config/proxies/transport/)；[Mihomo Hysteria2](https://wiki.metacubex.one/en/config/proxies/hysteria2/)；[Mihomo TUIC](https://wiki.metacubex.one/en/config/proxies/tuic/)

由此可以确定：

- **任何“客户端直接 TCP 连接旧 IP”的方案当前都不可用。** 这包括 sing-box Reality、Xray Reality、Trojan TLS、VLESS RAW TLS 等；服务端换程序无法处理根本没有抵达的包。
- **Cloudflare 代理可以改变路径。** 官方说明 proxied DNS 记录返回 Cloudflare Anycast IP，HTTP/HTTPS 流量先进入 Cloudflare 网络，再到源站；因此客户端终点不再是旧 VPS IP。正式采用前仍需从 Cloudflare 回源侧读回 101/HTTP2 连接和源站日志，不能只看客户端延迟。[Cloudflare proxy status](https://developers.cloudflare.com/dns/proxy-status/)
- **UDP 到旧 IP 不是完全不可达，但质量很差。** QUIC 协议可能比 WireGuard 更能适应丢包，却不能修复持续性限速、黑洞或上游对 UDP 长流量的干预。

## 方案对比

| 方案 | 传输路径 | 预期速度 | 当前条件下的稳定性 | 旧 IP 能否保留 | 建议 |
|---|---|---:|---:|---|---|
| Xray VLESS Reality RAW TCP 443 | 客户端直连 VPS | 高 | **旧 IP：不可用**；新可达 IP：高 | 直连时不能 | 新 IP 上的速度主节点 |
| VLESS gRPC + TLS + Cloudflare | 客户端 → Cloudflare → 旧 VPS | 中到中高 | 当前最有希望；需正确 HTTP/2 调优 | **可以**，作为源站 | 旧 VPS 恢复可用的首选 |
| VLESS WS/HTTPUpgrade + TLS + Cloudflare | 客户端 → Cloudflare → 旧 VPS | 中 | 兼容性高，但长连接会被边缘重启/空闲超时 | 可以 | gRPC 的兼容备用 |
| Hysteria2 | QUIC/UDP 直连 VPS | 路径健康时高 | 当前 UDP 证据下为低到中，必须实测 | 可先测试旧 IP | 实验节点，不先做默认 |
| TUIC v5 | 主要为 QUIC/UDP 直连 VPS | 路径健康时高 | 与 Hysteria2 同样受当前 UDP 路径影响 | 可先测试旧 IP | 优先级低于 Hysteria2 |
| WireGuard | 固定 UDP 隧道 | 健康路径上很高 | 已实测不稳定 | 可以，但无意义 | 只留应急 |
| sing-box（核心） | 取决于所载协议 | 取决于协议 | 不能改变路径可达性 | 取决于协议 | 可承载 HY2/TUIC，但当前 Reality 不再继续押注 |

## 各方案分析

### 1. Xray VLESS Reality TCP 443

Reality 的优势是直连、层次少、不需要 CDN 和自己的公开证书。Xray 文档说明 Reality 会使用目标站的 TLS 外观；VLESS + RAW + Reality 与 XTLS Vision 是官方支持组合。[Xray Reality](https://xtls.github.io/en/config/transports/reality.html)；[Xray 传输兼容矩阵](https://xtls.github.io/en/config/transport.html)

但它对当前旧 IP 没有修复能力：抓包为 0 包意味着 Reality 握手甚至没有机会开始。**若要使用这条路线，需要更换为从万达云实际可达的新 IP，最好同时换地区/ASN，避免只换同网段后复现。**

服务端核心建议用 Xray-core，而不是继续让 sing-box 1.13.x 承担 Reality 入站。当前实测的 sing-box 服务端在本机回环测试也报 `REALITY: processed invalid connection`；sing-box 项目 issue #4023 记录了相同错误模式，并报告同一组凭据改用 Xray 客户端可工作。该 issue 已被关闭且标记为 `spam`，因此只能作为“高度相似的复现记录”，不能当成维护者正式确认的缺陷。[sing-box issue #4023](https://github.com/SagerNet/sing-box/issues/4023)

### 2. Cloudflare + VLESS gRPC + TLS

这是能在**不更换旧 VPS/IP**前提下明显改变客户端网络终点、同时兼顾并发性能的首选。Cloudflare 官方支持代理 gRPC，但要求源站监听 443、启用 TLS/HTTP2、通过 ALPN 宣告 HTTP/2，并在 Cloudflare zone 中打开 gRPC；未开启会返回 403。[Cloudflare gRPC](https://developers.cloudflare.com/network/grpc-connections/)

gRPC 自带 HTTP/2 多路复用，比普通 WebSocket 更适合并发小连接。不过 Xray 官方文档也给出两个稳定性注意点：

- 经 Cloudflare 时可把 `initial_windows_size` 设为不低于 65536，避免 CDN 发送意外的 HTTP/2 GOAWAY；
- gRPC 不能像普通 HTTP path 那样自然 fallback，存在主动探测风险，Xray 官方现已建议优先 XHTTP。[Xray gRPC](https://xtls.github.io/en/config/transports/grpc.html)

相对 Reality 直连，它增加了 TLS/HTTP2 封装和 CDN 中转，峰值速度通常不会更高，但**在直连旧 IP 完全不可达时，可用性比理论峰值更重要**。建议使用真实域名、不可猜的 service name、严格 TLS 验证，并在源站防火墙只允许 Cloudflare 官方 IP ranges 回源。[Cloudflare IP ranges](https://www.cloudflare.com/ips/)

### 3. Cloudflare + VLESS WebSocket/HTTPUpgrade + TLS

WebSocket 是兼容保底方案。Cloudflare 官方说明：

- 所有计划均支持代理 WebSocket，不需要专门启用额外协议；
- 443 和 8443 都属于其默认支持的 HTTPS 代理端口；
- WebSocket 是长连接，Cloudflare 发布边缘代码时可能重启服务器并终止连接，空闲连接也会超时，因此需要心跳和自动重连。[Cloudflare WebSockets](https://developers.cloudflare.com/network/websockets/)；[Cloudflare 网络端口](https://developers.cloudflare.com/fundamentals/reference/network-ports/)

WebSocket 配置面比 gRPC 小，但 HTTP/1.1 特征更明显，且单个长连接会受 Cloudflare 边缘重启影响。Xray 已建议优先考虑 XHTTP；Mihomo 当前文档也提供 XHTTP 配置。因此 WS/HTTPUpgrade 适合作为 gRPC 失败时的兼容节点，不应是唯一主节点；后续可在相同域名灰度 XHTTP。[Xray WebSocket](https://xtls.github.io/config/transports/websocket.html)；[Mihomo 传输配置](https://wiki.metacubex.one/en/config/proxies/transport/)

### 4. Hysteria2

Hysteria2 运行在 QUIC 之上，用 QUIC reliable stream 承载 TCP 代理流量，也支持 unreliable datagram 承载 UDP。官方协议还允许客户端和服务端交换带宽信息，并使用 BBR/Cubic 等拥塞控制；它明确以不可靠、丢包网络下的性能为目标。[Hysteria 2 协议](https://v2.hysteria.network/docs/developers/Protocol/)

这使它成为唯一值得在旧 IP 上再做一次 UDP 验证的协议：WireGuard 大流量失败不等于 QUIC 必然失败，因为 QUIC 有重传和拥塞控制。但当前风险依然很高：如果问题是万达云嵌套 VPN 对长时间 UDP 流量做限速或丢弃，而不是普通随机丢包，Hysteria2 也会断。端口跳跃和 Salamander 混淆可以改变端口/包外观，却不能创造不存在的回程带宽。[Hysteria 2 客户端完整配置](https://v2.hysteria.network/docs/advanced/Full-Client-Config/)

结论：**可以在旧 IP 的 UDP 443 上并行临时测试，不要求先买新 IP；只有 100 MiB 以上持续传输、重复多轮均通过后，才升级为备用或主节点。**

### 5. TUIC

TUIC v5 的目标包括 QUIC 0-RTT 代理、流多路复用、连接迁移以及 UDP 的可靠/不可靠两种承载。官方协议仓库也明确说明它没有官方实现，实际行为依赖各客户端/服务端实现。[TUIC 协议仓库](https://github.com/tuic-protocol/tuic)；[TUIC v5 规范](https://github.com/tuic-protocol/tuic/blob/dev/SPEC.md)

Mihomo 和 sing-box 都提供 TUIC v5 实现及 BBR/Cubic/New Reno 等配置，但它与 Hysteria2 共享“底层依赖 UDP/QUIC”的关键路径。当前没有证据表明它会比 Hysteria2 更适合这条已经出现 WireGuard 回程丢失的线路，且实现交叉兼容面更大，所以优先级排在 Hysteria2 后。[Mihomo TUIC](https://wiki.metacubex.one/en/config/proxies/tuic/)；[sing-box TUIC](https://sing-box.sagernet.org/configuration/inbound/tuic/)

### 6. WireGuard

WireGuard 协议所有数据包都使用 UDP。其简洁性和内核实现使它在健康链路上性能很好，官方旧基准也显示吞吐和延迟优势；但官方同时提醒这些基准较旧，不能拿来预测具体公网路径。[WireGuard 协议](https://www.wireguard.com/protocol/)；[WireGuard 性能](https://www.wireguard.com/performance/)

在当前环境中，WireGuard 已经通过真实持续下载证明失败，降低 MTU 也没有改善。这比任何通用性能基准更有决定力。继续调 keepalive、DNS 或 MTU 不会解决服务端发送很多而客户端收不到的回程丢失；保留现有配置作紧急小流量备用即可。

### 7. sing-box 与 Xray-core

这两个是代理核心，不是网络协议。速度和稳定性的主要决定因素仍是：客户端到入口 IP 的可达性、传输层（TCP/QUIC）、中转路径和协议配置。

- **Xray-core：** 当前优先用于 VLESS Reality 和 VLESS HTTP 类传输，协议真源、配置文档和 Reality/XTLS 路线集中，适合作为新主服务端。
- **sing-box：** 仍适合承载 Hysteria2、TUIC、TUN/路由等多协议能力，官方配置列出了完整的 Hysteria2/TUIC 入出站；但不应继续把当前已经在 VPS 本机失败的 Reality 组合作为唯一出口。[sing-box outbounds](https://sing-box.sagernet.org/configuration/outbound/)；[sing-box changelog](https://sing-box.sagernet.org/changelog/)

“把 sing-box 换成 Xray”能解决的只是实现兼容问题；“让旧 IP 从万达云直连恢复”不在任何核心的能力范围内。

## 是否必须更换 IP

| 目标 | 是否必须换 IP | 原因 |
|---|---|---|
| 继续直连 Reality/TLS TCP 443 | **必须**（或先改变万达云出口路径） | 旧 IP 的 TCP 包未抵达 VPS，换服务端核心无效 |
| 经 Cloudflare 代理的 gRPC/WS/HTTPUpgrade | **不必须** | 客户端连接 Cloudflare 边缘，旧 IP 仅作为源站；前提是 Cloudflare 能正常回源 |
| 在旧 IP 测 Hysteria2/TUIC | 不必须先换 | UDP 至少能握手，但能否承载大流量尚未证明 |
| 要低延迟、高吞吐、少中转的长期主节点 | **建议换** | 新 IP/新地区可恢复直接 TCP Reality，并避免 CDN 额外路径 |

## 推荐落地拓扑

```text
稳定主节点（先恢复）
Clash Verge / Mihomo
        │ VLESS gRPC + TLS（WS/HTTPUpgrade 备用）
        ▼
Cloudflare Anycast 域名
        │ TLS 回源
        ▼
旧 VPS 45.63.127.24：Caddy/Nginx + Xray VLESS

速度主节点（新 IP 验证后）
Clash Verge / Mihomo
        │ VLESS Reality RAW TCP 443
        ▼
新 IP / 不同地区或 ASN：Xray-core

实验备用
Clash Verge / Mihomo
        │ Hysteria2 QUIC/UDP 443
        ▼
旧 VPS（只有持续大包测试通过才保留）
```

## 验收门槛

所有测试均保持万达云开启，并明确强制选择被测节点。下列五项全部通过才可称为“可用”：

1. VPS/Cloudflare 源站日志或抓包能读回真实入站连接，排除“客户端显示已连接但服务端 0 包”。
2. 经 Clash Verge 的 HTTPS 请求连续 20 次成功，DNS 解析和出口 IP 都正确。
3. 至少 100 MiB 下载连续完成 3 次，不以 1 KiB 的 IP 查询替代吞吐验证。
4. 持续传输 10 分钟不掉线；空闲 10 分钟后能自动恢复连接。
5. 人为填错 path/SNI/凭据的负控必须失败，避免测试实际走了 DIRECT 或万达云自身出口。

只有延迟数字、握手日志、HTTP 200 回执或某次小请求成功，都不足以证明节点稳定可用。

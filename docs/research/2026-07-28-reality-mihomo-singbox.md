# Reality / Mihomo / sing-box 兼容性研究

> 核验日期：2026-07-28（Asia/Taipei）  
> 范围：只读核对 sing-box、Mihomo、Project X 的官方文档、官方 GitHub issue 与 release；不改客户端、VPS 或任何服务。  
> 证据边界：GitHub issue 的复现由报告者提供，除非 maintainer 明确确认，不能当成“官方已确认根因”；下文会单独标注这一点。

## 结论先行

1. 现有“sing-box 双端同机也失败”的证据**不足以**证明 sing-box Reality 本身有已知核心缺陷。高度相似的 [sing-box #4023](https://github.com/SagerNet/sing-box/issues/4023) 虽然声称同类失败，但 issue 被标 spam；更关键的是 maintainer `nekohasekai` 的唯一明确回复是 **`missing tls.server_name`**。因此首要动作不是换核心，而是从正在运行的客户端配置读回并核对 SNI/`servername`、公钥、short ID、UUID、Vision flow 和 selector。
2. 官方 sing-box 文档把 Reality 客户端所需字段定义为 `public_key`、`short_id`，而 TLS 的 `server_name` 是客户端 TLS 字段；Mihomo 的 VLESS 示例相应使用 `servername`、`reality-opts.public-key`、`reality-opts.short-id`。任一字段在运行时遗漏、错名或没有重新激活 profile，都会让 Reality 认证失败。先核验这些字段，才能把本机 EOF 当作核心问题而不是临时探针配置错误。
3. 目前没有“升级到某个版本必好”的官方修复。sing-box [1.13.14 release](https://github.com/SagerNet/sing-box/releases/tag/v1.13.14) 的说明只有泛化的 “Fixes and improvements”；Mihomo [v1.19.29 release](https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.29) 也未列出此 Reality 兼容性修复。两个未合并、已关闭的 Mihomo PR（[#2994](https://github.com/MetaCubeX/mihomo/pull/2994)、[#2995](https://github.com/MetaCubeX/mihomo/pull/2995)）提出 sing-box/remnawave 证书 HMAC/DER 表示与 x509 fallback 的互操作假设，但作者自称 prototype，maintainer 标为 `ai slop` 并关闭；不能用来升级、降级或打补丁。
4. Clash Verge 的 `verge-mihomo` 延迟/Timeout 不能单独证明“节点没拨号”或“服务端坏了”。Mihomo 的 `/proxies/{name}/delay` 是一次带 `url`、`timeout`、可选 `expected` 的 HTTP 测试；`alive`、`history`、以及 selector 的 `now` 都是另外的状态。官方也说明 `global` 模式必须在 `GLOBAL` 组选择节点。因此必须同时读 selector、做强制该本地 HTTP 代理的真实请求、再看服务端握手日志，不能靠一个延迟格子下结论。
5. 只有在**完整运行时字段核验后仍失败**，才把 Xray server 作为临时端口的隔离验证/回退路线；先成功再切 443。Xray 的 Reality/VLESS 字段与 Mihomo 的字段有官方定义，能映射 UUID、私钥对应公钥、short ID、SNI 与 Vision flow，但没有公开证据能替代本机实测。Mihomo 官方明确不考虑 Xray `v26.7.11+` 的兼容性；若要作替代实验，只测试 stable `v26.3.27` 这类不在该排除范围的版本，绝不直接切换 443。

## 上游证据到底说明了什么

现场在 VPS 本机的复现确实隔离掉了 Mac、Clash Verge、万达云以及客户端到 VPS 的外部路径；但它**没有隔离临时客户端配置本身**。上游 #4023 也报告了相同服务端错误：

- 服务端 `forwarded SNI`，随后 `TLS handshake: REALITY: processed invalid connection`；
- 换 Chrome/Firefox/random 指纹、不同 SNI、新密钥、`max_time_difference: "1m"` 都没有改变失败；
- 报告者声称同一 sing-box 服务端被 Xray-core 客户端成功使用。

这些是 issue 作者的复现结果，而不是 maintainer 签署的结论。更高优先级的官方信息是 maintainer 在该 issue 明确指出 `missing tls.server_name`（[官方 GitHub API 回读该 comment](https://api.github.com/repos/SagerNet/sing-box/issues/comments/4276078455)）。所以正确顺序是：先用运行时配置证明 `servername`/SNI 和 Reality 对应值完整一致，再考虑另一核心的隔离验证；不能直接从同一错误字符串推出 sing-box 1.13.14 有已知 bug。

## Mihomo 的延迟与“没有拨号”应该如何判读

官方 [Mihomo API 文档](https://wiki.metacubex.one/en/api/) 定义：

- `GET /proxies/{name}/delay?url=...&timeout=...&expected=...` 只是在指定节点上执行一次 HTTP 延迟测试，返回的是 `delay`（毫秒）。
- `/proxies/{name}`/`/proxies` 中 `alive` 和 `history` 是节点状态/历史；selector 另有 `now`，代表当前选中的子节点。
- `PUT /proxies/{selector}` 带 `{"name":"..."}` 才会选择 selector 的具体节点；[全局模式文档](https://wiki.metacubex.one/en/config/general/) 明确要求在 `GLOBAL` 代理组中选节点。

所以 Timeout 可能是 Reality 认证失败，也可能是 selector 没有选到 VLESS、规则走了其他组、测试 URL/DNS/期望状态不匹配。唯一足以证明“本节点正在处理真实流量”的最小证据组合是：

1. 读回 `GLOBAL.now`（或命中规则对应 selector 的 `now`）正是要测的 VLESS 节点；
2. 使用 Clash Verge 实际监听的 HTTP 代理端口运行一个显式代理请求，并要求 HTTP 成功；
3. 同时读服务端日志，确认该请求到达 VLESS 入站，且没有 `processed invalid connection`；
4. 再做一次有明确长度的下载，验证不是只完成首包/握手。

Mihomo 官方 VLESS 示例的相关字段为 `type: vless`、`server`、`port`、`uuid`、`flow: xtls-rprx-vision`、`tls: true`、`servername`、`client-fingerprint`、`reality-opts.public-key`、`reality-opts.short-id`、`network: tcp`。来源：[Mihomo VLESS 配置](https://wiki.metacubex.one/en/config/proxies/vless/)、[Mihomo TLS/Reality 配置](https://wiki.metacubex.one/en/config/proxies/tls/)。

## Mihomo 源码中的候选路径：必须用 debug 日志证实，不能直接打补丁

官方 `v1.19.29` 源码中，`GetRealityConn` 在 TLS handshake 结束后打印 `REALITY Authentication: <bool>`；若 `verified` 仍为 `false`，就返回 `REALITY authentication failed`。同一个文件的 `VerifyConnection` 在 ed25519 HMAC 校验成功时会设 `verified = true`，但后面的 x509 fallback 即使 `Verify` 成功也只 `return nil`，没有设这个标志。该代码路径本身可直接读到：[握手后的拒绝判断](https://github.com/MetaCubeX/mihomo/blob/v1.19.29/component/tls/reality.go#L118-L128)、[HMAC/x509 分支](https://github.com/MetaCubeX/mihomo/blob/v1.19.29/component/tls/reality.go#L174-L196)。

这**不等于**“当前 sing-box 必然触发该 fallback”。将它归因给 sing-box/remnawave 的 PR #2995 被 maintainer 以 `ai slop` 关闭，且没有被合并。因此安全的判定规则是：

- debug 日志已出现 `REALITY Authentication: false`：该候选路径与实际失败相符，再评估升级/替代客户端；
- 没有该日志且服务端直接 `processed invalid connection`/客户端 EOF：优先核验实际加载的 `servername`、公钥、short ID、UUID、flow 与 `tls: true`，而不是修改 Mihomo 源码；
- 不下载或应用 PR #2994/#2995 的补丁，不把网络传闻当修复。

## 条件性备用验证：Xray server + 现有 Mihomo client

### 先验证、后切换

这是**字段与日志核验仍失败之后**的备用实验，不是首选修复。Mihomo 官方在 Reality 文档中明确写道：对 Xray `v26.7.11+` 的刻意不兼容行为不考虑兼容；不可用时建议换为 Mihomo native listener、sing-box、旧版 Xray 或其他协议。[来源](https://wiki.metacubex.one/en/config/proxies/tls/#reality-opts)。

若仍需验证，只在 `127.0.0.1:<临时端口>` 启动官方标为 latest 的 [Xray v26.3.27 stable](https://github.com/XTLS/Xray-core/releases/tag/v26.3.27)，借助受控 SSH 本地转发让 Mihomo 的**该 VLESS 节点**去连接它。测试通过的判据：

- 强制 selector 选中该节点；
- 经过 Clash Verge HTTP 代理的请求收到预期 HTTP 状态；
- 连续下载至少数 MiB 成功；
- Xray 日志没有认证失败；
- 还原服务器地址/端口为 VPS `:443` 后重复以上真实传输。

任何一项失败都不切换 443。这样可以保留正在工作的万达云通道，并允许立即回滚到现有 sing-box。

### 字段映射（不写入真实凭据）

| 语义 | 现有 sing-box server | Xray server | Mihomo / Clash Verge client |
| --- | --- | --- | --- |
| 用户身份 | `users[].uuid` | `settings.clients[].id` | `uuid` |
| Vision flow | `users[].flow` | `settings.clients[].flow` | `flow: xtls-rprx-vision` |
| VLESS 关闭内层加密 | — | `settings.decryption: "none"` | `encryption: ""`（Mihomo 示例） |
| Reality 伪装目标 | `tls.reality.handshake.server` + `server_port` | `realitySettings.target`（旧名 `dest` 仍为别名） | `servername` 必须是允许的 SNI |
| 允许的 SNI | 与 handshake 相容的域名 | `realitySettings.serverNames` | `servername` |
| 服务端私钥 | `tls.reality.private_key` | `realitySettings.privateKey` | 不放私钥 |
| 对应客户端公钥 | 该私钥的 X25519 公钥 | 不放在 inbound | `reality-opts.public-key` |
| short ID | `tls.reality.short_id[]` | `realitySettings.shortIds[]` | `reality-opts.short-id` |

Xray 官方要求 server 侧 `target`、`serverNames`、`privateKey`、`shortIds`；client 侧必须使用一个允许的 `serverName`、`fingerprint`、`shortId`，以及名为 `password` 的对应公钥字段（旧名 `publicKey` 已改名）。Mihomo 则仍将这个客户端公钥命名为 `reality-opts.public-key`。来源：[Project X Reality 字段](https://xtls.github.io/en/config/transports/reality.html)、[Mihomo Reality 字段](https://wiki.metacubex.one/en/config/proxies/tls/)。

### Xray server 最小配置形状

以下是字段形状，不是可直接粘贴的生产配置；尖括号必须从现有已验证配置一对一迁移，且不得泄露私钥或 UUID。

```json
{
  "inbounds": [
    {
      "listen": "0.0.0.0",
      "port": 443,
      "protocol": "vless",
      "settings": {
        "clients": [{"id": "<UUID>", "flow": "xtls-rprx-vision"}],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "raw",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "target": "<CAMOUFLAGE_HOST>:443",
          "xver": 0,
          "serverNames": ["<SNI>"],
          "privateKey": "<EXISTING_PRIVATE_KEY>",
          "shortIds": ["<EXISTING_SHORT_ID>"]
        }
      }
    }
  ],
  "outbounds": [{"protocol": "freedom", "tag": "direct"}]
}
```

`xtls-rprx-vision` 与 TCP/RAW + TLS/Reality 的组合由 [Xray VLESS 文档](https://xtls.github.io/en/config/inbounds/vless.html) 明确列为支持的 XTLS 组合；Xray 的 transport 文档把 `raw`、`security: "reality"` 和 `realitySettings` 列为同一 `streamSettings` 的对应字段。[Reality 官方示例](https://xtls.github.io/en/config/transports/reality.html) 也说明 `target` 是 server-only，错误地放在 client 侧会触发角色误判。

对应的 Mihomo 节点保持以下最小形状（不需要把 client 换成 Xray）：

```yaml
- name: Tokyo VLESS Reality
  type: vless
  server: <VPS_IP>
  port: 443
  uuid: <UUID>
  flow: xtls-rprx-vision
  network: tcp
  tls: true
  servername: <SNI>
  client-fingerprint: chrome
  reality-opts:
    public-key: <PUBLIC_KEY_OF_PRIVATE_KEY>
    short-id: <SHORT_ID>
```

## 不应再尝试的“修复”

- 在没有读回运行时配置前盲目轮换 Reality 密钥、short ID、SNI、浏览器指纹或 `max_time_difference`：#4023 的报告者称这些变量无效，而 maintainer 反而指出漏了 `tls.server_name`。先审计字段，再决定是否需要更换实现。
- 仅凭 Clash Verge 的绿色延迟或 WireGuard 的最新握手宣称 VPN 已可用：它们都不是真实代理下载/应用流量的证据。
- 直接切 443、再看是否恢复：会把一次兼容性实验变成全量中断。应先以临时监听 + 强制 Mihomo 节点 + HTTP/下载/服务端日志完成绿灯。
- 直接使用 Xray `v26.7.11+`：Mihomo 官方明确不考虑该版本范围的兼容性；也不把未验证的 pre-release 当修复。

## 官方来源

- [SagerNet/sing-box #4023：sing-box client/server Reality 同机最小复现](https://github.com/SagerNet/sing-box/issues/4023)
- [sing-box v1.13.14 release（latest）](https://github.com/SagerNet/sing-box/releases/tag/v1.13.14)
- [sing-box TLS / Reality 字段文档](https://sing-box.sagernet.org/configuration/shared/tls/)
- [Mihomo VLESS 配置](https://wiki.metacubex.one/en/config/proxies/vless/)
- [Mihomo TLS / Reality 配置](https://wiki.metacubex.one/en/config/proxies/tls/)
- [Mihomo API（proxy delay、selector 与状态字段）](https://wiki.metacubex.one/en/api/)
- [Mihomo general / global mode](https://wiki.metacubex.one/en/config/general/)
- [Mihomo v1.19.29 Reality verifier source](https://github.com/MetaCubeX/mihomo/blob/v1.19.29/component/tls/reality.go)
- [Mihomo PR #2994（未合并）](https://github.com/MetaCubeX/mihomo/pull/2994)
- [Mihomo PR #2995（maintainer 标为 ai slop 并关闭）](https://github.com/MetaCubeX/mihomo/pull/2995)
- [Project X REALITY 配置](https://xtls.github.io/en/config/transports/reality.html)
- [Project X VLESS / Vision 配置](https://xtls.github.io/en/config/inbounds/vless.html)
- [Xray stable v26.3.27 release](https://github.com/XTLS/Xray-core/releases/tag/v26.3.27)
- [Xray #6477：26.7.11 pre-release + Mihomo 1.19.28 认证失败报告](https://github.com/XTLS/Xray-core/issues/6477)

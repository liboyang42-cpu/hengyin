# 自托管 runner 的网络配置(2026-08-28 定稿)

## 病象

CI 门禁随机变红,红的**不是门禁本体**,是门禁还没跑起来就挂了。四种形态:

| 形态 | 日志长相 |
|---|---|
| action 下载超时 | `Failed to download archive 'https://codeload.github.com/actions/setup-java/...' after 3 attempts`(每次 100s HttpClient timeout) |
| syft 装不上 | `[error] received HTTP status=000 for url='https://github.com/anchore/syft/releases/v1.24.0'` |
| gitleaks 装不上 | `Error: parameter 'file' is required` ← 下载失败返回空路径,再喂给 `extractTar` 抛出的**二次错误**,不是真病因 |
| grype 装不上 | 同 syft |

共同点:全是**从 github.com / codeload.github.com 拉东西**。

## 根因

**四个 runner 的 `.env` 里一条代理配置都没有。**

本机 shell 有 clash(`127.0.0.1:7897`),但 runner 是 LaunchAgent 起的,不继承登录 shell 的环境变量。
于是 runner 全程裸连 github.com。2026-08-28 实测同一个 URL:

```
裸连  200  30.14s / 30.00s / 30.10s     ← 三次都在 30s 上下,且经常直接超时
走代理 200  10.21s /  5.30s /  4.53s
```

30 秒是「运气好」的情况。actions 的 action 下载超时阈值是 **100 秒**,重试 3 次;
网络稍差就必然撞满,于是「随机」变红。

## 处置

给**每个** runner 目录的 `.env` 追加(runner 会把 `.env` 应用到 job 进程):

```
https_proxy=http://127.0.0.1:7897
http_proxy=http://127.0.0.1:7897
no_proxy=localhost,127.0.0.1,::1,*.local
```

⚠️ `no_proxy` 必须覆盖本地回环,否则 MySQL IT 容器(3307)和本地服务也会被代理掉。

改完必须**重启 runner** 才生效:

```bash
launchctl kickstart -k "gui/$(id -u)/actions.runner.liboyang42-cpu-chengyin.arlan-mac-chengyin"
# -2 / -3 / -4 同理
```

## 怎么确认真的生效了(回执 ≠ 观测)

`.env` 写进去了、runner 重启了,**都只是回执**。唯一算数的是从 CI 侧读回来:

```bash
gh pr view <PR> --json statusCheckRollup \
  --jq '.statusCheckRollup[]|select(.name=="Secret scan (gitleaks)")|.conclusion'
```

2026-08-28 的判据:#921 的 gitleaks 由 `FAILURE` 变成 `SUCCESS`,同一份代码、只改了代理。

## 兜底:工具二进制预热

代理让下载**快**,但不让它**必然成功**。所以另外做了一层:

- 7 个 action(checkout / setup-java / setup-node / sbom-action / scan-action / paths-filter / gitleaks-action)
  预置进每个 runner 的 `_work/_actions/<owner>/<repo>/<ref>/`,并**必须**同时创建
  `<ref>.completed` 标记文件 —— ⚠️ **只放目录不放标记,runner 会当成没缓存重新下载**。
- syft 1.24.0 / grype 0.110.0 预置进 `_work/_tool/<name>/<version>/arm64/`,同样需要 `arm64.complete`。
  grype 是下载后**对了官方 checksums.txt 的 sha256** 才放进去的。

⚠️ 注意 `anchore/sbom-action` **不读** GitHub tool cache,它每次都跑 anchore 的 `install.sh` 重新下载 ——
所以 syft 的预热对它无效,syft 那条只能靠代理。这点 2026-08-28 实测确认过(预热后 20:05 那次仍然 status=000)。

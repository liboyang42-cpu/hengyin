# 转台渲染(ECS)

GLB → 24 帧 300×396 WebP。由后端 `objectCardGenTask` 以子进程调用,约定见 `render.js` 文件头与施工文档第三版 §4.4。

⚠️ `ops/` 不在部署正则里:改这里**不会**触发 jar 部署,**也不会自动同步到 ECS**。改完要手动同步(见下)。

## ECS 上的布局

```
/www/wwwroot/turntable-render/
  render.js  package.json  package-lock.json      ← 本目录同步过去
  node_modules/                                     ← npm ci 装
  chrome-headless-shell-linux64/                    ← 下面第 2 步
```

以系统用户 `turntable`(无登录 shell)运行,不用 root。

## 安装(2026-09-22 首装记录)

1. 系统库(只装这 9 个,全部来自阿里云官方源,不走 EPEL):
   `dnf install --setopt=install_weak_deps=False atk at-spi2-atk at-spi2-core libXcomposite libXdamage libXfixes libXrandr mesa-libgbm alsa-lib`
   ⚠️ 不要 `dnf install chromium`:EPEL 的 chromium 会带进 avahi 守护进程和 nss-mdns(改 /etc/nsswitch.conf)。
2. 浏览器:Chrome for Testing 的 headless shell,版本钉死 154.0.8037.57,从 npmmirror 下载
   `https://cdn.npmmirror.com/binaries/chrome-for-testing/154.0.8037.57/linux64/chrome-headless-shell-linux64.zip`
3. `npm ci --omit=dev`

## 同步

```bash
scp -i ~/.ssh/chengyin_deploy render.js package.json package-lock.json root@<生产库主机>:/www/wwwroot/turntable-render/
ssh ... 'cd /www/wwwroot/turntable-render && npm ci --omit=dev'
```

## 自检

```bash
cd /www/wwwroot/turntable-render
runuser -u turntable -- env HOME=/tmp node render.js --selftest          # 文件头校验,不起浏览器
runuser -u turntable -- env HOME=/tmp nice -n 10 node render.js sample.glb /tmp/tt-selftest && ls /tmp/tt-selftest | wc -l   # 应为 24
```

`sample.glb` 是 Khronos 官方示例 BoomBox(10.1MB)。

## 2026-09-22 ECS 实测(Phase 0b 闸口)

| 项 | 结果 |
|---|---|
| 单件耗时 | 4.3 秒(闸口 ≤ 60 秒) |
| 出图 | 24 帧 300×396 WebP,共 150KB |
| 内存 | 单进程峰值 120MB;turntable 用户全部进程 RSS 合计峰值 927MB(多进程共享页被重复计算,实际更低),只持续约 4 秒 |
| 对 Java 的影响 | 渲染期间本机请求中位 3ms、最大 19ms(渲染前 2–3ms) |
| 沙箱 | 以非 root 用户 `turntable` 运行,**未关沙箱**即可跑通 |
| 负例 | 截断的 GLB → 退出码 1、0 帧;非 GLB → 退出码 1 |

⚠️ 截断 GLB 是实测踩出来的:最初只校验魔数,一个下载断在 2MB/8MB 的文件被渲染「成功」出 24 帧残缺模型。
现在入口校验「魔数 + 版本 2 + 头里声明的长度 == 实际大小」,自检里有对应用例。

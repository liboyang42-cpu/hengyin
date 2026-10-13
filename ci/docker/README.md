# 城瘾 CI 构建镜像

## 构建与推送

```bash
# 需要 ACR(阿里云容器镜像服务)。云效的公共构建集群拉不到 docker.io,
# 但能拉 ACR —— 现在用的 alinux3 就来自 build-steps-public-registry.cn-beijing.cr.aliyuncs.com
docker build -t <你的ACR地址>/chengyin/ci:20260827 .
docker push  <你的ACR地址>/chengyin/ci:20260827
```

⚠️ 本机没有 docker daemon(今天装了 colima,可用 `colima start` 后构建)。

## 用法

把云效流水线各 job 的 `runsOn.container` 换成这个镜像,然后**删掉每个 job 里
的环境准备段**(装 node / 装 python / 下 MySQL / 下 gitleaks / 下 grype)。

预计收益:
- 每轮省掉 891MB MySQL 下载 + 各种 install
- CI 不再因上游发版而随机红(npm 10.9.8、grype latest 302 那两次)
- 版本可复现:镜像 tag 即环境快照

## ⚠️ 用 tag 不要用 latest

镜像本身也是「会自己变的外部资源」。用日期 tag(如 `20260827`),
换版本时显式改流水线配置 —— 否则等于把版本漂移问题从工具挪到了镜像。

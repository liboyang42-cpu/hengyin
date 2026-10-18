# VPS EXTRA 1bfa81a5 POI查询合同回归

PASS：E02_12Test 2项实际通过，0失败、0错误、0跳过。

- 固定源码：`1bfa81a5a58ffa8112c10414dad9fdb1701ef188`；相对29fe仅POI选项条件要求status=1、新增2项H2/MyBatis生产XML回归与自有说明，无依赖变化。
- 源包SHA256：`4947dd9644898c08e412e88082cfb674da911b24c929c69d63bb044ef151aadb`
- 守卫：原212，1536MiB无额外swap、1CPU、256PIDs、network none、共享锁。`2026-10-16T06:05:40.731295+00:00`—`2026-10-16T06:08:19.134125+00:00`，exit0/completed。
- fresh1XML精确类集/实际方法数匹配，报告及日志hash见summary。精确容器`9cc6be5c1840de231920d742221aacaa46d87d9647a430475b301c03b54aed60`已由显式chengyin-aws过滤确认不存在。
- 首次06:03:49Z因其他任务持锁exit75、containerId null、实际0项；正常锁释放后06:05:40Z重试。首次run/result另存并纳入summary，不覆写历史。

验证覆盖显式上架/下架/合成NULL、城市非空、投影、排序、limit和保存快照同口径。历史生产NULL来源仍未证实；合成NULL仅兼容负控，本项不增加BUG计数。

VPS独占目录`/home/ubuntu/previews/cy-vps-extra-1bfa81a5-20261016`保留source/cache、源包、selection、run/result、日志、fresh XML。没有重跑旧64，不能给新SHA继承旧成绩。

真实MySQL（含E02_25MySqlIT2项）、真实Redis、运行HTTP、设备、业务、集成、合并部署均未验。用户决定保持现有入口、不新增；未改212/CI/网络或手动触发CI。

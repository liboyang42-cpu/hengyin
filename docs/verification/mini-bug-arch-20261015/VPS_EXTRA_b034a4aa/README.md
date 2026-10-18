# 原E02两类异常定向修复：8实际全通过

固定修复源码 `b034a4aabdacffbf7a2ef4f400b56eb2c6eed6cf`，直接父为 `54fdc951a59467cd93b6ba49cf87ae890faf2500`。root实际diff确认仅两份已授权测试夹具中四条JSON_TYPE/JSON_CONTAINS的Java方法名引用从单引号改为双引号；6+2方法及全部断言、负控、生产XML、生产源码、依赖均未变。

原212于2026-10-16 14:03:53.575927Z—14:06:40.642804Z完成，**两类8实际PASS / 0FAIL / 0ERROR / 0SKIP**。fresh两份XML精确匹配测试类与实际testcase数，guard0 / completed、BUILD SUCCESS；仅复测原异常两个类，没有重跑其余四类或整批35。

| 类 | 实际通过 | 失败/异常/跳过 |
|---|---:|---:|
| com.chengyinhub.business.longrun.E02_23Test | 6 | 0/0/0 |
| com.chengyinhub.business.mapper.ViewCreativeSquareCommunityPolicyMyBatisTest | 2 | 0/0/0 |

原923170ab六类35=28通过/7异常仍保留在[原失败证据](https://github.com/liboyang42-cpu/hengyin/blob/3489ce72995b840af2196deab80fa8f2a88e527c/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_923170ab/README.md)，包括此前两次0项资源拒绝。新SHA只具有本次8项成绩，不能声称新35全跑或继承旧28。原作者唯一夹具修复槽可在签收后释放，业务源码保持冻结。

本次VPS task `/home/ubuntu/previews/cy-vps-extra-b034a4aa-20261016`；源包SHA256 `497621755df5245ff3200a41f617a3bf35daf7cee8c7ec542d87603758937369`，守卫SHA256 `12374f742dcf1547c8eabf28c28403c94c8307fe20cba0a3a63d7f2b49764fba`。准入内存2,840,732KiB，原1536MiB/1CPU/256PID/no-network/no-swap入口不变。精确容器 `8e4127dcda5d845b683e88158ef10443b7f7ee6cd29ea45c11eaf11b5624b3e8` 已由守卫清理，并经显式chengyin-aws精确ID过滤empty/exit0确认；source/cache/完整日志/两XML/原回执保留。

H2有限JSON别名只支持本测试合同，不是真MySQLJSON行为。真实MySQL（含E04新5+旧3）、Redis/运行HTTP/设备业务/集成未验。迁移仅源码、未执行，未回填/合并/部署；history_id依赖代码不能部署到未迁移库。逐报告及日志哈希见[summary.json](summary.json)。

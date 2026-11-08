# P9 队内成员名次 — UI 输入

状态：SOURCE_ONLY / NOT_RUN。本文件供 root 合并到唯一 canonical UI 清单；本 lane 未修改 UI 源码或 canonical 清单。

## 已写源码合同

沿用 `GET /api/orient/team/detail?teamId=`，响应仍为原 AjaxResult 的 `data`。原有 team、threshold、tasks、summary 和 members 各旧字段保留。

| 位置 | 新字段 | 含义 |
| --- | --- | --- |
| data | rankBasis | 固定 `COMPLETED_TASK_COUNT` |
| data | rankScope | 固定 `TEAM_ACTIVE_MEMBERS` |
| data | rankedMemberCount | 当前队伍在岗成员中完成数大于零的人数；不是最高名次 |
| data.members[] | doneTaskCount | 原 doneTaskIds 的数量；使用既有真实完成事实按任务去重后的结果，仅计当前可见、未下架任务 |
| data.members[] | rank | 完成数大于零时的竞争名次，例如 `1,1,3`；零完成为显式 null |
| data.members[] | ranked | doneTaskCount 大于零时为 true，否则 false |
| data.members[] | mine | memberId 等于当前已登录查看者时为 true |

members 已按完成数降序、并列 memberId 升序返回；零完成成员留在列表末尾。客户端使用服务端次序、rank 和 ranked，不自行用列表下标生成名次；零完成显示“未上榜”，全部零完成时 rankedMemberCount=0，不补第一名。memberId/name/avatar/role/doneTaskIds 仍保留，role 不再决定该列表次序。

本次 metadata 的实际字段名是 data.rankBasis / data.rankScope，不是旧待实施清单中的 memberRankBasis。队伍之间 `GET /api/orient/team/ranking` 的原返回及排序未修改，其 teamDoneCount 是团队阈值口径，不能混成成员个人完成数。

## 门槛与异常

保留登录、当前在岗成员、城市定向内容和 orient.team.board.enabled 开关门槛；不自动开启开关，不把禁用或越权当作空榜。待审/退队成员不进入成员列表和 rankedMemberCount；本人标记以当前请求身份为准。

## 后续验收

root 统一在批准测试 VPS 编译并运行 OrientTeamBoardServiceImplTest；之后用正常 UI 和独立读回验收正数排序、1/1/3、零完成、跨来源重复任务、退队/待审、非成员、开关关闭和本人标记。本 lane 未做 Java/H2/MySQL/HTTP/客户端/设备/部署验证，不表示接口已部署可用。

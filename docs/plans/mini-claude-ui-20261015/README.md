# Claude 小程序 UI 与新功能（2026-10-15）

> 分工依据：`docs/plans/mini-codex-cloud-20261015/CLAUDE.md`（PR #16）。Codex 做既有 bug 与共用后端；Claude 做 UI、新功能及**新功能专属**后端。原生 iOS / Flutter 排除。
> 共同基线：`d551c6babd018d41aec90dad2c0acaf1c534644d`（Codex 在 `codex/mini-bug-arch-integration-20261015` 复用 #11 + #12–#15，4480 项动作合同）。Claude 集成分支：`claude/mini-ui-features-20261015`。
> 本批不做：新迁移、新依赖、生产配置、鉴权/RBAC 改变、资金/留存/结局政策改变；会费、群聊、群投票维持不做。

## 1 现状对账（基线 d551c6b 实读）

### 1.1 已实现（来自 #12–#15，CI 绿，但**均未做 VPS / 开发者工具 / 真机验收**）
| 功能 | 位置 | 说明 |
|---|---|---|
| 商家装修页返回守卫 | `pages/merchant/decor` | `page-container` 拦截，复用既有「放弃修改？」 |
| 图片预览统一出口 | `utils/media-preview.js` | 只替换了缺 `current` 的调用点 |
| 定位兜底（上次位置） | `pages/searchmap`、`pages/square/list` | 不再直接落上海坐标 |
| 打卡码文本兜底 | 商家据点/团码/商家页 | 码图缺失时显示等宽码文本 + 长按复制 |
| 俱乐部置顶公告 | `pages/club/detail` | 最多 2 条，本地已读 |
| 继续上次漫游 | 首页 `cy-roam-resume-bar` | 只读本机恢复记录 |
| 官方路线**浏览** | `subpackageRoam/official-routes`、`official-route` | 列表 + 详情 + 站点导航 |
| 收到的邮票 | `scene-roam-stamp-album` | 新 tab |
| 清除 NPC 对话记录 | `components/cy/npc-chat` | 危险确认 + 服务端删除 |
| 举报队伍 | `pages/team/report-sheet` | 回执显示任务号 |
| 积分明细 | `subpackageMember/points-history` | 分页 + 四态 |
| 主题状态键管理 | `pages/publish/components/topic-state-keys` | 发布高级设置 |
| 游玩页音频/轮询/精度/长按提示 | `pages/play`（**Codex B01 独占**） | Claude 不再改 |

### 1.2 已实现但需补齐（Claude）
| # | 项 | 缺什么 | 依赖 |
|---|---|---|---|
| G1 | 官方路线「按这条路线开始」 | 详情页只能浏览；漫游开局没有「跟随路线」入参/状态 | 无 Codex 依赖；如需后端，属新功能专属（不得加表） |
| G2 | 门店认领证明材料 | `asset_id` 列注释为「已有对象存储资源标识，不存客户端任意URL」，当前前端把上传后的完整 URL 当 assetId；`proofType` 写死 `PHOTO` 无枚举依据 | **等 Codex C00 核 PR14 认领材料合同**（OPT-060）后再改，不自行编造 |
| G3 | 漫游入口文案与代码整洁 | 入口写「官方线路」、页面写「官方路线」；`pages/roam/index.js` 两处一行两语句 | 无 |
| G4 | 积分规则展示 | 规则/汇率文案写死在客户端（缺陷 P1-D29） | 只做展示读取既有配置接口；规则本身不改 |

### 1.3 未实现的新功能（后端接口已存在、无需迁移）
| # | 功能 | 已有接口 | 备注 |
|---|---|---|---|
| N1 | 我的举报 / 被举报方查看 / 申诉 | `GET /api/community-trust/reports/mine`、`/reports/{id}`、`/reports/{id}/subject`、`POST /reports/appeal` | 原工单 POST-RPT-01..03（主端曾写 iOS，现改小程序） |
| N2 | 俱乐部自建勋章（创建/列表/授予） | `POST /api/club/badge/create`、`/list`、`/grant` | 原工单 CLUB-BDG-01..03；#12 已修平台墙泄漏 |
| N3 | 官方活动主办方控制（邀请/转交/排期/暂停/恢复） | `POST /api/official/v2/events/{id}/organizer-invites`、`/organizer-transfer`、`/schedule`、`/pause`、`/resume` | 原工单 OFF-*；已有 `pages/activity/official-*` 页 |
| N4 | 昵称校验与推荐 | `POST /api/user/nickname/check`、`/suggest`（路径以控制器为准） | 原工单 NICK-01/02 |
| N5 | NPC 对话导出 | `GET /api/ai/npc/chat/data/export` | 与 C1 删除成对的数据权利入口 |

### 1.4 本批不做（原因）
- 需要迁移：想去收藏、领券写接口、照片两段上传、护照云端化、广播过期/已读、积分有效期、按作品付费模式。
- 需要后台先配数据或政策：成长任务、创作者中心、礼物、商家众包/安全感评分（G4/G6 政策行与写入方）。
- 属 Codex 的 bug：退款订阅授权补齐、点赞/作者保存、积分券库存账务、排期候补、内容隐私等（见 PR #16 TASKS.md）。

## 2 Claude 任务卡（云端并行，最多 4 个）
见 `TASKS.md`。

## 3 留给本地验收（用户在开发者工具/真机上看）
云端只能跑单测与静态门；以下必须本地看：
1. 每张卡 PR 描述里的「本地验收清单」（入口位置、四态截图、深浅主题、长文本截断、44px 命中区）。
2. 视觉微调（间距、层级、动效）留给本地一轮：云端不做像素级调整，只用既有组件与 token。
3. 真机项：G1 跟随路线时的定位与地图、N3 主办方操作的二次确认、N5 导出文件/复制。
4. **开发者工具连测试服**：先确认 `ext.json` 指向 VPS 回环地址（开发版/体验版未配会回落生产）。

# Claude 小程序长期任务清单（30 项）

> 2026-10-15 用户指示：三条开发线持续领取，做完一项开草稿 PR 即领下一项；**本阶段不做验证**（不等 CI、不做 VPS/开发者工具/真机），用户回来后统一验证。
> 规则仍适用：`TASKS.md`（含末节「用户决定」）、CY-COORD-20261015-v1、Codex `owned_files`（`docs/plans/mini-codex-cloud-20261015/tasks.json`）不可改；无迁移/新依赖/生产配置/鉴权与资金政策改变；会费、群聊、群投票不做；官方路线跟随暂缓。
> 领取方式：分支 `claude/bl-NN-<短名>` 存在即视为已领取。按编号从小到大领第一个未领取项。先核实代码现状：已经做完、属于 Codex 文件、需要新接口/新表、或需要产品决定 ⇒ 在 PR #21 评论 `BL-NN BLOCKED: 原因`，推一个只含说明的空提交分支占位（防止重复领取），然后领下一项。

| # | 名称 | 内容 | 范围提示 |
|---|---|---|---|
| 01 | 漫游 POI 筛选 | 漫游地图加筛选半屏：半径（1/3/5 km）、只看未到过；本地持久化 `roam_filter_v1`（按账号）；半径通过现有 `/api/roam/pois` 的 radius 参数传，不改后端 | `pages/roam/**` 薄接线 + 新组件 |
| 02 | 漫游静止提示 | 站着不动（位移 < 3m 持续 60s）时 HUD 显示「原地停留中」，不改计时规则 | roam 新 util + HUD |
| 03 | 漫游离线提示 | 位置上报连续失败/断网时显示「网络断开，位置暂不同步」，恢复后消失 | roam |
| 04 | 积分明细筛选 | 积分明细页加「全部/获得/使用」切换（前端对一次全量列表筛选），月份分组 | `subpackageMember/points-history/**` |
| 05 | 收到的印章详情 | 印章册「收到的」点开详情半屏（来源、时间、照片），只用接口已有字段 | `components/cy/scene-roam-stamp-album/**` |
| 06 | 协办邀请卡片 | 官方活动收件箱里协办/履约邀请显示活动名、时间、角色说明，接受/拒绝后回读 | `pages/activity/official-inbox/**` |
| 07 | 勋章墙俱乐部分组 | 个人勋章墙把已获得的俱乐部勋章单独分组（仅当接口字段能区分；否则 BLOCKED 并写读合同请求） | `subpackageP3/**` 勋章墙 |
| 08 | 二级页导航统一·会员分包 | `subpackageMember` 里仍用左对齐大标题/自绘返回的二级页改为 `cy-nav-bar`（返回+居中标题+胶囊） | 不碰 Codex 订单/参与详情组件 |
| 09 | 二级页导航统一·俱乐部 | 同上，`pages/club/**` | |
| 10 | 二级页导航统一·商家 | 同上，`pages/merchant/**` | |
| 11 | 二级页导航统一·漫游分包 | 同上，`subpackageRoam/**`、`subpackageP3/**` | |
| 12 | 弹层统一·俱乐部 | 自绘弹层改用 `cy-sheet`（标题+关闭+底部按钮），`pages/club/**` | |
| 13 | 弹层统一·商家 | 同上，`pages/merchant/**` | |
| 14 | 弹层统一·发布 | 同上，`pages/publish/**`（不含 Codex 文件） | |
| 15 | 商家页无描边 | 商家/浅色页卡片、输入框去描边，灰底白卡，按 token；`pages/merchant/**` | |
| 16 | 官方活动页无描边 | 同上，`pages/activity/official-*` | 等 CL4 合并后再改 detail |
| 17 | 返回守卫·权益编辑 | `pages/merchant/decor/perks` 有未保存修改时用 `page-container` 拦截返回（照 decor 主页写法） | |
| 18 | 返回守卫·俱乐部编辑 | 俱乐部资料/主题编辑页同上 | |
| 19 | 返回守卫·券设置 | `subpackageMember/couponInfo` 等券编辑页同上 | |
| 20 | 图片预览收口·俱乐部 | `pages/club/**` 的 `wx.previewImage` 改用 `utils/media-preview.js` | |
| 21 | 图片预览收口·其余非 Codex 文件 | 其余页面同上（跳过 Codex 文件） | |
| 22 | 列表 key 修正 | 剩余可能重复的图片/标签列表 `wx:key="*this"` 改为稳定 key（跳过 Codex 文件） | |
| 23 | 定位兜底·其余页面 | 其余写死上海坐标的地图页改用 `utils/location/last-location.js` | |
| 24 | 四态统一·商家 | 商家页加载/空/失败/无权限统一用 `cy-state-shell`，状态名统一 | |
| 25 | 四态统一·俱乐部 | 同上，俱乐部页 | |
| 26 | 骨架屏补齐 | 无骨架的列表页（消息、勋章、积分明细、官方活动）补 `cy-skeleton` | |
| 27 | 空态文案诚实化 | 空态里写承诺/假引导的文案改为如实说明（先列清单再改，逐条说明依据） | |
| 28 | 漫游历史筛选药丸收编 | 漫游历史 `.hs-fp` 自绘筛选改用 `cy-tabs` chip 变体（组件注释已建议） | |
| 29 | 设计 token 回收·俱乐部 | `pages/club/**` wxss 硬编码颜色/字号/圆角改 token（DS 门基线内的项） | |
| 30 | 设计 token 回收·漫游 | 同上，`pages/roam/**`、`subpackageRoam/**` | |

每项 PR：草稿，合回 `claude/mini-ui-features-20261015`；正文首行 `CY-HANDOFF: CLAUDE`，写明 BL 编号、改了哪些文件、协调者统筹文件补丁（app.json/台账/基线/冻结计数）、未验证说明。

# 资金 UI 中间记录（未完成）

**10:00:17Z 共享库 OOM 事故后：本页所有 DB UUID、ID、运行版本与读回均为事故前历史记录，当前库内容不保证存在。** root 浏览器已离线，前端 18096 已停止并确认无监听；不再写共享 HTTP／SQL。六个编辑器存储项与当前草稿保全于私有目录 `/private/tmp/codex-money-recovery-20261105/`，并已复制校验到持久目录 `/Users/arlan/Documents/Codex-Recovery/chengyin-shared-rig-20261105/root-money/`，含 SHA256 清单和原草稿独立副本，不含登录存储。root 没有共享库完整 SQL dump，未重启／重建／恢复数据库。不能用旧 UUID、截图或已核验 JAR 声称环境恢复。

2026-11-05。前端 `a8fb71f466966438a8dc29ada7636c34d67ddad1`，root 自有前端端口 18096；共享后端 18099，仍为 `a2285b3dd558d40357462a87cd43a70b8d538548`。Play 独占应用重启。本次没有新建后端或数据库，没有部署或连接生产。

数据库独立只读回查：`cy_e2e_51`，UUID `76fd666f-d8ef-11f1-b090-d26bfff2310e`，容器 `cy-chain-play-map-20261105-1-mysql`。身份、俱乐部和主题均由界面操作创建；SQL 仅作读回，没有用作者创建接口或 SQL 代写内容。

| 步骤 | 实际结果 | 证据 |
|---|---|---|
| 检查旧身份 9003 | 有一个俱乐部，但角色为 player，isClubLeader=false，编辑器不显示招商。未修改该共用账号。 | 角色接口读回；不用此身份继续资金走查 |
| 独立身份申请主理人 | 四步界面填写，角色实际变为 club；member 990007。 | [主理人完成页](money-16-own-leader-done.jpg)；独立 DB role=club |
| 界面创建俱乐部 | 990005，MONEY-CODEX-1105 慢逛社，owner 990007。 | [俱乐部完成页](money-17-own-club-created.jpg)；独立 DB 同值 |
| 专业编辑器创建主题 | 自由探索、类别城市漫步、真实上传并裁剪合成封面、剧情、两章 PERK 承接配置、档期。页面预览显示 2 章／0 节点；节点应由后续商家承接提供。 | [当前本机预览](money-32-preview-or-issues.jpg)；不代表两个章节都已落库 |
| 创建草稿 | 界面触发 v2/create 返回 topicId 990029、DRAFT、published=false。 | [筛选后的请求收据](receipts.json)；DB member=990007、club=990005、product_type=2、lifecycle/status/user_status=0 |
| 独立读回章节 | 78：第1章咖啡、recruit_enabled=1、PERK、category_id=4001；79：第2章、recruit_enabled=0、PERK、category_id=NULL。 | 第二章书店及后续修改仍在本机，不能声称已保存 |
| 保存核对／发布 | author-saved-detail 返回 HTTP 404，当前显示“待确认”。AI 预检另返回业务 code 500、配置缺失，现有产品策略将其降为非阻断建议，不能算预检通过。 | receipts.json；实际保存阻塞是旧后端缺 87cb26eb8 的新只读接口，未绕过保存核对，未修改安全策略 |

临时浏览器 harness 原本没有 picker-view 的选择事件。本目录 [picker-view-seam.js](picker-view-seam.js) 仅在 root 自己的临时浏览器中为可见原生日期行补点击选择，通过既有 MPWEB.fire 发送 change；不调用页面 handler、不写页面 data、不请求作者 API。实点 11 月 6 日／11 月 8 日后，原页面确认值为 `11月6日 09:00`／`11月8日 18:00`，断言通过。[选择画面](money-28-picker-seam-choice.jpg) 是浏览器控件替身，不能当微信原生滚轮验收。封面、位置、支付等 rig 能力也不是真机证明。

所有引用截图均逐张查看，JPEG 780×1688、每张小于 200 KB。未提交原始网络日志、登录凭据或签名图片 URL。主理人电话字段使用虚构测试联系名，非真实个人资料。

停止位置：保留原浏览器的离线状态和本机草稿，原 `/tmp/codex-money-draft-990029-backup.json` 已复制进上述私有保全目录。等待共享环境管理者完成恢复、独立确认库目标与身份，再判断事故前 ID 是否仍有效；不能直接继续向这些 ID 写入。之后仍需通过界面核对原主题、保存第二章／档期、发布，以及商家申请、供给、定价、场次、开售审核、两人付款、两店核销、14 天前后结算与退款幂等走查。未取得新的开售或资金 UI 闭环 PASS。

STATUS: PARTIAL

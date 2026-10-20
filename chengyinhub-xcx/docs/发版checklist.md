# 城瘾小程序 发版 Checklist(SYS-S2 固化)

> 每次微信「上传 → 提交审核 → 发布」前逐条过。机械项交给自检脚本,人工项自己确认。
> 自检脚本:`cd chengyinhub-xcx && node scripts/preflight-release.js`(有 BLOCK 退出码 1,可挂 CI)。源码侧微信隐私合同另由 `npm run verify:wechat-release` 检查；它不能替代微信后台读回。

## A. 机械项(脚本自动跑 preflight-release.js)

- [ ] **域名指向生产**:`app.js` 生效的 `siteBaseUrl` = `https://www.chengyinhub.com/prod-api`(HTTPS,非 localhost)
- [ ] **无明文 http/localhost**:源码无未注释的 `http://localhost` / `http://<ip>`
- [ ] **console.log 调试残留**:发版前清理(`console.error` 例外保留)
- [ ] **主包体积**:主包页数 ≤ 40(超了看 FE-16 主包瘦身),开发者工具「代码依赖分析」主包 < 1.8M。
      余量薄的时候先跑 `npm run report:mainpkg` 看该动哪儿——它列出主包字节构成、
      零引用组件、以及只被单个分包用到因而可下沉的组件(源码字节,真实判据仍是 B 段的官方回读)。
- [ ] **死注释**:`app.js` 被注释的 localhost siteBaseUrl 死行顺手删(非阻断)
- [ ] **法务文本**:`utils/agreement-docs.js` 不含占位/未定稿声明，主体、客服电话、生效日与当前发布版本一致
- [ ] **开发视角隔离**:体验版/正式版启动时清除 `debug_user_view`，不得让旧 Storage 改写真实 RBAC 视角

## B. 人工项(脚本管不到,自己确认)

- [ ] **隐私协议**:`app.json` / 微信后台「用户隐私保护指引」与实际用到的接口(定位/相册/位置)一致,无遗漏声明
- [ ] **双版本页别删错**:`signupInfo`(v1 主题类)/`signupInfo2`(v2 活动类)按 ownerType 分流,**都在用不能删**;真僵尸只有 `templatedetail2`(已删)
- [ ] **后端已部署**:本次前端依赖的新接口/字段对应的 jar 已部署、迁移已落库(见部署交接清单),否则新功能空态
- [ ] **支付/核销真机冒烟**:报名→支付→票夹→入场码/核销 至少走一遍真机
- [ ] **版本号 + 更新日志**:微信后台版本号递增,备注写清本次变更
- [ ] **截图 provenance**:本次改动的页面若在 `docs/screenshots/` 有截图,同 PR 内重拍或删掉过期图,
      并在 `docs/screenshots/README.md` 清单里补上拍摄 commit。过期截图会让人得出相反结论
      (`coop-finance-merchant-white-2026-08-04.png` 就干过一次)。
- [ ] **微信官方包体读回**:先用开发者工具 `preview` 或 `upload` 的 `--info-output` 保存 JSON,再执行 `node scripts/wechat-release-contract.js --info <info.json>`；门禁兼容 DevTools `size.total/packages` 与 miniprogram-ci `subPackageInfo`（`__APP__`=主包、`__FULL__`=整包，数字按 bytes），逐项确认主包/每个分包 ≤2M、分包合计 ≤30M,内部主包 `<1.8M` 目标仍保留。没有 `info-output` 不得写“包体合规”。

## C. 发布三件套顺序(自有服务器 <生产库主机> / www.chengyinhub.com)

1. 跑预检 SQL(只读)→ 备份 → 执行本批 migration
2. 打 jar 部署后端(旧 jar 留作热回滚)
3. **定时任务启用**(仅当本批新增或恢复 `sys_job` 行时,见下面 C-1)
4. 微信开发者工具「上传」→ 后台「开发版本」读回 → 选择并核验「体验版」可扫码 → 「提交审核」→ 审核通过 → 「发布」；上传、开发版本、体验版、审核、发布每个状态分别留证，不能用 CLI exit 0 代替后续状态。

### C-1. 定时任务(sys_job)启用顺序 —— 已经付过一次代价的顺序

> 2026-08-26 事故:`clubEventOpsTask` / `clubPostMediaReviewTask` /
> `merchantCrmDeliveryRecoveryTask` 三条 Quartz 注册在**包含其 bean 的 jar 还没部署**时
> 就被插入并启用,生产每个 cron 周期报一次 bean-not-found,最后靠
> `migration_merchant_z_quartz_jobs_pause_repair_20260826.sql` 把它们收敛回暂停态止血。
> 根因是顺序,不是某个人手抖 —— 所以顺序写进流程,而不是写进那次的复盘。

- [ ] **迁移只准插暂停态**:任何新增 `sys_job` 行的迁移,`status` 必须是 `'1'`(暂停),
      `remark` 必须写明「默认暂停」与到期复核日。迁移**永不**负责启用。
- [ ] **启用晚于 jar**:先确认新 jar 已部署且**自报 commit 等于本次 master SHA**,
      再启用。判据不是「我刚才部署过」,是新连接回读到的版本号。
- [ ] **bean 存在性回读**:启用前逐条确认 `invoke_target` 里的 bean 真在新 jar 里
      (`grep` 类名 + 该 bean 有 `@Component`/`@Service`),缺一条就不启用那一条。
- [ ] **启用后跨周期观察**:启用后至少跨一个 cron 周期看日志,确认无
      `NoSuchBeanDefinitionException` / bean-not-found。有就立刻在后台暂停,不要等。
- [ ] **暂停态不会自动过期**:被设为暂停的任务**到期日到了也不自动启用**。
      到期未启用必须复核 —— 当前挂着的到期日见
      `chengyinhub-admin/sql/migration_merchant_z_quartz_jobs_pause_repair_20260826.sql`。
- [ ] **知道停着的代价**:确认这三条(或本批任意一条)停着期间谁在替它兜底。
      例:`clubPostMediaReviewTask` 是俱乐部动态图片送审的**重试恢复**,
      停了就意味着送审失败的图片不再补送 —— 这是内容安全缺口,不是延迟。
- [ ] **SQL 改 status 不等于调度器已 pause**:`UPDATE sys_job` 只改持久态,
      当前 JVM 里已装载的 Quartz 调度器不会因此立刻暂停。必须搭配后台/API 侧 pause,
      或受控重启使调度器重载。

## D. 回滚

- 后端:热回滚旧 jar;迁移用幂等 SQL + 备份表
- 小程序:微信后台「版本回退」到上一线上版本

# 小程序工程资产清单 (mini-program-engineering-inventory)

> Goal: 非页面工程质量持续优化 Loop · 目标仓库 `chengyinhub-xcx`
> 生成于 Loop Preflight(Step 0)。覆盖率见文末。

## 0. 规模基线
- 主包页数 34;分包 11(roam/play/activity/A/B/P3/publish/club/topic/merchant/coop)
- JS 文件 162(排除 node_modules/scripts/tests):utils 27 / components 28 / pages 85 / subA+B+P3 20 / 其它
- 单元测试 26 个文件(tests/unit),176 用例基线全绿
- 组件框架 glass-easel;自定义 tabBar;`lazyCodeLoading: requiredComponents`

## 1. 应用启动 / 全局生命周期
| 资产 | 文件 | 职责 | 调用方 | 依赖 | 风险 | 已审 | 已验 | 问题# | 最后验证轮 |
|---|---|---|---|---|---|---|---|---|---|
| App 入口 | `app.js` | onLaunch:applyTheme('dark')、隐藏 tabBar、建 appReadyPromise、恢复会话、firstLogin、灰度开关 | 全局 | theme/session/transport/consent | 高 | ✅ | ⏳ | — | L0 |
| 主题 | `utils/theme.js` | applyTheme('dark'/'light') | app.js/index | — | 低 | ⏳ | ⏳ | — | — |
| 灰度开关 | app.js `loadFeatureFlags` | 启动拉 `/api/config/features` 覆盖,默认全关 | app.js | sendRequest | 中 | ⏳ | ⏳ | — | — |

## 2. 路由 / 分包 / 分享
- `app.json` 注册:主包 34 页 + 11 分包;`preloadRule` 4 条;`sitemap.json`
- 分享/深链入口:各页 onShareAppMessage(需审 path 是否注册)
- 已知坏分享(基线 lint):`subpackageA/pages/infomationdetail/infomationdetail.js` → `/pages/infoDetail/infoDetail`(未注册)

## 3. 网络请求封装
| 资产 | 文件 | 职责 | 风险 |
|---|---|---|---|
| 请求客户端 | `utils/transport/request-client.js` | send():注入 Authorization、401 单航班重登、错误 toast、loading | 高 |
| 上传客户端 | `utils/transport/upload-client.js` | uploadAll():multipart+认证,全部 settle 后统一收 loading | 中 |
| 统一入口 | app.js `sendRequest/chooseImage/chooseDocument` | 页面统一通道 | 高 |
| 配置 | `utils/config.js` | baseUrl / baseImgUrl(已切正式域名) | 高 |

## 4. 登录 / Token / 身份
| 资产 | 文件 | 职责 | 风险 |
|---|---|---|---|
| 会话存储 | `utils/session/session-store.js` | 7 字段内存↔Storage 双写,绑定 globalData 同一对象 | 高 |
| 登录管理器 | `utils/session/session-manager.js` | 单航班 login/refresh,N 个并发 401 只换一次 token | 高 |
| RBAC 角色 | app.js `getUserRole/setUserRole` | 三级回退 role→storage→userType | 高 |
| 身份守卫 | `utils/roleGuard.js` | 角色能力快照/权限缓存 | 高 |
| 身份策略 | `utils/identity/*` | identity-policy | 高 |
| **刻意保留** | `debug_user_view`(shezhi.js/index.js)、`useDevUser()`(被 isDevEnv 网住) | 用户有意保留的调试开关 | — 勿删 |

## 5. 缓存 / 持久化 key
- 会话 7 key(session-store 管):user_type/user_id/authorization/open_id/avatar/nickname/role
- 业务 key:`selectedAddress`(×5)、`roam_reveals`、`roam_tiles`、`roam_sessions`、`role_permission_cache_v1`、`merchantApplyParams`、`member_hero_bg`、`has_inviter`、`logs`

## 6. 敏感能力 / 隐私
- `requiredBackgroundModes:["location"]` + `requiredPrivateInfos` 含 `startLocationUpdateBackground`(后台定位)
- 后台定位代码点:`pages/roam/index.js`、`pages/play/index.js`(**高风险审计项**)
- 定位工具:`utils/geo.js`、`utils/roam-geo.js`、`utils/location/*`
- 隐私授权:app.js `initPrivacyAuthorization`/`onNeedPrivacyAuthorization` → `pages/privacy/index`
- 权限说明:`scope.userLocation`/`scope.userFuzzyLocation` desc 已配

## 7. 定时器 / 监听器
- setInterval 命中 8 处(需审是否 onUnload 清理)
- console.log 10 / console.error 19(生产噪声审计项)

## 8. 环境 / 调试 / 配置
- `utils/config.js`(baseUrl/baseImgUrl 正式域名)
- `pages/club/utils/mockData.js`(仅 dev,被 isDevEnv 网住;2026-10-19 从主包 utils/ 移入 pages/club 分包)
- app.js:50 注释掉的 `siteBaseUrl: 'http://localhost:8080'` 死行(P3 候选)
- `project.config.json`/`project.private.config.json`:只读,不提交

## 9. 测试 / 发布门禁(现有,不编造)
- `scripts/preflight-release.js` (`npm run preflight:release`):发版前自检 SYS-S2
- `tests/unit/**/*.test.js` (`npm run test:unit`):176 用例
- `tests/lint/ui-integrity.js` (`npm run lint:ui`):UI 完整性(坏分享/死按钮/占位入口)
- `tests/automator/*`:login-smoke、merchant-ledger-settlement-smoke、dead-ui-regression
- `scripts/_verify_*.js`:大量 automator 自证脚本

## 10. 覆盖率(随 Loop 更新)
- 工程资产审计覆盖率:Preflight 完成结构盘点,深度审计随 Step 1 展开
- 关键流程验证覆盖率:待 Step 5

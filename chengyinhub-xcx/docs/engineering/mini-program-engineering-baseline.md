# 小程序工程基线 (mini-program-engineering-baseline)

> Loop Preflight 记录的"修改前"状态。后续每轮对比此基线,关键指标恶化 >10% 即本轮失败。

## 1. 自动化门禁基线(修改前)
| 检查 | 命令 | 结果 |
|---|---|---|
| 发版自检 | `node scripts/preflight-release.js` | ✅ 0 阻断(WARN 0 / INFO 4) |
| 单元测试 | `node --test tests/unit/**/*.test.js` | ✅ 176 pass / 0 fail |
| UI 完整性 | `node tests/lint/ui-integrity.js` | ❌ 1 error(坏分享)+ ⚠️ 9 warn |
| 空白/冲突 | `git diff --check` | ✅ clean |
| 开发者工具编译 | automator | ⏳ 待 Step 5(需真机/工具) |

## 2. 核心模块职责
- 启动:`app.js` onLaunch → applyTheme('dark') → 会话恢复 → firstLogin(单航班) → 灰度开关
- 请求链路:页面 `app.sendRequest` → `request-client.send` → 注入 Authorization → 401 单航班重登 → 错误 toast
- 登录/身份链路:`session-manager.login` → `/api/login/code` 换 JWT → `session-store` 落 7 字段 → RBAC role 单一真源
- 缓存策略:session-store 内存+Storage 双写同一 globalData 对象引用;空值不覆盖
- 错误处理:`getRequestErrorMessage` 归一化(认证失败/SQL 错误→友好文案);`shouldAutoToastRequestError` 控制自动 toast

## 3. 已知刻意保留边界(勿当 bug)
- `debug_user_view`(用户/商户视角切换):用户有意保留,上线前移除
- `useDevUser()`:被 `isDevEnv()`(envVersion==='develop')网住,正式版不产假用户 — **正确状态**
- `baseImgUrl` 正式域名:M0-9 测试域名清零已完成,勿回退 test 域名

## 4. 高风险边界(改动前必须用户确认 · 文档 §5)
- 登录/Token/JWT/续期(session-manager)
- RBAC/角色/身份切换(roleGuard/identity)
- 隐私权限声明 / 后台定位(app.json requiredBackgroundModes + roam/play)
- 分包结构 / 主包入口(app.json)
- 网络请求基础封装(request-client/upload-client)
- 生产 API 地址 / 全局缓存格式 / 持久化结构

## 5. 性能基线(待 Step 5 采样)
- 主包 34 页,分包 11;`lazyCodeLoading: requiredComponents` 已开
- 冷/热启动、首屏请求数:待 automator/工具采样(同环境 5 次取中位数)

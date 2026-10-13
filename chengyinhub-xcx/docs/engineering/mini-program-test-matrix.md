# 小程序验证矩阵 (mini-program-test-matrix)

> 关键流程验证清单。Step 5 运行验证填入实际结果与证据位置。

| 流程 | 前置 | 身份 | 数据要求 | 步骤 | 预期 | 实际 | 自动/手动 | 最后轮 | 证据 |
|---|---|---|---|---|---|---|---|---|---|
| 冷启动 | 清缓存 | 游客 | — | 启动→onLaunch | applyTheme dark、会话恢复、firstLogin resolve、isAppReady=true | — | automator | — | — |
| 登录恢复 | 有会话缓存 | 玩家 | 7 字段 | 启动 | restore 回填 globalData,无需重登 | — | unit(session) | L0 | test:unit |
| Token 过期重登 | authorization 失效 | 玩家 | — | 触发 401 | 单航班 refresh,N 并发只换一次 | — | unit(session-manager) | L0 | test:unit |
| 冷启动缓存短路 | 有缓存会话 | 玩家 | openid+auth | 冷启动 | firstLogin→ensureSession 命中缓存,不发网络登录 | ✅ ensureSession 单测绿;过期 token 由首个 401 兜底(复审确认) | unit+复审 | L2 | session-manager.test.js:58 |
| 弱网冷启动不登出 | 缓存会话+网络失败 | 玩家 | — | 冷启动 exchangeCode networkFail | 保留缓存会话(仅 noData 才清) | ✅ 代码级+复审确认 | 复审 | L2 | app.js firstLogin |
| 401 重登 complete 单次 | 401 触发重登 | — | — | 静默重登窗口 | param.complete 只触发一次(最终响应),fail 先于 complete | ✅ 单测(A2-1/A2-3) | unit | L2 | request-client.test.js |
| 后台定位离页停采 | play 离页 | 玩家 | — | onHide | offLocationChange+stopLocationUpdate;onShow 经 loadData→initLocation 恢复 | ✅ 代码级恢复链+onHide 不抛;**GPS 实测需真机** | 需真机 | L2 | play/index.js |
| 角色切换 | debug_user_view | 玩家/商户 | — | 切视角 | getUserRole 三级回退正确 | — | unit(roleGuard) | L0 | test:unit |
| 请求认证注入 | 已登录 | — | — | sendRequest | 自动带 Authorization | — | unit(request-client) | L0 | test:unit |
| 上传部分失败 | — | — | 多文件 | uploadAll | 全部 settle 后收 loading,不卡死 | — | unit(upload-client) | L0 | test:unit |
| 分享路径 | — | — | — | 各页分享 | path 均已注册 | ❌ infomationdetail 坏 | lint:ui | L0 | lint:ui |
| 后台定位 | roam/play | 玩家 | GPS 序列 | 进入游玩 | 前台按需采集+用途说明;后台仅授权后 | — | 需真机 | — | — |
| 弱网/超时 | 限速 | — | — | 请求超时 | 友好 toast,不静默 | — | 手动 | — | — |

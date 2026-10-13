# 城瘾小程序弹窗视觉交接说明（2026-08-04）

## 0. 交接对象与当前真相

本说明面向下一次 Codex/编码助手 会话。先读本文，再操作 worktree；不要依据旧会话口头结论恢复状态。

- 当前 worktree：/Users/arlan/orca/workspaces/chengyin/ui-final-writer-0804
- 小程序目录：/Users/arlan/orca/workspaces/chengyin/ui-final-writer-0804/chengyinhub-xcx
- 分支：liboyang42-cpu/ui-final-writer-0804
- 已 push 的最新 commit：a60c1f6f03be4aee05b384d17c804ceb6ae3a3f7
- 已 push 内容：玩家弹窗宽度调整、玩家遮罩从 .56 调为 .30
- GitHub remote：github，不是 origin
- 已确认远端 branch 指向 a60c1f6f03be4aee05b384d17c804ceb6ae3a3f7

### 当前必须保留的未提交改动

在写本文时，worktree 另有 3 个未提交 WXSS 改动。它们不是 a60c1f6 的一部分，也没有 push；不要 reset --hard、checkout -- 或清理它们。

1. chengyinhub-xcx/components/cy/scene-roam-history/index.wxss
   - 移除内容层重复的 background: var(--cy-color-bg-surface)。
   - 原因：外层 scene-sheet 已有 rgba(28,28,36,.90)，重复刷一次会叠成约 99% 不透明。
2. chengyinhub-xcx/components/cy/scene-route-content/index.wxss
   - 移除玩家内容层重复背景。
3. chengyinhub-xcx/components/cy/scene-share-invite/index.wxss
   - 移除玩家分享邀请内容层重复背景。

这 3 个文件需要下一会话重新跑测试、重新截图后再决定是否 commit/push。当前已保存的运行时 PNG 是在这 3 个改动出现前取得的，不能证明它们改动后的视觉结果。

## 1. 已确定的设计规则

Figma 节点：

https://www.figma.com/design/ku2oaN9ag1XUMC9lKNnrcr/Untitled?node-id=3902-13780&m=dev

节点 3902:13780 是玩家深色历史弹窗的底层容器，不是完整历史内容页。Figma 设计上下文实际包含：

~~~css
border-radius: 24px 24px 0 0;
border-top: 0.714px solid rgba(255,255,255,0.20);
border-right: 0.714px solid rgba(255,255,255,0.20);
border-left: 0.714px solid rgba(255,255,255,0.20);
background: rgba(28,28,36,0.90);
box-shadow: 0 10px 20.4px 3px rgba(0,0,0,0.25) inset;
~~~

当前产品规则：

- 玩家弹窗：深色。
- 商户弹窗：浅色。
- 当前 Figma 节点的导出代码没有 backdrop-filter 或具体 Background blur 数值。
- 不得自行猜 20px、20rpx 等 blur 值；若用户要求真正的背景模糊，先取得 Figma Effects 的准确数值。
- 不得用 Figma 原图代替运行时截图。

## 2. 当前实现位置

### 统一弹窗外壳

- chengyinhub-xcx/components/cy/scene-sheet/index.wxml
- chengyinhub-xcx/components/cy/scene-sheet/index.wxss
- chengyinhub-xcx/style/tokens.wxss

核心 token：

~~~css
--cy-comp-sheet-width: 392.975px;
--cy-comp-sheet-full-height: 579.135px;
--cy-comp-sheet-full-max-height: 579.145px;
--cy-comp-sheet-border-width: 0.714px;
--cy-comp-sheet-player-bg: rgba(28,28,36,.90);
--cy-comp-sheet-player-border: rgba(255,255,255,.20);
--cy-comp-sheet-player-inset-shadow: inset 0 10px 20.4px 3px rgba(0,0,0,.25);
~~~

a60c1f6 之后外壳使用 width: 100%; max-width: var(--cy-comp-sheet-width)，保证窄设备不裁切，同时不超过 Figma 宽度。

### 漫游历史入口与内容

- 入口：pages/roam/index.wxml，sceneCurrent.id === roam-history
- 历史内容：components/cy/scene-roam-history/index.wxml
- 历史逻辑：components/cy/scene-roam-history/index.js
- 历史样式：components/cy/scene-roam-history/index.wxss

内容组件使用真实本地 roam_sessions 数据；无记录时显示真实空态，不注入身份、数据或接口响应。

## 3. 已取得的视觉证据

- 正确运行时手机截图：/tmp/figma-history-runtime-0804-phone.png
- 完整 DevTools 截图：/tmp/figma-history-runtime-0804-correct.png
- route：pages/roam/index
- scene：roam-history
- state：自然运行的空态
- 截图右侧手机模拟器显示“漫游历史 / 状态 / 场景 / 排序 / 还没有漫游记录”。
- /tmp/figma-history-runtime-0804.png 是错误的商户页截图，禁止作为证据。

## 4. 已完成验证

在 a60c1f6 基线下：

- node --test tests/unit/scene-sheet-contract.test.js tests/unit/scene-content-contract.test.js
  - 22 passed，0 failed。
- node tests/lint/ui-integrity.js --warn-as-error
  - 0 error，0 warn。
- pre-commit hook：小程序 JS/JSON syntax check passed。
- WeChat DevTools 能打开正确的 pages/roam/index，并通过真实 scene 入口显示历史弹窗。

## 5. 已知环境问题

DevTools Console 有与本弹窗无关的环境报错/警告：

- https://www.chengyinhub.com 不在开发者工具 request 合法域名列表中。
- reportRealtimeAction:fail not support。
- 部分旧页面 selector 和 scroll-view enable-flex 警告。

这些不能被改写成“弹窗代码失败”，也不能通过伪造接口数据掩盖。网络阻断时只保留自然空态证据。

## 6. 下一会话执行顺序

1. git status --short --branch，确认 3 个未提交 WXSS 改动仍在。
2. 读取并审查这 3 个 diff，不要丢弃。
3. 重新运行两个合同测试和 UI lint。
4. 用正确 worktree 打开 DevTools；不要误看 /Users/arlan/Downloads/chengyin 主目录。
5. 在 pages/roam/index 打开 roam-history，重新取得改动后的手机 PNG。
6. 对照 Figma 节点只验容器：宽度/高度、三侧边框、90% 背景、圆角、inner shadow。
7. 如果用户继续要求 Glass blur，先取得准确 Figma Effects 数值，再改代码；不要猜值。
8. 3 个未提交文件通过测试和截图复核后，单独 commit，再 push 到 github 分支。
9. 不自动 merge、deploy、微信上传或删除 worktree。

## 7. 恢复命令

~~~bash
cd /Users/arlan/orca/workspaces/chengyin/ui-final-writer-0804
git status --short --branch
git log -2 --oneline --decorate
git diff --check

cd chengyinhub-xcx
node --test tests/unit/scene-sheet-contract.test.js tests/unit/scene-content-contract.test.js
node tests/lint/ui-integrity.js --warn-as-error
~~~

打开正确小程序项目：

~~~bash
/Applications/wechatwebdevtools.app/Contents/MacOS/cli open \
  --project /Users/arlan/orca/workspaces/chengyin/ui-final-writer-0804/chengyinhub-xcx
~~~

## 8. Git 边界

- 已 push：a60c1f6f03be4aee05b384d17c804ceb6ae3a3f7。
- 未 merge、未 deploy、未微信上传。
- remote 使用 github，不要 push origin。
- 不得使用 git reset --hard、git checkout -- 或删除当前未提交改动。

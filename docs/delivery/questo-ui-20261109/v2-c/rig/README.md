# v2-c 逐帧对照 rig

对照对象:原型 `proto/motion-v2/index.html`(分支 `claude/questo-ui-brief-20261109`)与小程序真实页面代码(走查 rig `claude/cloud-walk-20261101-rig` 的 mpweb 运行时,`setData` 注入状态,与 x-design1 同一做法;容器没有 dockerd,后端用返回通用 `{code:200}` 的兜底后端)。

```
# 1 走查 rig(本地 worktree,不改它的分支)
git worktree add /tmp/rig origin/claude/cloud-walk-20261101-rig
RIG_HOME=/tmp/righome CAND=<仓库根> bash /tmp/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/build.sh
#    本次对 rig 运行时只改了一处(仅本机,未提交):this.animate 桩原来丢掉关键帧的 ease,改成映射到 WAAPI easing
node /tmp/rig/.../mpweb/server.mjs &  node ../../x-design1/rig/fake-backend.mjs &
# 2 原型帧 / 小程序帧(同一时间轴,真实时钟截屏;各帧偏差见 compare/skew-*.json,多数 ≤ 5ms,m6 飞层前几帧 ≤ 40ms)
node proto-frames.mjs <proto.html> <outDir> <m> <look> 0,300,...
node rig-frames.mjs <outDir> <m> <look> 0,300,...
python3 compose.py <protoDir> <rigDir> <outDir> <m> <look>     # 左原型 | 右小程序
```

- `proto-index.html`:原型原文。`proto-index.patched.html`:仅一处改动——时刻 6 的飞层原本 `appendChild` 到 `.phone`(不在 `.m6` 作用域内,样式不生效、飞层不可见),改成挂到 `.m6` 里,才能看到原型要表达的「封面从卡片长成头图」。时刻 4/5 用原文。
- m4 = 点支付为 0ms,1100ms 到账(原型同);m5 = 点发送为 0ms;m6 = 点卡片为 0ms;m6back = 详情落定后点返回为 0ms。

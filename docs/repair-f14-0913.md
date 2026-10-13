# F14 修复记录：故事流音频有播放按钮却无动作

日期：2026-09-13 · 范围：小程序发布页故事流音频块的预听（仅前端，复用现有音频管理）
来源：`商家漫游与内容制作_第三轮.md` · P2 / F14（UI59–62）

## 问题

作者在 `990052` 编辑器打开故事流，滚到音频块，点击播放图标（UI61 显示 ▶ 及
`audit-tone.mp3`），UI62 无任何反馈。章节旁白的 `onPreviewChapterAudio` 是另一条路，
不能抵消这个缺口。

## 根因

`pages/publish/fabu/index.wxml` 的已上传音频块只有一个 `<cy-icon name="play-filled">`，
外层只有 `bindlongpress="selectStoryBlock"`（长按选中），**没有 tap 绑定**；
`index.js` 里只有 `uploadStoryAudio`（选文件）与 `_fillStoryAudioMeta`（探时长），
没有任何播放处理。图标长得像按钮，点了却不发生任何事。

## 修复（最小实现，复用现有音频管理）

不新建音频框架：故事流音频块与章节旁白**共用同一个 `InnerAudioContext`（`_previewAudio`）**，
因此两者天然互斥——谁后起播谁把对方顶掉，不会两段一起响。

- `index.js` 新增 `onPreviewStoryAudio(e)`：按 `blockKey` 找块 → 空 url 直接返回（空态走上传）；
  同一块再点 = 暂停/继续；换块先 `destroyPreviewAudio()` 再建；
  `onPlay/onPause/onStop/onEnded` 回写 `storyAudioPlaying`/`storyAudioKey`；
  `onError` 复位并 `cyToast('预听失败')`。
- 复用与删除都按**来源身份** `this._previewSource`（`{kind:'chapter'}` / `{kind:'story',key}`）
  判断，不再只比 URL；身份在**建播放器时**登记、在 `destroyPreviewAudio()` 清理。
- 所有音频回调先判 `that._previewAudio !== audio` 弃权，旧播放器被换掉/销毁后迟到的回调不复活状态。
- `destroyPreviewAudio()` 扩展为同时复位 `storyAudioPlaying`/`storyAudioKey` 并清 `_previewSource`（唯一拆解路径）。
- `closeStoryEditor()`、`openStoryEditor()`（换章）、`removeStoryMedia()`（删到当前块）
  都调 `destroyPreviewAudio()`，保证换块/关编辑器/删块即停、销毁释放。
- `index.wxml`：给音频行挂 `catchtap="onPreviewStoryAudio"` + `aria-role/aria-label`
  （`预听音频`/`暂停音频`），播放中给播放圆加 `is-playing`。
- `index.wxss`：`.story-media-block__audio-play.is-playing` 复用 `--cy-color-brand` /
  `--cy-color-text-inverse` 表达“正在播”，不新造皮肤、不改全局音频规则。

## P2 复核补修（双轴独立审查提出）

### P2-1：缓冲期删除块不停播 + 迟到回调复活

**根因**：`storyAudioKey` 直到 `onPlay` 才赋值，而 `removeStoryMedia` 按它匹配。
播放器已 `play()` 但还没 `onPlay`（网络缓冲）时删块，匹配不到 ⇒ 不 destroy，缓冲完成后仍会响。
且旧播放器的 `onPlay` 迟到回调无人拦，会把 `storyAudioPlaying/storyAudioKey` 置回。

**修复**：来源身份 `_previewSource = {kind:'story',key}` 在**建播放器时**登记；
`removeStoryMedia` 改按 `_previewSource` 匹配并 destroy；回调加 `_previewAudio !== audio` 弃权闸。

### P2-2：同 URL 时故事块与旁白互相沿用监听器

**根因**：`onPreviewChapterAudio` 只按 `_previewAudio.src === url` 复用。若故事块与旁白同址，
故事→旁白会沿用故事块的监听器 ⇒ `audioPreviewPlaying` 永远 false、点「预听/暂停」pause 不了。

**修复**：复用判据改为来源身份（chapter / story+key），同 URL 不同来源一律 destroy 重建，
各自监听器只写自己的状态位；两个方向（故事→旁白、旁白→故事）都各能暂停。

### 总控追加复审：同来源替换 URL

第二次 Standards 复审发现只比较来源会遗漏文件替换：同章节或同故事块的 URL 从 A 换到 B 后，仍复用 A 播放器。总控接管冻结候选，在根隔离工作区新增两条行为负控，原14项通过、两项新测试失败（`/tmp/cy-f14-url-red.log`）。修复为来源身份与当前 URL 同时匹配才复用，其余情况销毁重建。修复后16项音频测试与相关回归共71/71通过，无失败或跳过（`/tmp/cy-f14-final-green.log`）。旧文件迟到回调及新文件正常暂停同时验证。

最终代码与新增测试所有权已移到 `product-repair-0913`；DeepSeek 工作区冻结候选不含这两行最终修复。下文14/69为前一轮交付记录，最终以本节16/71为准。

## 改动文件

| 文件 | 改动 |
|---|---|
| `chengyinhub-xcx/pages/publish/fabu/index.js` | 新增 `onPreviewStoryAudio`；来源身份 `_previewSource` 登记/复用/清理；回调弃权闸；`destroyPreviewAudio` 复位故事流状态；关闭/换章/删块停止 |
| `chengyinhub-xcx/pages/publish/fabu/index.wxml` | 音频行绑定预听 + 可读名称 + 播放态 class |
| `chengyinhub-xcx/pages/publish/fabu/index.wxss` | `.is-playing` 播放态（复用 token） |
| `chengyinhub-xcx/tests/unit/fabu-story-audio-preview-contract.test.js` | 9 条基础契约 + 5 条 P2 回归（缓冲期删除/迟到回调/同 URL 双向切换） |

## 验证

### 负控（先 RED）

```
cd chengyinhub-xcx
node --test tests/unit/fabu-story-audio-preview-contract.test.js
```

旧码 9/9 判红，指纹明确：`音频块没有绑定播放处理`、
`TypeError: page.onPreviewStoryAudio is not a function`。

### 修复后（GREEN）

```
node --test tests/unit/fabu-story-audio-preview-contract.test.js
# tests 14 · pass 14 · fail 0   （9 条基础 + 5 条 P2 回归）
```

覆盖：真起播并回写状态；同块暂停/续播不重建播放器；失败 toast 且复位；
换块销毁上一块；关编辑器停；删到正在播的块停；与章节旁白双向互斥；空 url 块不建播放器；
**缓冲期（未 onPlay）删块即 destroy；迟到 onPlay 不复活；同 URL 故事↔旁白各自换监听器且能暂停**。

P2 回归 RED 指纹（修复前 3 条判红）：`缓冲期删除也必须 destroy`、
`迟到 onPlay` 后状态被置真、`同 URL 也不能沿用故事块的监听器`。

### 相关测试与 lint

```
node --test tests/unit/fabu-story-audio-preview-contract.test.js \
          tests/unit/publish-story-editor-hit-targets-contract.test.js \
          tests/unit/publish-identity-and-audio-placeholder-contract.test.js \
          tests/unit/pro-editor-fabu-flow.test.js \
          tests/unit/publish-editor-return-flow.test.js \
          tests/unit/all-page-back-stack-contract.test.js \
          tests/unit/wxml-balanced-tags-contract.test.js
# tests 69 · pass 69 · fail 0

node scripts/a11y-tap-target-lint.js    # 过小命中区 0 处(音频行有 min-height,不触 44px 红线)
npm run lint:scope                       # JS-SCOPE PASS: 495 个生产 JS
```

### 历史交付门禁（DeepSeek 首次交付，后续总控验证见末节）

- `node scripts/uiaudit/build-action-ledger.js --check` → **FAIL**。
  原因：新增了真实控件「预听音频」（绑定按钮的必然结果），`controlCount 3963 → 3964`；
  重建 diff `+554/-477`，漂移的 `staticSource` 分两块：
  `pages/publish/fabu/index.js` 381 条（本次 F14：新控件 + 行号漂移）、
  `subpackageRoam/nearby/index.js` 62 条（已由总控 commit `a945b40de` 的 F08，其台账待集成时一并重建）。
  **未改 `action-ledger.json`。**
- `npm run lint:ui` → **FAIL，22 条全部落在 `pages/publish/fabu/index.js`**
  （U4-DYNAMIC 基线行号漂移，语义无新增违规），无其它文件命中。
  **未改 `tests/lint/ui-integrity-baseline.js`。**
- 未为规避行号而压缩无关代码（总控明确要求）。

## 复现步骤与预期（供总控原生截图验收）

1. 打开 `990052` 编辑器 → 打开某一章的故事流，滚到已上传的音频块。
2. 点音频行（或播放圆）：应真的起播，播放圆转主题强调底（`is-playing`），
   再点同块暂停，再点续播。
3. 播放中切到另一音频块 / 关闭编辑器 / 删除该块：音频立即停止，无后台残留。
4. 章节旁白与故事流音频交替点击：只响一个（互斥）。
5. 用一个不可解码的音频地址：给「预听失败」toast，不静默。
6. P2-1：点一块后**立刻**删它（不等起播，模拟网络缓冲）→ 不能删完还响。
7. P2-2：把章节旁白与某故事块设成**同一个音频地址**，交替点两者 →
   各自都能播、都能暂停，状态位不错位。

## 未验证项

- 未真机/未连生产：结论来自行为单测 + 静态契约；真实可听、真机权限与 iOS/Android
  静音开关表现需总控截图/真机验收（本任务不改真机权限与全局音频规则）。
- 总控已在整合树更新两份登记：UI-GATE-0、门禁负控自检及 action-ledger check 均通过。11 个动态 setData 的行号迁移逐项确认相邻代码不变，理由和数量54未变，只更新 key/digest。日志 `/tmp/cy-f14-ui-green.log`、`/tmp/cy-f14-ui-selftest.log`、`/tmp/cy-wave2-ledger-check.log`。
- 台账仍有0条运行验收回执，新音频明确为 `pending / real-device-required`。巨大生成差异包含F01/F02八个查询URL传播，760处targets去除该八URL后与旧数据相同；169处evidence变化仅staticSource，fabu因新增按钮95增94删。独立Standards结构复核通过，未伪造播放成功。
- 未 push / 未开 PR / 未合并；未动 F20/F08 文件、后端、上传链路。

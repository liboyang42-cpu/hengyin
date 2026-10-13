# 下一站节点映射 Design QA

## 对照真源与状态

- source visual truth: `/Users/arlan/.codex/generated_images/01a02e0b-6fb7-7bb1-9dd6-fc08820e975a/exec-0eec994c-9b0c-44a3-b4ef-e5b73f91aa44.png`
- implementation screenshot: `/Users/arlan/orca/workspaces/chengyin/route-m5-integration/chengyinhub-xcx/artifacts/route-flow-audit/final-polished/creator-next-station-sheet.png`
- full-view comparison: `/Users/arlan/orca/workspaces/chengyin/route-m5-integration/chengyinhub-xcx/artifacts/route-flow-audit/final-polished/reference-vs-actual.png`
- state: 城市定向第 1 章故事流中，打开“旧城门抉择”的“下一站”映射弹层；两条模板结果分别对应历史支线和艺术支线，其他结果继续故事流。
- viewport: WeChat DevTools `iPhone 12/13 (Pro)`，390 × 844 CSS px，light theme，系统字号 16。
- pixels and density: source 853 × 1844 px；implementation 624 × 1352 px，截图相对 CSS 视口约 1.6×。对照时把 implementation 等比归一到 851 × 1844 px，再与 853 × 1844 px source 并排，不用设备像素比 3 直接判断尺寸。

## Full-view comparison

- 信息架构一致：原故事流仍留在遮罩后方；路线编辑只作为节点内“下一站”轻量弹层，没有恢复全局路线图、起点/终点或技术参数。
- 层级一致：节点名为弱提示，“下一站”为主标题；结果与目标按左右两列阅读；“其他结果”作为同一卡片内的默认规则。
- 实现有意沿用项目既有 `cy-sheet` 顶部关闭/完成动作，没有照搬参考图底部大按钮，避免同一产品出现两套弹层导航。该差异不改变主任务和信息密度。

## Focused region comparison

映射卡片在 1704 × 1844 的并排原图中已能清楚辨认字号、分隔线、箭头、截断和左右对齐，因此没有另做放大裁切。重点核对结果如下：

- typography: 系统中文字体、标题/正文/辅助文字三层权重稳定；“寻找历史”“历史支线”等真实文案无裁切、无异常换行。
- spacing and layout rhythm: 两行显式映射和一行默认规则均保持 44 CSS px 以上触达高度；左右留白、卡片圆角、行分隔和底部安全区稳定。
- colors and tokens: 只使用现有黑、白、灰语义 token；遮罩、白色 sheet、浅灰页面底和细分隔线与现有节点编辑器一致。
- image quality: 此状态没有产品图片或装饰插画；背景节点缩略位继续使用项目现有图标组件，没有新增 CSS/内联 SVG 伪造资产。
- copy and content: 左侧是模板结果，右侧是目标地点，语义不再重复；默认规则明确写成“其他结果 / 没有单独对应时 / 继续故事流”。
- icons and affordances: 关闭、完成和右箭头可见；picker 保留整行可点击目标，文字过长时只对目标名称做 ellipsis。
- accessibility/responsiveness: 390 px 宽下无重叠和裁切；行高满足移动端触达；控件已有 aria label。长目标名称通过 `min-width: 0` 与 ellipsis 收敛，不挤压左侧结果。

## Comparison history

### Iteration 1 — blocked

- [P1] 目标地点使用通用 `node-pill` 后，picker 宽度形成循环约束，实际截图只显示“历史支”“艺术支”，核心映射文字被裁切。
- [P2] 节点名与模板名相同时仍拼接，标题行显示“旧城门抉择 · 旧城门抉择”。

Fixes:

- 为 picker 增加独立的弹性宽度和 `min-width: 0`，改成直接右对齐的目标文字与箭头，移除不必要的灰色药丸。
- 相同的节点名和模板名只显示一次。
- 验收数据使用“寻找历史/寻找艺术 → 历史支线/艺术支线”，让模板结果和地点目标的角色清楚可见。

Post-fix evidence:

- `/Users/arlan/orca/workspaces/chengyin/route-m5-integration/chengyinhub-xcx/artifacts/route-flow-audit/final-polished/creator-next-station-sheet.png`
- `/Users/arlan/orca/workspaces/chengyin/route-m5-integration/chengyinhub-xcx/artifacts/route-flow-audit/final-polished/reference-vs-actual.png`

## Findings

没有剩余可执行的 P0/P1/P2 视觉问题。

P3 acceptable difference: 参考图使用底部大“完成”，实现沿用城瘾现有节点弹层的顶部“关闭 + 完成”。这是产品内一致性选择，不需要为单一页面引入第二套动作区。

## Interaction and runtime evidence

- primary interactions tested: 故事流展开、节点详情进入、下一站弹层打开、picker 选择、完成返回、保存、HTTP `/api/topic/update` 回读、重新进入 `/api/topic/edit-detail` 恢复。
- console errors checked: 0。
- source-node local key and persisted database id were tested separately，避免已保存节点摘要丢失。

final result: passed

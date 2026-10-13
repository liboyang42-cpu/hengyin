# 城瘾小程序界面规范

本文件只做入口索引，不复制第二套 token。视觉变量唯一真源是
[`style/tokens.wxss`](../style/tokens.wxss)，字号与间距映射见
[`style/TOKEN-MAPPING.md`](../style/TOKEN-MAPPING.md)，改页自查见
[`docs/DS改页自查清单.md`](./DS改页自查清单.md)。

## 1. 两套主题

- 玩家域默认使用深色 `theme-dark`：黑色页面底、中性灰层级、白色主操作。
- 商家域使用浅色 `theme-merchant`：中性浅底，不把玩家域紫色或深色组件直接搬入。
- 玩法允许使用 `--cy-color-playkit-*` 表达玩法身份；状态仍只使用 success、warning、danger、info。
- 页面和组件只消费语义 token，不直接引用 primitive 色值。

## 2. 层级与组件

- 普通二级页使用 `cy-nav-bar` + `cy-page-title`，页面只出现一个主标题。
- 页面内容优先使用现有 `cy-*` 组件；卡片、按钮、空态、错误态、弹层不得另造同义实现。
- 玩家卡片使用 `elevated`，卡中卡和输入使用 `raised`；需要靠描边才能分层时，先检查表面档位是否选错。
- 主操作每屏只保留一个视觉主按钮；危险操作必须二次确认且说明后果。

## 3. 状态与文案

每个异步页面都必须能独立表达 loading、empty、error、ready；写操作还要表达 submitting、unknown、confirmed、failed。不得吞错、伪造成功或用空白替代失败。

用户可见文案只说明“发生了什么、现在怎么办”。禁止出现“服务端、接口、回执、幂等、数据库、字段、mock、fixture、state”等工程词；剧情世界观内有意使用的术语不受此规则影响。

## 4. 可用性底线

- 点击目标、焦点顺序、文字对比度和动态字号遵循现有组件约束。
- 图片失败要保留缩略图位置和原图重试，不得把本机临时路径提交给业务接口。
- 长文案、空值、极长名称、网络中断、重复点击和账号切换都必须有可恢复结果。
- 地图、相机、定位、手机号、支付等设备能力必须在真机验收，开发者工具截图不能替代。

## 5. 验收入口

- 页面框架规则：[`docs/chrome-spec.md`](./chrome-spec.md)
- 新增玩法段（`pages/publish/temp` 配置页）：[`docs/玩法段配置页_设计手册.md`](./玩法段配置页_设计手册.md)
- 全量复查流程：[`docs/UI全量复查方法.md`](./UI全量复查方法.md)
- 自动门禁：`npm run test:unit`、`bash ../ci/xcx-check.sh`

# 双端原生设计源

本目录服务于当前公共仓库的 `chengyinhub-xcx` 原生小程序及独立 SwiftUI App。保留各端原生实现、系统语义和既有权限边界。

`baseline.json` 记录已核实的 App 仓库、main ref 和批准提交；生成器要求 App HEAD 为该提交的后代。更新基线前须重新核实已合入 ref。`--ios-root` 必须指向核实过的 App checkout；本机绝对路径不属于共享配置。

`design-map.json` 映射既有 tokens 的17个尺寸，以及原生语义颜色、Dynamic Type 与强调色。375pt 是参考画布，rpx 到 pt 不按实际设备宽度换算。App 的 `App/QuestifyPalette.swift` 由既有生成器生成；只读接入不重新生成或改变数值。

`page-pairs.json` 当前登记17组真实源码对应；登记和源码存在不代表交互、视觉、设备或业务已经验收。新增页面需核实真实对应，不伪造另一端改动。工作树检查需列出本任务全部 UI 文件；确实无须修改的对侧用 `--reviewed-mini-path` / `--reviewed-ios-path` 及 `--review-note` 说明。

在公共仓库根目录运行，`APP_ROOT` 由使用者设置为核实过的 App checkout：

```sh
python3 -B -m unittest discover -s scripts/tests -p test_native_design.py
python3 -B scripts/native-design.py check --ios-root "$APP_ROOT"
python3 -B scripts/native-design.py check --ios-root "$APP_ROOT" \
  --ios-path App/MerchantBusinessViews.swift \
  --ios-path Core/MerchantBusinessQuery.swift \
  --reviewed-mini-path pages/merchant/ledger/index.js \
  --reviewed-mini-path pages/merchant/ledger/index.wxml \
  --reviewed-mini-path pages/merchant/ledger/order-detail/index.js \
  --reviewed-mini-path pages/merchant/ledger/order-detail/index.wxml \
  --reviewed-mini-path utils/merchant-access-policy.js \
  --review-note 'Mini already separates masked verification records from finance-only filters and details.'
```

不传页面清单时只检查变量与映射存在性，输出 `Page change coverage NOT CHECKED`。工作树配对检查不能替代暂存区检查。

## 跟踪来源与本地钩子

安装前先审查并跟踪本目录元数据、已有生成器、安装器及其测试；小程序 `style/tokens.wxss` 已有独立跟踪源。`--staged-side` 对基线、设计映射、tokens 使用 `git show :路径`；App侧还从其索引读取生成文件。未跟踪文件或工作树修补不能冒充待提交内容。页面组映射目前由工作树读取，仍须跟踪并在页面改动时完成明确清单检查。

在真实公共源码已被跟踪、普通检查和测试通过之后，才由集成负责人运行：

```sh
python3 -B scripts/install-native-design-hooks.py --ios-root "$APP_ROOT"
python3 -B scripts/install-native-design-hooks.py --ios-root "$APP_ROOT" --write
```

安装器串接并保留两端已有 shell pre-commit 内容，不替换现有安全检查。Git worktree 共用 hooks 目录时会写入当前源码根的精确匹配入口；其他 worktree 的检查覆盖须由其负责人核对，不能把一次本地安装称为跨仓库 CI 或全 worktree 覆盖。本地钩子不构建后端、不连接业务API、不发布。

# x-fix1 结果：WXS 编译阻断修复（ISSUE B-1）

分支：`launch/questo-20261110`（直接在远端最新提交上提交；没有强推，没有合 master，也没有动 #779）。

## 1. B-1 修复（已先行推送 32069fa1）

- `chengyinhub-xcx/utils/wxs/gesture.wxs:115`：`wh: Number(ds.wh) || 0` 改为 `wh: (ds.wh * 1) || 0`。这是验收单用 DevTools `wcc -d` 实测能通过的写法（改后 465 个 wxml 全部编过）。语义一致：`null`、`''` 得 0；`undefined`、非数字串得 NaN，再兜底成 0；数字串照常转数。

## 2. 全量 .wxs 审计

扫了 `chengyinhub-xcx` 下全部 14 个 `.wxs` 文件和 4 段 WXML 内联 `<wxs>`，审计结论：

| 位置 | 原写法 | 处理 |
|---|---|---|
| `utils/wxs/gesture.wxs:37` | `String(levels).split(',')` | 改为 `('' + levels).split(',')` |
| `utils/wxs/gesture.wxs:95` | `return Number(fallback) \|\| 0` | 改为 `return (fallback * 1) \|\| 0`。验收单说这行单独能编过，但 `utils/wxs/money.wxs` 的注释写着 Number() 会报 Unexpected token，按同一口径收掉，不留隐患 |
| 其余 13 个文件和内联 wxs | — | 没有 let/const、箭头函数、模板串、展开、简写属性、默认参数、for..of、class、new、正则字面量、`this`。`getRegExp` 的 flags 只有 `g`/`i`。`parseFloat` 是 WXS 全局函数，可用 |

## 3. 防回归闸（新增）

- `chengyinhub-xcx/scripts/wxs-syntax-gate.js`：用 Node 自带的 Acorn（`--expose-internals`，与 `js-scope-lint` 同样做法，不加依赖）。按 `ecmaVersion: 5` 解析每个 `.wxs` 和 WXML 内联 wxs。再按 AST 禁掉以下写法：
  - 把 `Number/String/Boolean/Object/Array/Function/Symbol/Date/RegExp/Error/Promise/Map/Set/isNaN/eval` 当函数调用
  - `new`
  - 正则字面量
  - `getRegExp` 的 flags 不是 g/i/m 字面量
  - `this`、`window`、`wx`、`getApp`
  - `require` 引的不是 `.wxs` 字面量
- 夹具 `chengyinhub-xcx/tests/fixtures/wxs-syntax-gate/*.wxs.fixture`：13 个 bad 夹具，覆盖 B-1 原样（对象字面量里的 `Number(ds.wh)`）、String()、箭头函数、const、模板串、简写属性、展开、默认参数、for..of、正则字面量、非法 flag、new Date、isNaN；1 个 ok 夹具。夹具用 `.wxs.fixture` 后缀而不用 `.wxs`，以免被 DevTools 当源码编译（`tests/` 也在 packOptions.ignore 里）。
- 单测 `chengyinhub-xcx/tests/unit/wxs-syntax-gate.test.js`（3 条）：全仓扫描 PASS；selftest PASS；B-1 原样被拦、改成 `(ds.wh * 1)` 后放行。已核对：拿修复前的 gesture.wxs 跑闸，报出 37、95、115 三处。CI 现有的 `tests/unit/**/*.test.js` 分片会自动带上这个单测，所以没有改 `ci/` 或 `.github/workflows/ci.yml`。

## 4. 真 wcc 编译

仓库里没有能在 Linux 上跑微信 wcc 的工具：`git grep wcc` 只命中二进制素材，本机也找不到 wcc 可执行文件。wcc 只随 Windows/macOS 版微信开发者工具分发。本线因此没有跑真编译。本闸是静态的 ES5 子集加 WXS 禁区检查，不能替代真机编译。上线前仍需在 Mac 的 DevTools 上编译一次（或用 `wcc -d`），确认 465 个 wxml 全部通过。

## 5. 门禁

| 检查 | 结果 |
|---|---|
| `node --test "tests/unit/**/*.test.js"`（chengyinhub-xcx） | 17296/17296 通过 |
| `node tests/lint/ui-integrity.js --warn-as-error` | UI-GATE-0:PASS |
| `build-action-ledger.js --write`，再跑 `--check` | ACTION-LEDGER PASS（6022 个控件），`git status` 无 diff |
| `build-route-matrix.js --json`，再跑 `--check` | route-matrix PASS（171 条路由 / 27 个 scene），无 diff |
| 主包 `mainpkg-size-report --json` | mainTotal 3,307,300 字节，×0.633 约 2044.5 KiB，小于 2048。gesture.wxs 少了 5 字节；新增文件都在被忽略的 `scripts/` 和 `tests/` 下 |

没有跑 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`，没有动生产，没有密钥。

STATUS: COMPLETE - B-1 已修复并先行推送；gesture.wxs 另外两处 Number()/String() 已收掉；新增 WXS 语法闸与单测（含 B-1 夹具），全量单测、UI-GATE-0、台账和路由矩阵、主包门禁全部通过；真 wcc 无 Linux 版，需在 Mac DevTools 上复核编译

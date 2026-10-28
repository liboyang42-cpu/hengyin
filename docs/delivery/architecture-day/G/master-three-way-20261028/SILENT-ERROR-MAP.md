# 给 root 的精确 silentError 映射（源码，不是扫描通过）

root 独占 `ci/silent-error-allowlist.txt`，本包未编辑它。下表只计真实 `silentError: true` 属性，不把注释里的 silentError 算调用。

| 最终模块 | 数量 | 本包建议后像中的行号 / 真实入口 |
|---|---:|---|
| pages/template/index.js | 0 | 旧整页两个列表请求已由 master 拆走，不叠加旧页 2 条 |
| pages/templatedetail/shelf/index.js（review） | 2 | 167 topic-template/list；201 template/list，整个顺序分页同一静默调用点 |
| pages/templatedetail/topic-preview/index.js（review） | 1 | 102 topic-template/info（新增生命周期后移行，以 postimage 为准） |
| pages/publish/fabu/index.js | 6 | 2725 author-edit-detail；6727 safety/precheck；8501 _bindNodeTemplate 的 author-myinfo；8585 另存副本读取 author-myinfo；8608 另存副本 draft；8745 _loadNodeTemplateDetail 的 author-myinfo |

失败合同：shelf 初次失败写各自 topicError/gameError，原 WXML 有错误态/重试，后台刷新失败保留同类已加载内容；分页失败不发布部分列表。topic-preview 初次/重试读取维持原 loading/errorMsg/retry。fabu 原编辑失败进入可恢复的 editLoadError；安全预检保留 complete 收尾和拒绝呈现；选中本人稿失败显示 nodeBindingError；另存副本失败/未知保留 nodeCopyError/nodeCopyUncertain，不自动重复写；节点详情补读失败给原节点错误。完整采用的新 requestId/receipt 未新增静默豁免。

fabu 完整公共列表 GET/POST 选择页复用 `/api/template/list`，未设 silentError：治理旧页 7 与 master 6 不能并集成 13。原 `publish-check` 模块的 1 条（沿 root 实际源路径定位） 不在本包写范围，交 root/E 保留同源真实结果。所有跨 owner 移植以最终串行行位重定位，不据这些 donor 行号扩免次数。

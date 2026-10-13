# R9-P3 成员数重复单位

`pages/club/detail/index.js` 的 `formatMemberCount` 已输出完整“2 名成员”，`enrichClub` 又追加一次单位，实际页面显示“2 名成员 名成员”。仅移除调用处重复追加，保留数值来源、千位分隔、单位和成员相关功能。

证据 `/tmp/r9-p3-member-count/`：`native.cjs red` 在正式页面加载与渲染中判红，`red.json` 同时保存 data 和节点文本。修改后 `native.cjs green` 的 2、0、1200 三组分别显示“2 名成员”“0 名成员”“1,200 名成员”，结果 `green.json`；三张截图总控均已查看。请求全部使用合成替身，无成员写入；cleanup记录恢复/disconnect。

第一次GREEN探针碰到热更新期间getApp暂不可用，第二次固定700ms等待时尚未渲染节点；未将这些作为产品故障。最终等待真实节点出现再断言，日志 `green-render-wait.log` PASS，未替换产品handler。

既有俱乐部管理、详情信息结构、视觉状态和Figma合同共26/26通过（`related.log`），其中保留普通成员入口、管理权限及治理入口负控。无新增镜像实现的测试。仅本地受控验证，真实成员治理、生产发布未验证。

Spec与Standards分别独立审查，均0项发现。

# R9-06 旧俱乐部编辑链接参数时序修复（候选）

基线fceee9812，本地并行集成后的HEAD c08953103，订单及候补改动与本修复不重叠。

旧兼容壳在onLoad前挂载编辑组件，初始clubId为空，组件attached进入missing-param。onLoad随后给出合法编号，但共享组件没有属性观察器，因此不会再次读取。修复只在兼容壳添加routeReady：onLoad一次setData解析编号并标记就绪，再挂载原组件。共享编辑器、主编辑入口、权限、保存和解散规则不改。真正缺参同样在就绪后挂载原组件，仍显示明确错误；不能简单按clubId是否存在挂载而把错误页变空白。

## RED / GREEN

/tmp/r9-06-club-edit-probe/：

- probe.cjs/red.log正式组件时序3项2绿1红：初始合法编号正确读取；后到编号不再读取；真正缺参明确错误、不猜对象。
- native.cjs/native-red.json/png：旧root真实页面与组件都已拿到7001，但仍missing-param，只发access请求、无detail请求。截图已看，确认原生复现。
- club-edit-deep-link.test.js按正式WXML挂载条件与Page/Component生命周期模拟onLoad前后；旧版合法链接RED、缺参正控GREEN。最小修复后shell-green.log与解散绑定/路由恢复相关8/8，无跳过；id与clubId两种参数、缺参保留错误、未保存退出确认均覆盖。
- native-green.cjs：修复后实际加载正确俱乐部资料，原生input修改名称→原生返回→可见放弃确认→实际“继续编辑”点击保留草稿；真正无编号页面仍显示缺参。截图native-green、native-dirty-confirm、native-missing均已实际查看。初次选择取消节点误命中隐藏modal失败；枚举真实组件show/title后找到可见确认层通过，不作为产品缺陷。
- 所有请求只为受控access/detail，无save/dissolve/真实网络；native-cleanup.json恢复/disconnect true。数据为合成正常样板，非真实权限或落库证据。

UI-GATE-0通过；Standards与Spec独立审查均0项，两者分别独立运行新增测试2/2通过。本次按上述验证完成本地修复，未发布；不把兼容入口修复泛化为俱乐部所有流程验收。

# EXTRA ed598258：76项影响面回归，1失败待修

[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36) 精确公共候选：`ed598258c0f614385940f326099b9626531fab02`。相对已针对性通过的e72候选，生产改动为JourneyStory两处合法键trim读取，以及Share GET读库异常返回503；新增E03_18Test/E06_17Test。本候选独立验证，不继承此前两类56项通过。

专用测试VPS212守卫完成编译和11个精确普通类回归：**76实际项，75通过、1失败、0错误、0跳过**。11份XML均fresh，类集合与预选一致；守卫退出1/child_command_failed，精确容器已确认清理。源码简单@Test声明为75，实际StoryService类执行26项，所以总实际76；源码计数不能替代执行观测。命令、起止时间、源包/日志/XML哈希见 [summary.json](summary.json)，影响面选择见 [selection.json](selection.json)。

唯一失败：`E06_17Test.unrelatedControllerDataAccessFailureStillUsesUnchangedGlobalAdvice:111`。预期通用消息“系统繁忙，请稍后重试”，实际“SQL SELECT private unrelated table”。该方法检查无关测试Controller仍使用原全局Advice；当前原Advice调用UserSafeMessage，原脱敏模式匹配已知异常标记，未匹配这个裸SQL夹具。相关共用生产类不在本候选改动中，根因和修复范围待原作者与C00裁定，不能将失败解释为Share503行为失效，也不能悄悄删除或放松安全断言。

E03_18全部5项通过；E06_17的Share503、原GONE边界、隐私数据和成功读取4项通过，隔离负控1项失败。均为真实service/MockMvc与mapper替身回归，不是运行中后端或真实数据库验收。

失败完整包已直接交EXTRA原E06单条修复线，并同步C00。修改共享UserSafeMessage/GlobalExceptionHandler前必须由C00核归属正式移交；若仅调整异常隔离夹具，仍要保留无泄漏/503负控，并独立记录裸SQL脱敏疑点，不能用期待泄漏SQL的断言求绿。Cloud只交源码与完整SHA，不手动验证；本地收到修复后按实际差异针对性复测，其余独立工作不等候。

证据保留：`/home/ubuntu/previews/cy-vps-extra-ed598258-20261015/`。资源为1536MiB同额memory-swap无额外swap、1CPU、256PIDs、network none/无发布端口，共享java-build.lock持有至完成；无其他服务、预览、DevTools或生产操作。3个直接关联真实MySQL类未运行，既有23个真库门禁亦未据此验收；真实数据库/Redis、运行中HTTP、设备与业务、合并部署仍未完成。

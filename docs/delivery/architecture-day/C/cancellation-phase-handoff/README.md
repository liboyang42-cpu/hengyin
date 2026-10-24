分派前四片仅供 E/D/H 参考，不是 C 最终源码增量；Java 未运行。

基线 def951bef36a94417d90259906f6bda6abe6efb3。C 三文件源码为 10c44a2f4cee2231e02f03521f7448ba6773e19d，公开 support：wireTeamUnit(service, mockMapper)、wireTeamTransactions(service, originalManager)、stubEmptyObservation(group, exactScope)。

采用 git 原生 --unified=0 导出，避免补丁文本内空白 context 的格式误处理；若选择应用，请使用 git apply --unidiff-zero --check 后按文件 owner 串行处理，不要覆盖其新工作。四片在未提交来源上 reverse/check 通过。

E：ActivityCancel/HostWeather 补当前行/phase/通知 port；payout 观察改 phase commit，JDBC 回滚用原 manager；club 改 capture/finalWithObserved。Activity 外来行负控命中 current-row guard，精确断言 IllegalStateException/原取消报名当前范围不一致，其余拒绝零副作用保持。

D：ActivityCancelFreeOrderMybatisIT 只复用原真实 manager、移除外层 execute，不替换真实 Mapper。ClubEventCancellationTransactionBoundaryTest 实际调用仍 REQUIRED 的 updateFuture，原两例和真实 manager 正确，未改。

H：OuterCancellationTopicFence 补 unit manager/current-row source，wrapper 验 capture→outside observation→finalWithObserved，保留 actor/scope/锁次序。

转出源码仅在本地未提交工作区，本目录只有 patch；root 按新 owner 唯一应用。

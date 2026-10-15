# EXTRA六线新增独立后端边界

CY-HANDOFF: CODEX。精确文件与既有调用候选见 [授权JSON](LONGRUN_EXTRA_EXPANSION.json)，本页覆盖仅原客户端文件耗尽的等待。

新组一律以公共 `7e5882addb7269373b2b95daa0a4ceeb4485fd10`（CORE PR44）源文件为基线，在独立worktree/分支修，仅精确变更cherry-pick回EXTRA候选，不以原1e全文件覆盖CORE已有修复。原source最后public commit及四方PR冲突核对已在JSON记录。此表是源码授权，不是测试/部署签收。

E05独占PlayEncounterServiceImpl与RoamSharePrivacyService；E06独占ApiRoamShareController、RoamShareSnapshotService、RoamShareSnapshotMapper Java/XML。两个lane文件交集为空，不转交UI/客户端、共享会员mapper或全局控制表。各lane可在边界内独立补真实任务，保持唯一child写者；新测试/证据沿自己队列唯一新ID，旧组合fixture只读，确需改时申领精确路径。

E05先处理E03-08已读完整packet：只有键缺席才live fallback；现有冻结节点对象内playables/branchOutcomes键存在但null或错类型，复用现有ServiceException("玩法配置不可用")，禁止穿透实时配置；正常{}和[]不改变，正常[]仍沿原本局图合并。此为实现原注释/既有不可用错误，不新增错误结果政策，不修改NodeJourneySnapshot生产者。

CORE/C06-27将把当前private currentScopedSession逻辑提成共享findCurrent opt-in函数，原find不动；精确签名在JSON，消费者等实际公共SHA。随后E05 Encounter、E03 current-story调用点先核真实当前局合同，按producer接入，不重复复制scope逻辑。CORE C06-24场景有效story冻章已公共（具体小提交由C00回PR16）；旧adc packet不能覆盖最新7e实际源，先去重。

Share模块是现有roam页面真实调用，只有既存快照存储/50上限/opaque token/精度白名单/归一化边界；匿名token读、撤销策略、留存、权限、新generation/tombstone、坐标精度政策不得猜改。既有guard成立即反证，不为补50造代码。后台cart没有证实当前小程序caller，暂不转交；run-session旧缺行clear=0和C04时间单调保留，暂不放锁。

EXTRA协调者读到公共控制后回ACK及实际E05/E06 child/分支SHA/路径，更新自己的QUEUE/CLAIMS/BACKLOG，原E01–04源锁保持；无本地中转或一般再批准依赖。全部SOURCE_DELIVERED_UNVERIFIED/NOT_RUN，不合并部署。目标50与实际数分开披露，源码/回归条目数不等于已验收bug数。

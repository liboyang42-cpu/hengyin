# 单条章节通知审计方向

Parent：5c2e2d8c46f17fec3a3e61dfe7e020577a187cc1。仅允许 D_CHAPTER_INVITE_NOTICE → SHARED_BUSINESS_AUDIT_PORT，依据 root 审定的 D fe32ef5595a8a595dc29959054a612a3bbc5dd0d 的 CHAPTER_APP_NOTICE_UNSUBMITTED 用途。

69 类型 / 57 owner 不变，67→68 方向。六可信文件中只有 owners.json 此一个 tuple 变化；其余五个逐 byte 等于 5c。3 私有 Mapper、2 whole-table facts、2ec baseline 和 locking proposal 不变。

新增控制：精确边绿；其它 D 方向红；审计端口反向回调形成有 hash witness 的新环红；另起 audit namespace 经真实临时 Git/source CLI 报 NEW_TABLE_WRITER。原缺 raw UNKNOWN、旧方向/基线冻结/写权控制保留。

实际 Python：test_gate + test_collect_jdeps 212/212，test_locking_select 19/19，共 231/231、0skip。Python3.9 grammar / diff / 限定秘密扫描通过。协议 fixture 为 NOT_COMPILED 替身，不是真 Java/JAR/jdeps/DB/CI 验收。

类型图只能约束 class 依赖方向，不能证明调用用途限于某 action、语义纯读或业务写权。未覆盖输入仍 UNKNOWN。root 唯一 CI 双 pin；工具在独立后续提交固定本 verifier 完整 SHA。

命令：`python -m unittest ci.architecture.test_gate ci.architecture.test_collect_jdeps`；`python -m unittest discover -s ci/architecture -p test_locking_select.py`。首次直接 module 跑 locking 因其现有 `import test_gate` 路径产生 ImportError（未执行场景）；上述 discover 为正确入口，19 项实际通过。

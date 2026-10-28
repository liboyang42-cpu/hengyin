# 恢复writer可信引用接线

- verifier：`0297c2cc9d093e8127103da26325e7da0f1f18e1`，parent `3d3a63d4120c83eaecd8b2bf70a12b51d5f4988c`；六可信文件SHA见该提交 `F/payment-recovery-writers/trusted-files.json`。
- 本工具提交 parent：`c4bc0693e2313905dca940f1af3f2ca4c5908fd1`。仅消费 compiled_controls.py 固定ref + 原test旧ref拒绝增量及本目录；禁止用此旧祖先整树覆盖集成。
- root唯一应用 `dual-ref.review.patch`，同步两个checkout到相同完整 verifier SHA；workflow实际文件本片未编辑。补丁依据公开9b28，只改两行，没有新job/并行/依赖。
- 工具实际8项Python控制通过、0skip；仅前置/输入/未知/拒绝控制，不执行Java/jdeps。配套verifier273项Python通过；Java/真实编译边/受信事件source gate均NOT_RUN，由root统一验收。

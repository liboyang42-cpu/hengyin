# 固定接线

可信verifier：`c2db98326366c52e640c2b1c421b0fb6bde611fa`，parent公开 `8490765f0572dae8ec3468f4e75d0ff6f4760b12`。六文件SHA见同目录trusted-files.json；本片追加固定compiled_controls.VERIFIER及旧0297拒绝控制源码。

root串行应用dual-ref.review.patch；本片没有直接改workflow、job、预算或registry。所有原控制保留，新增控制源码NOT_RUN；Python/Node回归、Java/jdeps/数据库/CI均NOT_RUN。B三文件路径/最终公开源仍待root提供，未猜测修改原B两个SQL绑定。

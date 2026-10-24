# 最终固定接线

可信verifier：`b94428f0242be4a33332d05e29bb7d0a7e02c734`，parent `2b59e393cd78b9d16b82f8a9c67b83377b30b53a`，完整范围基于公开8490。六文件SHA见同目录trusted-files.json；本片同步compiled_controls.VERIFIER及旧c2db拒绝控制源码。

root仅选一份双ref补丁：尚在8490/0297用dual-ref.review.patch；已接c2db用dual-ref-after-c2db.review.patch。两份均固定同一完整b944。实际workflow/job/预算/registry未改；旧baseline、3私有Mapper、唯一manual writer和B恢复指纹保留。

B三文件经root明确无新type/XML/DDL，无需新增许可。G e57f新read Service/SELECT Mapper仅登记精确技术类型/原权限入口，read namespace未加入pure_writers，VERSION方向不证明方法级只读。累计新增11项控制源码（本片4项），本轮所有测试、审查、编译、Java/jdeps、数据库/CI均NOT_RUN；root集中验收。

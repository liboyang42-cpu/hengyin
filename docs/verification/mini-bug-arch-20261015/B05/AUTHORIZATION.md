# B05 用户授权与应用记录

用户在本聊天明确回答“批准这项最小修复”。授权范围：已删除帖文对作者旧详情链接也不可见，并连带隐藏其编辑历史，保留草稿/审核中等其他作者状态；仅修改既有SQL读取条件，无DDL。

协调者在授权后应用 pending-authorization.patch：CommunityPostMapper.visibilityPolicy 作者分支增加 lifecycle <> DELETED，新增 b05DeletedPostOldLinkIsUnavailableToAuthorAndOtherViewer MyBatis回归。补丁留在目录作为审阅记录，现已应用，不可重复应用。此前README/合同中“待授权/未应用”是授权前调查快照，以本记录为最新状态。

应用前 git apply --check 通过；应用后XML解析通过、git diff --check通过。Java/JUnit、数据库独立读回及HTTP尚未执行，由既有托管CI和后续VPS/设备层验证。授权与应用不代表业务验收通过，没有部署或启用新内容链。

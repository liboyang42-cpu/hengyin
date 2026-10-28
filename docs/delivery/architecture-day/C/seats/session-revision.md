# H 后续 P2：席位回调必须比较既有 session revision

父提交 `9343c0891767606a932a7a0b93861215fc8028ae`（已独立冻结第四入口/记录代次/表单编码修复）；本片只改席位 helper、玩家页的席位/出码调用与必要测试。不改 app/session-store、JWT/RBAC、后端或 A/G 资金逻辑，不创建会话基础设施。

## 触发证据与修复

生产 `app.setUserRole` 委托 `getSession().setUserRole`，后者会递增原 `getRevision()`，但不改变 token、authorization_issued_at 或 feature-flags epoch。原 helper 只比较后三者与 member，因此旧回调仍被视为当前。实际执行原 app 委托、真实 session-store、原分类器和持久 workflow 的定点负例：revision 1→2，原 pending 1→0；在“应保留 pending”断言实际失败（1 fail/0 skip）。此证据只说明前端迟到回调隔离缺口，不证明后端越权。

`registration-seat.captureLogin` 现在从实际 `app.getSession().getRevision()` 捕获内存 revision，`loginCurrent` 对同一原快照比较 revision。缺方法、读取异常或非法 revision 直接判上下文无效，不用 0/undefined fallback 放行；不存新 revision 字段、不迁移原 UNKNOWN、不改原身份/attemptId。

所有已经复用该 helper 的签发组件、俱乐部/商家/profile/参与详情写与读回执、磁盘待办清理因而同时受保护。玩家页带队分支在打开相机之前捕获同一 helper 快照，识别到席位后把 `seatCurrent` 用于发送、迟回调、读回执和 workflow 清理；不依赖仍未比较 revision 的旧 `_isPlayAccountCurrent`。`ensureTicket` 也在请求前验证同一快照，回包不再能以旧角色代次打开新选席场景。

角色变化后旧上下文不得重发/读回/清理/提示；磁盘原 UNKNOWN 和他人记录保持原样。用户在新上下文主动重新扫描同一席位时，仍由原持久身份限定为只读原 target，再由后端重新校验读取权限，绝不重发核销或迁移到新账号。

## 验证

新增 `registration-seat-session-revision.test.js` 的17条实际 Node 场景执行生产 app 委托和真实 session-store，包括：字符串角色与权限快照角色路径、在途写/回读、角色ABA、旧上下文零重试、新上下文只读原target、他人pending不变、同账号同代正常确认、头像昵称不改代次、缺 revision fail closed、相机未返回/提交后/读回执中切角色、签发组件与旧出码入口、四个真实 adapter 的迟回执。带队测试刻意令旧 `_isPlayAccountCurrent` 始终为 true，确认保护来自新增的席位 revision fence。

旧正常场景的9处 app fixture 显式装配稳定 revision 的 getSession 替身，保留全部原用例/断言；角色变化场景使用真实 store，不靠手动 increment 或放松生产缺字段校验。执行方式为每文件独立 `node --test --test-isolation=none --test-reporter=tap`；最终16个定点文件实际 **435/435 PASS，0 fail/skip**；逐文件输出见 `session-revision-node-results.json`。

未运行 Java/MySQL/HTTP/JWT、真机、DevTools、CI；不发布、不执行 DDL。没有用这些前端测试代替后端权限、资金或真实数据库验收。最终源码的 JS syntax、diff 检查与限定秘密扫描另外执行；root 统一组合后再跑后端。root `run40077180059` 仍是已反馈的 Admin 编译阻断/0业务/CLEAN，C 未修改其编译修复。

本片仍未并写 root 的 action ledger/UI baseline；玩家页接线导致的位置变化需 root 按最终组合源精确重定位，不扩大基线。

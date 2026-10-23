# A 开工与失败机制（编码前记录）

- 公共仓库：https://github.com/liboyang42-cpu/hengyin
- 基线/实际 HEAD：2ec68899099a1e250df0949a8383bf85b5d3ffe0（完全相同）
- 原工作树：/workspace/hengyin，分支 work，干净；本任务独立 /workspace/hengyin-a，codex/architecture-day-a-withdraw。
- 不修改 B 的 WechatPayServiceImpl、通用余额写侧、根 pom、CI 或共享接口。

## 失败机制与最小反例

1. debitCash 成功扣款但不持久化扣款流水；事务提交回包丢失后 status=3 与「从未扣款」无法区分。测试：有/无真实 DEBIT 证据均不能仅凭状态回补；DEBIT 和绑定失败必须回滚扣款。
2. 超时升级 status=3，人工仅据状态+双人复核回补，可能已真实付款。测试：SUBMIT_INTENT 后 UNKNOWN/PROCESSING/NOT_FOUND/缺 ambiguous 错误均禁止回补；迟到 SUCCESS 阻止回补。
3. 自动回补已有 TransactionTemplate，保留；但没有扣款/渠道事实检查。测试：合法 NOT_ACCEPTED/最终 FAIL 首次必须真实回补；重复/并发最多一次。
4. 人工 catch 余额异常后补写状态并返回 false，可能提交错误经济结果。测试：失败必须抛出并回滚终态、REFUND 证据与余额；不吞异常。
5. 原业务号/金额与扣款证据没有不可变关联。测试：错 member/amount/CW/CD 或已绑定证据的重复扣款必须拒绝，原号不释放。

## 验证边界

按授权禁止在云任务 VM 启动 Java 后端、Java 测试或数据库。此处只写测试和运行轻量静态检查；Java/MySQL 实际执行交集成人统一安排。没有受控 VPS 凭据，不申请、不复制。

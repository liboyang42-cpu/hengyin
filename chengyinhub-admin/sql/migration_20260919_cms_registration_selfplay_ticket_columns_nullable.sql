-- RUN-41 后端第二根因修复：cms_registration.ticket_id 放回可空。
-- 为什么改列而不是代码塞 0：
--   自玩通行证(purchase_kind=2)按设计就没有票种 —— createSelfPlayPassInternal 从不调 setTicketId，
--   动态 insert 省略该列；P0-02 注释明文写着旧推断口径是「owner_type=1 + ticket_id 为空」。
--   base_schema_20260818 把它建成 bigint NOT NULL 且无默认值 ⇒ 严格模式(mysql_error 1364)下
--   每一次自玩建单都在 insertCmsRegistration 上炸,被控制器掩成「系统繁忙」且零日志(RUN-51)。
--   生产实证:cms_registration 全表零条 purchase_kind=2 —— 自玩票从上线起就从未落库成功过。
-- 发布顺序铁律:本迁移必须先于带 RUN-41 修复的 jar 落库(FreeSelfPlayCreateRealDbIT 按同一顺序复现)。
ALTER TABLE cms_registration
    MODIFY COLUMN `ticket_id` bigint NULL DEFAULT NULL
    COMMENT '票种ID;自玩通行证(purchase_kind=2)无票种,为 NULL',
    MODIFY COLUMN `ticket_price` decimal(10,2) NULL DEFAULT NULL
    COMMENT '票种价格(冗余);自玩通行证无票种,为 NULL';

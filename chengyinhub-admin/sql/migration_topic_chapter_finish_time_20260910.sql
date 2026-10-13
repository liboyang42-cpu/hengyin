-- 稿 468:1126「章节控制 → 结束本章」的落点。
--
-- cms_topic_chapter 一直没有「这一章结束了没有」这件事:只有 recruit_enabled
-- (商家承接开关)。而「结束」和「关掉招商」不是一回事 ——
-- 关掉招商是可以再打开的日常调度,结束是**不可逆**的:结束之后这一章不再接受
-- 商家承接申请,也不能重新开放。两件事共用一个布尔,迟早有人把结束当成关闭又打开。
--
-- 用时刻而不是布尔:「什么时候结束的」本身要能回答 —— 结算与复盘都要问这个时间。
--
-- 写入点只有一处:ApiTopicController#finishChapter,发起人(主题归属人)才能调,
-- 且只在 finish_time 仍为 NULL 时写(已结束的再点一次会被原样拒掉,不覆盖时刻)。

ALTER TABLE `cms_topic_chapter`
    ADD COLUMN `finish_time` datetime NULL DEFAULT NULL
        COMMENT '本章结束的时刻(不可逆;有值即不再接受商家承接申请)' AFTER `recruit_enabled`;

-- 存量章节不回填:历史上没有「结束」这个动作,全部按未结束处理。

package com.chengyinhub.business.service.support;

/**
 * 报名/购票的「数量 vs 剩余库存」唯一判定。报价与建单共用同一文案。
 *
 * <p><b>只落实已有事实,不新增规则:</b>这里判的是每行票的张数
 * ({@code RegistrationTicketItem.count} / {@code RegistrationRequest.tickets[]})与
 * {@code oms_ticket.remaining_inventory}的关系 —— 库存是既有的可售事实。
 *
 * <p><b>刻意不把成团人数当购买上限</b>:{@code oms_ticket.min_people}(最低成团人数)、
 * {@code max_people}(成团人数上限)描述的是"多少人能成团",{@code team_size}是"建议组队人数",
 * 三者都不是单次下单的张数闸,混用会把"一次买 5 张票的一个人"误判成"5 人团"。成团/队伍规则
 * 属多人协作域,不在本类表达。
 *
 * <p><b>不是并发闸</b>:报价阶段调用本类只是让用户在报价页就看到"买不了",避免走到下单才失败。
 * 并发下的唯一权威是建单侧的
 * {@code OmsTicketMapper.deductInventory / deductInventoryKeepingReserved} 条件 UPDATE
 * (remaining >= qty 才扣,0 行 = 库存不足)。调用方不得因为这里通过了就跳过那条 CAS。
 */
public final class RegistrationQuantityPolicy {

    private RegistrationQuantityPolicy() {
    }

    /** 张数归一:与建单/报价既有口径一致 —— null 或 &lt;=0 视为 1 张。 */
    public static int normalizedQuantity(Integer count) {
        return count == null || count <= 0 ? 1 : count;
    }

    /**
     * 校验本次要买的张数不超过当前剩余库存。不通过直接抛业务异常。
     *
     * <p>文案与建单侧逐字相同(「库存不足,仅剩 N 张」),前端按同一句直出,不需要为报价/下单
     * 两处准备两套解释。
     *
     * @param remainingInventory 报价/建单这一次读到的剩余库存;null 视为 0(无票可售)
     * @param quantity           本行购买张数;null/&lt;=0 由调用方先归一到 1,这里再兜底一次
     * @throws RuntimeException 张数超过剩余库存
     */
    public static void assertPurchasable(Long remainingInventory, int quantity) {
        long remaining = remainingInventory == null ? 0L : remainingInventory;
        int wanted = quantity <= 0 ? 1 : quantity;
        if (remaining < wanted) {
            throw new RuntimeException("库存不足,仅剩 " + remaining + " 张");
        }
    }
}

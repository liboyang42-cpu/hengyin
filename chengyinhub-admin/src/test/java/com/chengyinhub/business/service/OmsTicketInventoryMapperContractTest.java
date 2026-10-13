package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 票务库存的并发/回补 SQL 是资金链路约束，不能被一次“整理 Mapper”时悄悄删掉。
 */
class OmsTicketInventoryMapperContractTest
{
    private static final String TICKET_MAPPER =
            "chengyinhub-system/src/main/resources/mapper/business/OmsTicketMapper.xml";

    @Test
    void partialTicketUpdateLocksTheRowBeforeCombiningTimeFields() throws Exception
    {
        String xml = WiringSource.read(TICKET_MAPPER).replaceAll("\\s+", "");

        assertTrue(xml.contains("<selectid=\"selectOmsTicketByIdForUpdate\""
                        + "parameterType=\"Long\"resultMap=\"OmsTicketResult\">"
                        + "<includerefid=\"selectOmsTicketVo\"/>whereid=#{id}forupdate</select>"),
                "部分更新必须 SELECT ... FOR UPDATE，不能用无锁查询拼接时间窗口");
    }

    @Test
    void inventoryUpdateAndRestoreKeepBothBoundsInDatabase() throws Exception
    {
        String xml = WiringSource.read(TICKET_MAPPER).replaceAll("\\s+", "");
        String update = section(xml, "<updateid=\"updateOmsTicket\"", "</update>");
        String restore = section(xml, "<updateid=\"restoreInventory\"", "</update>");

        assertTrue(update.contains("#{remainingInventory}=remaining_inventory-(total_inventory-#{totalInventory})"),
                "总容量变更必须同步折算可售库存，不能留下 total 小于 remaining 的假库存");
        assertTrue(update.contains("remaining_inventory-(total_inventory-#{totalInventory})&gt;=0"),
                "下调总容量必须由 SQL 再确认已占用库存，不能只依赖 Java 预读");
        assertTrue(restore.contains("remaining_inventory+#{qty}&lt;=total_inventory"),
                "回补库存必须由 SQL 限制不超过总库存");
    }

    private static String section(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, "找不到票务库存 SQL 段: " + startMarker);
        return source.substring(start, end);
    }
}

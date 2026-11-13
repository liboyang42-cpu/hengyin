package com.chengyinhub.business.service.channel.platform;

import java.util.HashMap;
import java.util.Map;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用 Mockito 模拟 im_channel_request_receipt 的占位/加锁/完成，按 (operator,family,request) 存内存。 */
final class ReceiptFake
{
    final Map<String, Map<String, Object>> rows = new HashMap<>();

    static ReceiptFake install(ChannelPlatformMapper mapper)
    {
        ReceiptFake fake = new ReceiptFake();
        when(mapper.reserveReceipt(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong())).thenAnswer(i -> {
            String key = i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2);
            if (!fake.rows.containsKey(key))
            {
                Map<String, Object> row = new HashMap<>();
                row.put("fingerprint", i.getArgument(3));
                row.put("operation", i.getArgument(4));
                row.put("channelId", i.getArgument(5));
                fake.rows.put(key, row);
                return 1;
            }
            return 0;
        });
        when(mapper.lockReceipt(anyLong(), anyString(), anyString())).thenAnswer(i ->
                fake.rows.get(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2)));
        when(mapper.selectReceipt(anyLong(), anyString(), anyString())).thenAnswer(i ->
                fake.rows.get(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2)));
        when(mapper.finishReceipt(anyLong(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            Map<String, Object> row = fake.rows.get(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2));
            if (row == null || row.get("response") != null) return 0;
            row.put("response", i.getArgument(3));
            return 1;
        });
        return fake;
    }
}

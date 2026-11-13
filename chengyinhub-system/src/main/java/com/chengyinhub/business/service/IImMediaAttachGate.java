package com.chengyinhub.business.service;

/**
 * /api/im/send 发送语音(10)/视频(11)/文件(12)时的挂接闸：卡片必须指向发送者本人在本会话、审核已通过、尚未发送过的上传；
 * 消息落库后把上传绑定到该消息（一份上传只挂一条消息）。拒绝时抛 ServiceException(4xx, detail = 稳定码)。
 */
public interface IImMediaAttachGate
{
    boolean handles(int msgType);

    /** 校验并锁定上传台账，返回台账 id。 */
    long prepare(long sender, long conversationId, int msgType, String content, String extraJson);

    void bind(long uploadId, long messageId);

    String summary(int msgType);
}

package com.chengyinhub.business.service;

/** 原业务同事务站内通知意图；B拥有实现，A只生产原事件，不表示已送达。 */
public interface ISystemNotificationIntentService {
    /** 内部原事件回读；存在时禁止根据live业务对象重建payload。 */
    Long findIntentId(String eventKey);

    /** 同事件键必须固定接收人、类型及原始内容；返回持久意图ID，失败应回滚业务事务。 */
    Long enqueue(Long recipient, Integer msgType, String content, String extraJson, String eventKey);
}

package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ImConversation;

/**
 * IM 会话 Mapper
 */
public interface ImConversationMapper
{
    public ImConversation selectImConversationById(Long id);

    /** 发送消息/关闭会话的共同互斥点；必须在事务中调用。 */
    public ImConversation selectImConversationByIdForUpdate(Long id);

    public ImConversation selectImConversationByIdForShare(Long id);

    public ImConversation selectByTypeAndBizKey(@Param("type") Integer type, @Param("bizKey") String bizKey);

    /** 按类型列出会话(按最后消息时间倒序) — 后台客服台用 */
    public List<ImConversation> selectByType(@Param("type") Integer type);

    public int insertImConversation(ImConversation conversation);

    public int updateLastMessage(@Param("id") Long id, @Param("msgType") Integer msgType,
                                 @Param("text") String text, @Param("at") Date at);

    /** 关闭业务群会话，历史消息保留。 */
    public int closeConversation(@Param("id") Long id);
}

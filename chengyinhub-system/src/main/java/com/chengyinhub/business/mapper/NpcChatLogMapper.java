package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.NpcChatLog;

/**
 * NPC 追问日志 Mapper。
 *
 * @author chengyinhub
 */
public interface NpcChatLogMapper
{
    int insertNpcChatLog(NpcChatLog log);

    /**
     * 删除 create_time 早于 cutoff 的追问日志(留存清理,分批)。
     *
     * @param cutoff 保留期截止时间(早于此的删)
     * @param limit  单批删除上限
     * @return 实际删除行数
     */
    int deleteNpcChatLogsBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);

    /**
     * 查某用户的全部追问日志(用户数据导出,《漫游AI方案》§7.4)。
     *
     * @param userId 用户 ID(必须来自登录态,绝不接受客户端传参)
     * @return 该用户的全部记录,按时间升序
     */
    List<NpcChatLog> selectNpcChatLogsByUserId(@Param("userId") Long userId);

    /**
     * 删某用户的全部追问日志(用户主动删除,区别于 deleteNpcChatLogsBefore 的到期清理)。
     *
     * @param userId 用户 ID(必须来自登录态,绝不接受客户端传参)
     * @return 实际删除行数
     */
    int deleteNpcChatLogsByUserId(@Param("userId") Long userId);
}

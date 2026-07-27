package org.jeecg.modules.airag.practice.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.airag.practice.tool.entity.AiPendingToolCall;

/**
 * 写工具确认单数据访问接口。
 */
@Mapper
public interface AiPendingToolCallMapper extends BaseMapper<AiPendingToolCall> {

    /**
     * 加行锁读取确认单，保证多实例并发确认时只有一个事务能够执行写操作。
     */
    @Select("SELECT * FROM ai_pending_tool_call WHERE id = #{id} FOR UPDATE")
    AiPendingToolCall selectForUpdate(@Param("id") String id);

    /**
     * 仅允许确认单所属用户取消仍在有效期内的待确认请求。
     */
    @Update("UPDATE ai_pending_tool_call SET status='CANCELLED', update_time=NOW() " +
            "WHERE id=#{id} AND user_id=#{userId} AND status='PENDING' AND expires_at>NOW()")
    int cancel(@Param("id") String id, @Param("userId") String userId);
}

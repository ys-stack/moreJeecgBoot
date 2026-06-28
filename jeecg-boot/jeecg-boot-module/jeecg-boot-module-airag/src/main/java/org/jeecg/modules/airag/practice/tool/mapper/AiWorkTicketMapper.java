package org.jeecg.modules.airag.practice.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.airag.practice.tool.entity.AiWorkTicket;

/**
 * 工单 Mapper
 */
public interface AiWorkTicketMapper extends BaseMapper<AiWorkTicket> {

    /**
     * 查询指定前缀的最大工单号（用于生成递增序号）
     *
     * @param prefix 工单号前缀，如 "TK20260628"
     * @return 最大工单号，不存在则返回 null
     */
    @Select("SELECT MAX(ticket_no) FROM ai_work_ticket WHERE ticket_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxTicketNo(@Param("prefix") String prefix);
}

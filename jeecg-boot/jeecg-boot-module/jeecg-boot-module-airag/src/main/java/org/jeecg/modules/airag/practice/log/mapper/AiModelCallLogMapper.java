package org.jeecg.modules.airag.practice.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.airag.practice.log.entity.AiModelCallLog;
import org.jeecg.modules.airag.practice.log.vo.AiCallStatVO;

import java.util.List;

/**
 * AI 模型调用日志 Mapper
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-13
 * @Version: V1.0
 */
public interface AiModelCallLogMapper extends BaseMapper<AiModelCallLog> {

    /**
     * 按日期统计调用数据
     *
     * @param startDate 开始日期 yyyy-MM-dd
     * @param endDate   结束日期 yyyy-MM-dd
     * @param userId    用户ID（可选）
     * @return 统计结果
     */
    AiCallStatVO statByDateRange(@Param("startDate") String startDate,
                                 @Param("endDate") String endDate,
                                 @Param("userId") String userId);

    /**
     * 按模型分组统计
     *
     * @param startDate 开始日期 yyyy-MM-dd
     * @param endDate   结束日期 yyyy-MM-dd
     * @return 按模型分组的统计列表
     */
    List<AiCallStatVO> statGroupByModel(@Param("startDate") String startDate,
                                        @Param("endDate") String endDate);

    /**
     * 按天统计调用趋势（最近 N 天）
     *
     * @param startDate 开始日期 yyyy-MM-dd
     * @param endDate   结束日期 yyyy-MM-dd
     * @return 每天的统计数据
     */
    List<AiCallStatVO> statDailyTrend(@Param("startDate") String startDate,
                                      @Param("endDate") String endDate);
}

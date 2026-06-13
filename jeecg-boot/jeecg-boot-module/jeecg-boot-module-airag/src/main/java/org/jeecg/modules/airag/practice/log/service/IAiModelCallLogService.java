package org.jeecg.modules.airag.practice.log.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.log.entity.AiModelCallLog;
import org.jeecg.modules.airag.practice.log.vo.AiCallStatVO;

import java.util.List;

/**
 * AI 模型调用日志 Service
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-13
 * @Version: V1.0
 */
public interface IAiModelCallLogService extends IService<AiModelCallLog> {

    /**
     * 按日期范围统计
     *
     * @param startDate 开始日期 yyyy-MM-dd
     * @param endDate   结束日期 yyyy-MM-dd
     * @param userId    用户ID（可选）
     * @return 统计结果
     */
    AiCallStatVO statByDateRange(String startDate, String endDate, String userId);

    /**
     * 按模型分组统计
     *
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 按模型分组的统计列表
     */
    List<AiCallStatVO> statGroupByModel(String startDate, String endDate);

    /**
     * 按天统计调用趋势
     *
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 每天的统计数据
     */
    List<AiCallStatVO> statDailyTrend(String startDate, String endDate);
}

package org.jeecg.modules.airag.practice.log.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.modules.airag.practice.log.entity.AiModelCallLog;
import org.jeecg.modules.airag.practice.log.mapper.AiModelCallLogMapper;
import org.jeecg.modules.airag.practice.log.service.IAiModelCallLogService;
import org.jeecg.modules.airag.practice.log.vo.AiCallStatVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 模型调用日志 Service 实现
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-13
 * @Version: V1.0
 */
@Service
public class AiModelCallLogServiceImpl extends ServiceImpl<AiModelCallLogMapper, AiModelCallLog>
        implements IAiModelCallLogService {

    @Override
    public AiCallStatVO statByDateRange(String startDate, String endDate, String userId) {
        AiCallStatVO stat = this.baseMapper.statByDateRange(startDate, endDate, userId);
        if (stat == null) {
            stat = new AiCallStatVO();
            stat.setCallCount(0L);
            stat.setTotalTokens(0L);
            stat.setSuccessCount(0L);
            stat.setFailCount(0L);
        }
        return stat;
    }

    @Override
    public List<AiCallStatVO> statGroupByModel(String startDate, String endDate) {
        return this.baseMapper.statGroupByModel(startDate, endDate);
    }

    @Override
    public List<AiCallStatVO> statDailyTrend(String startDate, String endDate) {
        return this.baseMapper.statDailyTrend(startDate, endDate);
    }
}

package org.jeecg.modules.airag.practice.eval.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalResult;
import org.jeecg.modules.airag.practice.eval.mapper.AiEvalResultMapper;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalResultService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI评测结果Service实现。
 * 提供按runId查看和清理评测结果的能力。
 */
@Slf4j
@Service
public class AiEvalResultServiceImpl
        extends ServiceImpl<AiEvalResultMapper, AiEvalResult>
        implements IAiEvalResultService {

    /**
     * 查询一次评测运行下的所有逐用例结果。
     */
    @Override
    public List<AiEvalResult> listByRunId(String runId) {
        return this.lambdaQuery()
                .eq(AiEvalResult::getRunId, runId)
                .orderByAsc(AiEvalResult::getCaseCode)
                .list();
    }

    /**
     * 删除一次评测运行下的所有结果记录。
     */
    @Override
    public boolean removeByRunId(String runId) {
        return this.lambdaUpdate()
                .eq(AiEvalResult::getRunId, runId)
                .remove();
    }
}

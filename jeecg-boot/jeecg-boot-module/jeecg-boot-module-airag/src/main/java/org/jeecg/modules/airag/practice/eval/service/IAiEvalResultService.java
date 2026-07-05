package org.jeecg.modules.airag.practice.eval.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalResult;

import java.util.List;

/**
 * AI评测结果Service。
 * 封装评测结果的常用查询与清理能力。
 */
public interface IAiEvalResultService extends IService<AiEvalResult> {

    /**
     * 查询某次评测运行的全部结果。
     *
     * @param runId 评测运行ID
     * @return 当前runId下的逐用例结果
     */
    List<AiEvalResult> listByRunId(String runId);

    /**
     * 删除某次评测运行的全部结果。
     *
     * @param runId 评测运行ID
     * @return 是否删除成功
     */
    boolean removeByRunId(String runId);
}

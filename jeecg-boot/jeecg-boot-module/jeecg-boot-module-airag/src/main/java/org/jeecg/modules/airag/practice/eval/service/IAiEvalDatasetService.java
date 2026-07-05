package org.jeecg.modules.airag.practice.eval.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalDataset;

import java.util.List;

/**
 * AI评测集Service。
 * 封装评测用例的常用查询能力。
 */
public interface IAiEvalDatasetService extends IService<AiEvalDataset> {

    /**
     * 查询启用的评测用例。
     *
     * @param evalType 评测类型，传空时查询全部类型
     * @return 启用状态的评测用例列表
     */
    List<AiEvalDataset> listEnabled(String evalType);

    /**
     * 根据用例编码查询评测用例。
     *
     * @param caseCode 用例编码
     * @return 匹配的评测用例
     */
    AiEvalDataset getByCaseCode(String caseCode);
}

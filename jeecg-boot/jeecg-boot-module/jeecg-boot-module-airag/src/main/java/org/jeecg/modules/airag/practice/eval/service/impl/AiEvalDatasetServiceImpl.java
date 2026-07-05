package org.jeecg.modules.airag.practice.eval.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalDataset;
import org.jeecg.modules.airag.practice.eval.mapper.AiEvalDatasetMapper;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalDatasetService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI评测集Service实现。
 * 目前只放通用查询逻辑，评测执行逻辑单独放Runner中。
 */
@Slf4j
@Service
public class AiEvalDatasetServiceImpl
        extends ServiceImpl<AiEvalDatasetMapper, AiEvalDataset>
        implements IAiEvalDatasetService {

    /**
     * 查询启用的评测用例，并按用例编码排序。
     */
    @Override
    public List<AiEvalDataset> listEnabled(String evalType) {
        return this.lambdaQuery()
                .eq(AiEvalDataset::getStatus, 1)
                .eq(StringUtils.isNotBlank(evalType), AiEvalDataset::getEvalType, evalType)
                .orderByAsc(AiEvalDataset::getCaseCode)
                .list();
    }

    /**
     * 根据稳定用例编码查询单条评测用例。
     */
    @Override
    public AiEvalDataset getByCaseCode(String caseCode) {
        return this.lambdaQuery()
                .eq(AiEvalDataset::getCaseCode, caseCode)
                .one();
    }
}

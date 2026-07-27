package org.jeecg.modules.airag.practice.eval.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalRun;
import org.jeecg.modules.airag.practice.eval.mapper.AiEvalRunMapper;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalRunService;
import org.springframework.stereotype.Service;

@Service
public class AiEvalRunServiceImpl extends ServiceImpl<AiEvalRunMapper, AiEvalRun>
        implements IAiEvalRunService {
}

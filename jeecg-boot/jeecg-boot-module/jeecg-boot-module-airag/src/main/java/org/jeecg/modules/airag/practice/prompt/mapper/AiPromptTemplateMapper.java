package org.jeecg.modules.airag.practice.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.jeecg.modules.airag.practice.prompt.entity.AiPromptTemplate;

/**
 * AI Prompt 模板 Mapper
 *
 * 学习笔记：
 * - 继承 BaseMapper 后，insert/selectById/selectList/updateById/deleteById 全部自动生成
 * - 不需要写 XML，简单查询全靠 MyBatis-Plus
 * - 复杂查询可以加 @Select 注解或自定义方法
 */
@Mapper
public interface AiPromptTemplateMapper extends BaseMapper<AiPromptTemplate> {
}

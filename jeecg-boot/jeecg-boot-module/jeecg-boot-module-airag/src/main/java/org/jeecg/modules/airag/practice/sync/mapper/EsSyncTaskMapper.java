package org.jeecg.modules.airag.practice.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.jeecg.modules.airag.practice.sync.entity.EsSyncTask;

/**
 * ES数据同步任务Mapper接口
 *
 * @Author: jeecg-boot
 * @Date: 2026-07-10
 */
@Mapper
public interface EsSyncTaskMapper extends BaseMapper<EsSyncTask> {
}

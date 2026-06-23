package org.jeecg.modules.airag.practice.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.jeecg.modules.airag.practice.user.entity.PracticeSysUser;

/**
 * 轻量版用户 Mapper — 仅用于 practice 模块内部查询
 */
@Mapper
public interface PracticeSysUserMapper extends BaseMapper<PracticeSysUser> {
}

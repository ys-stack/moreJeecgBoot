package org.jeecg.modules.airag.practice.tool.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.modules.airag.practice.tool.entity.AiToolRolePermission;
import org.jeecg.modules.airag.practice.tool.mapper.AiToolRolePermissionMapper;
import org.jeecg.modules.airag.practice.tool.service.IAiToolRolePermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiToolRolePermissionServiceImpl extends ServiceImpl<AiToolRolePermissionMapper, AiToolRolePermission> implements IAiToolRolePermissionService {

    @Override
    public List<String> getPermittedToolIds(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of(); // 没角色 = 没权限
        }
        return baseMapper.selectToolIdsByRoleCodes(roleCodes);
    }
}
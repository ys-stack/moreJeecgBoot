// practice/tool/service/IAiToolRolePermissionService.java

package org.jeecg.modules.airag.practice.tool.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.tool.entity.AiToolRolePermission;

import java.util.List;

public interface IAiToolRolePermissionService extends IService<AiToolRolePermission> {

    /**
     * 根据角色编码列表，查询这些角色有权限的工具ID集合
     */
    List<String> getPermittedToolIds(List<String> roleCodes);
}
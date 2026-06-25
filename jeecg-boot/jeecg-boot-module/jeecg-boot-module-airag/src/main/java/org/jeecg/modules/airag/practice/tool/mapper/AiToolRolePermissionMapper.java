package org.jeecg.modules.airag.practice.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.airag.practice.tool.entity.AiToolRolePermission;

import java.util.List;

@Mapper
public interface AiToolRolePermissionMapper extends BaseMapper<AiToolRolePermission> {

    /**
     * 查询指定角色有权限的工具ID列表
     */
    @Select("<script>" +
            "SELECT DISTINCT tool_id FROM ai_tool_role_permission " +
            "WHERE role_code IN " +
            "<foreach collection='roleCodes' item='code' open='(' separator=',' close=')'>" +
            "#{code}" +
            "</foreach>" +
            "</script>")
    List<String> selectToolIdsByRoleCodes(@Param("roleCodes") List<String> roleCodes);
}
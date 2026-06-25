package org.jeecg.modules.airag.practice.tool.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ai_tool_role_permission")
public class AiToolRolePermission implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String toolId;      // 关联 ai_tool_definition.id
    private String roleCode;    // 角色编码

    private String createBy;
    private Date createTime;
}
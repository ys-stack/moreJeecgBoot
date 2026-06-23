package org.jeecg.modules.airag.practice.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 轻量版用户实体 — 仅映射 practice 模块查询所需字段
 * 对应 sys_user 表，避免直接依赖 jeecg-system-biz
 */
@Data
@Accessors(chain = true)
@TableName("sys_user")
public class PracticeSysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 登录账号 */
    private String username;

    /** 真实姓名 */
    private String realname;

    /** 工号 */
    private String workNo;

    /** 手机号 */
    private String phone;

    /** 删除状态（0正常 1已删除） */
    @TableLogic
    private Integer delFlag;
}

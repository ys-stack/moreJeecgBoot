package org.jeecg.modules.airag.practice.tool.handler;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.tool.cons.ToolCons;
import org.jeecg.modules.airag.practice.tool.entity.JeecgOrder;
import org.jeecg.modules.airag.practice.tool.mapper.JeecgOrderMapper;
import org.jeecg.modules.airag.practice.tool.validator.ParamValidator;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 查询订单工具（queryOrder）
 *
 * 模型决定调用此工具时，会传入 {"orderCode": "B100"} 这样的 JSON。
 * 本工具从 jeecg_order_main 表按订单号查询，返回订单信息 JSON。
 *
 * 对应 ai_tool_definition 表中 tool_code='queryOrder', handler_ref='orderToolHandler'
 */
@Slf4j
@Component("orderToolHandler")
public class OrderToolHandler extends AbstractToolHandler {
    // 订单号只允许：字母 + 数字 + 短横，长度 1~50
    private static final String ORDER_CODE_REGEX = "^[A-Za-z0-9\\-]{1,50}$";

    @Resource
    private JeecgOrderMapper jeecgOrderMapper;

    @Override
    protected List<String> validate(JSONObject args) {
        List<String> errors = new ArrayList<>();
        String orderCode = args.getString("orderCode");

        errors.addAll(ParamValidator.required("orderCode", orderCode));
        if (StringUtils.isNotBlank(orderCode)) {
            errors.addAll(ParamValidator.matchPattern("orderCode", orderCode,ORDER_CODE_REGEX, "只允许字母、数字和短横，最长50位"));
        }
        return errors;
    }

    @Override
    protected String execute(JSONObject args) {

        String orderCode = args.getString("orderCode").trim();
        LoginUser currentUser = getCurrentUser();
        log.info("[queryOrder] 查询订单号: {}", orderCode);
        //按订单号精准查询
        QueryWrapper<JeecgOrder> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("order_code", orderCode);
        if (currentUser != null && !isCurrentUserAdmin()) {
            //普通用户只能查自己部门的订单
            String deptIds = currentUser.getDepartIds();
            if (StringUtils.isNotBlank(deptIds)) {
                List<String> deptIdList = Arrays.asList(deptIds.split(","));
                queryWrapper.in("dept_id", deptIdList);
            }else {
                // 用户没分配部门，不允许查任何数据
                return "{\"message\": \"您没有分配部门，无法查询订单\"}";
            }
            log.info("[queryOrder] 非管理员 {}，限定部门 {}", currentUser.getUsername(), deptIds);
        }
        List<JeecgOrder> orders = jeecgOrderMapper.selectList(queryWrapper);
        if (ObjectUtil.isEmpty(orders)) {
            return "{\"message\": \"未找到订单号为 " + orderCode + " 的订单\"}";
        }
        return JSON.toJSONString(orders);
    }

    @Override
    protected String getToolCode() {
        return ToolCons.tool_code_queryOrder;
    }
}

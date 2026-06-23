package org.jeecg.modules.airag.practice.tool.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.ToolHandler;
import org.jeecg.modules.airag.practice.tool.entity.JeecgOrder;
import org.jeecg.modules.airag.practice.tool.mapper.JeecgOrderMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
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
public class OrderToolHandler implements ToolHandler {

    @Resource
    private JeecgOrderMapper jeecgOrderMapper;

    @Override
    public String execute(String argumentsJson) {
        JSONObject args = JSON.parseObject(argumentsJson);
        String orderCode = args.getString("orderCode");

        // 参数校验：订单号不能为空
        if (orderCode == null || orderCode.isBlank()) {
            return "{\"error\": \"订单号不能为空，请提供 orderCode 参数\"}";
        }

        log.info("[queryOrder] 查询订单号: {}", orderCode);

        // 按订单号精确查询
        QueryWrapper<JeecgOrder> qw = new QueryWrapper<>();
        qw.eq("order_code", orderCode.trim());
        List<JeecgOrder> orders = jeecgOrderMapper.selectList(qw);

        if (orders.isEmpty()) {
            return "{\"message\": \"未找到订单号为 " + orderCode + " 的订单\"}";
        }

        // 返回查到的订单（通常只有一条，但用数组兼容）
        return JSON.toJSONString(orders);
    }
}

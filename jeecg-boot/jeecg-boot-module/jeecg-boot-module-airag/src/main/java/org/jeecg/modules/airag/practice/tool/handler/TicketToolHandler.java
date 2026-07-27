package org.jeecg.modules.airag.practice.tool.handler;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.airag.practice.tool.cons.ToolCons;
import org.jeecg.modules.airag.practice.tool.entity.AiWorkTicket;
import org.jeecg.modules.airag.practice.tool.mapper.AiWorkTicketMapper;
import org.jeecg.modules.airag.practice.tool.validator.ParamValidator;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.jeecg.common.system.vo.LoginUser;

/**
 * 创建工单工具（createTicket）
 *
 * 模型决定调用此工具时，会传入类似这样的 JSON：
 * {
 *   "title": "登录页面白屏",
 *   "description": "部分用户 Chrome 125 下登录白屏",
 *   "ticketType": "bug",
 *   "priority": "high",
 *   "assignee": "admin"
 * }
 *
 * 本工具向 ai_work_ticket 表插入一条工单记录，自动生成工单编号。
 * 这是一个写操作（category=write），ai_tool_definition 中 require_confirm=1。
 *
 * 对应 ai_tool_definition 表中 tool_code='createTicket', handler_ref='ticketToolHandler'
 */
@Slf4j
@Component("ticketToolHandler")
public class TicketToolHandler extends AbstractToolHandler {

    @Resource
    private AiWorkTicketMapper aiWorkTicketMapper;

    @Override
    public String execute(JSONObject args) {
        String title = args.getString("title");
        String description = args.getString("description");

        // 构建工单实体
        AiWorkTicket ticket = new AiWorkTicket();
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setTicketType(args.getString("ticketType") != null ? args.getString("ticketType") : "bug");
        ticket.setPriority(args.getString("priority") != null ? args.getString("priority") : "medium");
        ticket.setAssignee(args.getString("assignee"));
        ticket.setStatus("open");
        ticket.setTicketNo(generateTicketNo());
        ticket.setCreateTime(new Date());

        // 获取当前登录用户作为 requester 和 createBy
        LoginUser loginUser = getCurrentUser();
        if (loginUser == null) {
            throw new IllegalStateException("缺少工具执行用户上下文");
        }
        ticket.setRequester(loginUser.getUsername());
        ticket.setCreateBy(loginUser.getId());

        log.info("[createTicket] 创建工单: title={}, type={}, priority={}",
                title, ticket.getTicketType(), ticket.getPriority());

        // 入库
        int rows = aiWorkTicketMapper.insert(ticket);
        if (rows > 0) {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "工单创建成功");
            result.put("ticketNo", ticket.getTicketNo());
            result.put("title", ticket.getTitle());
            result.put("type", ticket.getTicketType());
            result.put("priority", ticket.getPriority());
            result.put("status", ticket.getStatus());
            return result.toJSONString();
        } else {
            return "{\"success\": false, \"error\": \"工单创建失败，数据库写入异常\"}";
        }
    }

    @Override
    protected List<String> validate(JSONObject args) {
        List<String> errors = new ArrayList<>();

        String title = args.getString("title");
        String description = args.getString("description");
        String ticketType = args.getString("ticketType");
        String priority = args.getString("priority");

        // 必填项
        errors.addAll(ParamValidator.required("title", title));
        errors.addAll(ParamValidator.required("description", description));

        // 长度限制
        if (title != null) {
            errors.addAll(ParamValidator.maxLength("title", title, 200));
            errors.addAll(ParamValidator.noInjection("title", title));
        }
        if (description != null) {
            errors.addAll(ParamValidator.maxLength("description", description, 2000));
        }

        // 枚举校验（非必填，有默认值，但如果传了必须在范围内）
        if (ticketType != null) {
            errors.addAll(ParamValidator.inEnum("ticketType", ticketType,
                    "bug", "feature", "task", "incident"));
        }
        if (priority != null) {
            errors.addAll(ParamValidator.inEnum("priority", priority,
                    "low", "medium", "high", "urgent"));
        }
        return errors;
    }


    @Override
    protected String getToolCode() {
        return ToolCons.tool_code_createTicket;
    }

    /**
     * 生成工单编号：TK + 日期 + 4位序号
     * 从 DB 查询当天最大序号并递增，synchronized 防并发碰撞。
     * 格式：TK20260628xxxx
     */
    private synchronized String generateTicketNo() {
        String datePart = new SimpleDateFormat("yyyyMMdd").format(new Date());
        String prefix = "TK" + datePart;

        // 查询当天最大工单号
        String maxNo = aiWorkTicketMapper.selectMaxTicketNo(prefix);
        int nextSeq = 1;
        if (maxNo != null && maxNo.length() > prefix.length()) {
            try {
                nextSeq = Integer.parseInt(maxNo.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                // 解析失败从 1 开始
            }
        }
        return prefix + String.format("%04d", nextSeq);
    }
}

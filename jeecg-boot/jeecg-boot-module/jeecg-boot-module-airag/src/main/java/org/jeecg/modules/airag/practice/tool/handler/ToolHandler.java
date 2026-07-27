package org.jeecg.modules.airag.practice.tool.handler;

import java.util.List;

public interface ToolHandler {
    String execute(String argumentsJson);

    List<String> validateArguments(String argumentsJson);
}

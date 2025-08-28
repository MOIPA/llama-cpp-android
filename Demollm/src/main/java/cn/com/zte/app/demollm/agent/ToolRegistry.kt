package cn.com.zte.app.demollm.agent

object ToolRegistry {
    private val tools = mutableMapOf<String, ITool>()

    fun register(tool: ITool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): ITool? {
        return tools[name]
    }

    // 生成用于注入到 System Prompt 的工具列表描述
    fun getToolDefinitions(): String {
        if (tools.isEmpty()) return "[]"
        return tools.values.joinToString(separator = ",\n", prefix = "[", postfix = "]") { it.definition }
    }
}


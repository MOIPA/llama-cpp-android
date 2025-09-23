package cn.com.zte.app.demollm.agent

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GetCurrentDateTool : ITool {
    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"
    }

    override val name = "get_current_date"

    override val definition = """
    {
      "tool_name": "get_current_date",
      "tool_description": "获取今天的绝对日期，格式为 YYYY-MM-DD。这是解析'今天'、'明天'等相对时间描述的基础工具，必须在处理任何与日期相关的任务前首先调用。",
      "arguments": {}
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            Log.d(AGENT_LOG_TAG, "Executing get_current_date tool.")
            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            ToolResult(true, currentDate)
        } catch (e: Exception) {
            Log.e(AGENT_LOG_TAG, "Exception in GetCurrentDateTool", e)
            ToolResult(false, "获取日期失败: ${e.message}")
        }
    }
}

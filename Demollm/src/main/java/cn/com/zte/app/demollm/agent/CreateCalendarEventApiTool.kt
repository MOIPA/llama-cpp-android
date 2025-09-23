package cn.com.zte.app.demollm.agent

import android.content.Context
import android.util.Log
import cn.com.zte.zmail.lib.calendar.CalendarApiUtils
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

class CreateCalendarEventApiTool : ITool {
    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"
    }

    override val name = "create_calendar_event_api"

    override val definition = """
    {
      "tool_name": "create_calendar_event_api",
      "tool_description": "通过API静默创建一个新的日历事件。[前置条件: 如果用户没有提供具体日期，必须先通过 get_current_date 工具获得今天的日期]。如果用户的描述中包含‘今天’、‘明天’等相对时间，你必须先调用 get_current_date 来解析成绝对日期，然后计算出 start_time 和 end_time。",
      "arguments": {
        "type": "json object",
        "properties": {
          "title": { "type": "string", "description": "The title or subject of the event." },
          "start_time": { "type": "string", "description": "The event start time in 'YYYY-MM-DD HH:mm:ss' format." },
          "end_time": { "type": "string", "description": "The event end time in 'YYYY-MM-DD HH:mm:ss' format." }
        },
        "required": ["title", "start_time", "end_time"]
      }
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        val title = arguments.get("title")?.asString
        val startTime = arguments.get("start_time")?.asString
        val endTime = arguments.get("end_time")?.asString

        if (title.isNullOrBlank() || startTime.isNullOrBlank() || endTime.isNullOrBlank()) {
            return ToolResult(false, "Error: title, start_time, and end_time are required.")
        }

        Log.d(AGENT_LOG_TAG, "Executing create_calendar_event_api with title: $title")

        val future = CompletableFuture<ToolResult>()

        try {
            val calendarService = CalendarApiUtils.server
            if (calendarService == null) {
                future.complete(ToolResult(false, "Error: Calendar Service is not available."))
                return future.get()
            }
            calendarService.createEventSilently(title, startTime, endTime, context) { isSuccess, result ->
                if (isSuccess) {
                    future.complete(ToolResult(true, result ?: "Event created successfully."))
                } else {
                    future.complete(ToolResult(false, result ?: "Failed to create event with unknown error."))
                }
            }

        } catch (e: Exception) {
            Log.e(AGENT_LOG_TAG, "Exception while calling createEventSilently", e)
            future.complete(ToolResult(false, "Exception: ${e.message}"))
        }

        return future.get()
    }
}

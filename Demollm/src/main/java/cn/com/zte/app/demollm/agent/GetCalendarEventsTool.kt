package cn.com.zte.app.demollm.agent

import android.content.Context
import android.util.Log
import cn.com.zte.account.AccountApiUtils
import cn.com.zte.zmail.lib.calendar.CalendarApiUtils
import cn.com.zte.zmail.lib.calendar.ICalendarService
import com.zte.app.common.db.database.dao.EventInfoDBDao
import com.google.gson.JsonObject
import com.alibaba.android.arouter.launcher.ARouter
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GetCalendarEventsTool : ITool {
    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"
    }

    override val name = "get_calendar_events"

    override val definition = """
    {
      "tool_name": "get_calendar_events",
      "tool_description": "Query the list of schedules, meetings, or to-do items for a specified date no matter whether if user provide the date info.",
      "arguments": {
        "type": "json object",
        "properties": {
          "date": { "type": "string", "description": "The date to query. If the user does not provide it, this parameter should be ignored." }
        }
      }
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
//        val date = arguments.get("date")?.asString ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        return try {
            Log.d(AGENT_LOG_TAG, "Executing get_calendar_events for date: $date")
            val calendarService = ARouter.getInstance().build(CalendarApiUtils.MAIN_SERVICE)
                .navigation() as? ICalendarService
            val userNo = AccountApiUtils.getCurrUserNo(true)

            if (calendarService == null || userNo.isNullOrBlank()) {
                val errorMsg = "获取日历服务或用户信息失败"
                Log.e(AGENT_LOG_TAG, errorMsg)
                return ToolResult(false, errorMsg)
            }

            // 触发网络同步，确保本地数据是新的
            calendarService.loadTodayEventFromNetwork(userNo)
            Thread.sleep(2000) // 等待同步

            val startTime = "$date 00:00:00"
            val endTime = "$date 23:59:59"

            val eventList = EventInfoDBDao.getInstance().queryEventBetweenTime(startTime, endTime)

            if (eventList.isNullOrEmpty()) {
                ToolResult(true, "查询成功，当天没有日程安排。")
            } else {
                val formattedEvents = eventList.joinToString(separator = "\n") { event ->
                    "- ${event.startTimeParam}: ${event.title}"
                }
                ToolResult(true, formattedEvents)
            }
        } catch (e: Exception) {
            Log.e(AGENT_LOG_TAG, "Exception in GetCalendarEventsTool", e)
            ToolResult(false, "执行异常: ${e.message}")
        }
    }
}
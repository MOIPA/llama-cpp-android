package cn.com.zte.app.demollm.agent

import android.content.Context
import cn.com.zte.account.AccountApiUtils
import cn.com.zte.zmail.lib.calendar.*
import com.alibaba.android.arouter.launcher.ARouter
import com.google.gson.JsonObject
import java.util.Locale

class CreateCalendarEventTool : ITool {

    override val name = "create_calendar_event"

    override val definition = """
    {
      "tool_name": "create_calendar_event",
      "tool_description": "Create a new calendar event, meeting, or to-do item.",
      "arguments": {
        "type": "json object",
        "properties": {
          "title": { "type": "string", "description": "title or theme of the event/meeting" },
          "start_time": { "type": "string", "description": "start time of the event。if the user does not provide then ignore this parameter because the create calendar menu will let user choose the time info" }
        },
        "required": ["title"]
      }
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            val title = arguments.get("title")?.asString
            if (title.isNullOrBlank()) {
                return ToolResult(false, "失败：缺少日程标题")
            }

            val calendarService = ARouter.getInstance().build(CalendarApiUtils.MAIN_SERVICE).navigation() as? ICalendarService
            if (calendarService == null) {
                return ToolResult(false, "失败：日历服务不可用")
            }

            val account = AccountApiUtils.getCurrAccount(true)
            if (account == null || account.userId.isNullOrBlank()) {
                return ToolResult(false, "失败：无法获取有效的用户信息")
            }
            
            val startTimeDescription = arguments.get("start_time")?.asString ?: "今天"
            val eventContent = "由智能助手创建。用户描述的开始时间: $startTimeDescription"

            val currentUserContact = CalRequestContactBisModel(
                UNO = account.userId!!, N = account.nameZn ?: "", Y = account.nameEn ?: "",
                E = "", P = account.mobile ?: "", T = account.departmentZh ?: "",
                TE = account.departmentEn ?: "", F = "6"
            )
            val createModel = CalCreateBisModel(
                T = title, M = eventContent, P = arrayOf(currentUserContact), ESDate = null
            ).apply { from = "3" }
            val config = CalendarLauncherConfig(Locale.getDefault(), createModel)
            calendarService.startCreateEventActivity(context, config, null)

            ToolResult(true, "成功：已经为你打开日程创建页面，请确认并保存。")
        } catch (e: Exception) {
            ToolResult(false, "失败：执行时发生异常: ${e.message}")
        }
    }
}

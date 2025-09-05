package cn.com.zte.app.demollm.agent

import android.app.Activity
import android.content.Context
import android.util.Log
import cn.com.zte.router.settings.SETTINGS_SERVICE
import cn.com.zte.router.settings.SettingInterface
import com.alibaba.android.arouter.launcher.ARouter
import com.google.gson.JsonObject

class UploadLogTool : ITool {
    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"
    }

    override val name = "upload_log"

    override val definition = """
    {
      "tool_name": "upload_log",
      "tool_description": "Opens the log upload page, allowing the user to upload application logs.",
      "arguments": {}
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            Log.d(AGENT_LOG_TAG, "Executing upload_log tool.")

            if (context !is Activity) {
                val errorMsg = "Context is not an Activity, cannot open log report page."
                Log.e(AGENT_LOG_TAG, errorMsg)
                return ToolResult(false, errorMsg)
            }

            val settingService = ARouter.getInstance().build(SETTINGS_SERVICE).navigation() as? SettingInterface

            if (settingService == null) {
                val errorMsg = "Failed to get Setting Service."
                Log.e(AGENT_LOG_TAG, errorMsg)
                return ToolResult(false, errorMsg)
            }

            settingService.openLogReportActivity(context)
            
            ToolResult(true, "Log upload page has been opened successfully.")

        } catch (e: Exception) {
            Log.e(AGENT_LOG_TAG, "Exception in UploadLogTool", e)
            ToolResult(false, "Execution exception: ${e.message}")
        }
    }
}

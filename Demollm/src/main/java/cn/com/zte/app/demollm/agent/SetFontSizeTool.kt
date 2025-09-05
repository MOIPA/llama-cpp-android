package cn.com.zte.app.demollm.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.com.zte.app.settings.ui.view.GolbalSettingFontSizeActivity
import com.google.gson.JsonObject

class SetFontSizeTool : ITool {
    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"
    }

    override val name = "set_font_size"

    override val definition = """
    {
      "tool_name": "set_font_size",
      "tool_description": "打开字体大小设置页面",
      "arguments": {}
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            Log.d(AGENT_LOG_TAG, "Executing set_font_size tool.")

            if (context !is Activity) {
                val errorMsg = "Context is not an Activity, cannot open font size setting page."
                Log.e(AGENT_LOG_TAG, errorMsg)
                return ToolResult(false, errorMsg)
            }

            val intent = Intent(context, GolbalSettingFontSizeActivity::class.java)
            context.startActivity(intent)
            
            ToolResult(true, "Font size setting page has been opened successfully.")

        } catch (e: Exception) {
            Log.e(AGENT_LOG_TAG, "Exception in SetFontSizeTool", e)
            ToolResult(false, "Execution exception: ${e.message}")
        }
    }
}

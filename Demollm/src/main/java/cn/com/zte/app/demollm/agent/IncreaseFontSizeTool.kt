package cn.com.zte.app.demollm.agent

import android.app.Activity
import android.content.Context
import android.util.Log
import cn.com.zte.router.settings.SETTINGS_SERVICE
import cn.com.zte.router.settings.SettingInterface
import com.alibaba.android.arouter.launcher.ARouter
import com.google.gson.JsonObject

class IncreaseFontSizeTool : ITool {
    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"
        private const val MAX_FONT_SIZE = 4
    }

    override val name = "increase_font_size"

    override val definition = """
    {
      "tool_name": "increase_font_size",
      "tool_description": "增大字体大小一号",
      "arguments": {}
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            Log.d(AGENT_LOG_TAG, "Executing increase_font_size tool.")

            if (context !is Activity) {
                return ToolResult(false, "Context is not an Activity.")
            }

            val settingService = ARouter.getInstance().build(SETTINGS_SERVICE).navigation() as? SettingInterface
                ?: return ToolResult(false, "SettingService not found.")

            val currentSize = settingService.getCurrentFontSize()
            if (currentSize >= MAX_FONT_SIZE) {
                return ToolResult(true, "Font size is already at maximum.")
            }

            val newSize = currentSize + 1
            settingService.setFontSize(newSize)
            settingService.applyFontSize(context)

            val sizeDescription = when (newSize) {
                0 -> "小号"
                1 -> "标准"
                2 -> "大号"
                3 -> "特大号"
                4 -> "超大号"
                else -> "未知"
            }
            
            ToolResult(true, "字体已设置为 $sizeDescription ($newSize)")

        } catch (e: Exception) {
            Log.e(AGENT_LOG_TAG, "Exception in IncreaseFontSizeTool", e)
            ToolResult(false, "Execution exception: ${e.message}")
        }
    }
}

package cn.com.zte.app.demollm.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import cn.com.zte.router.message.ClearMessageAttachObserver
import cn.com.zte.router.message.IMessageInterface
import cn.com.zte.router.message.MESSAGE_SERVICE
import cn.com.zte.app.settings.utils.CacheUtils
import cn.com.zte.app.base.ui.BaseApp
import cn.com.zte.framework.data.utils.RN_CONFIG_FILE
import cn.com.zte.framework.data.utils.RN_PACKAGE_DIR
import cn.com.zte.framework.data.utils.getRNPackageFolder
import cn.com.zte.router.appupdate.RNConfig
import com.alibaba.android.arouter.launcher.ARouter
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class ClearCacheTool : ITool {
    override val name: String = "clear_all_cache"
    override val definition: String = """
{
"tool_name": "clear_all_cache",
"tool_description": "清理应用的所有缓存，包括系统缓存、消息缓存和小程序缓存。",
"arguments": {}
}
""".trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        try {
            var messageResult = ""
            var miniProgramResult = ""

            // 1. 调用 ClearMessageCacheTool
            val messageTool = ToolRegistry.getTool("clear_message_cache")
            if (messageTool != null) {
                val result = messageTool.execute(context, JsonObject())
                messageResult = "消息缓存: ${result.output}"
            } else {
                messageResult = "消息缓存: 清理工具未找到"
            }

            // 2. 调用 ClearMiniProgramCacheTool
            val miniProgramTool = ToolRegistry.getTool("clear_mini_program_cache")
            if (miniProgramTool != null) {
                val result = miniProgramTool.execute(context, JsonObject())
                miniProgramResult = "小程序缓存: ${result.output}"
            } else {
                miniProgramResult = "小程序缓存: 清理工具未找到"
            }

            // 3. 合并结果
            val finalMessage = "全部缓存清理完成.\n- $messageResult\n- $miniProgramResult"
            return ToolResult(true, finalMessage)

        } catch (e: Exception) {
            val errorMessage = "执行全部缓存清理时出错: ${e.message}"
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context, errorMessage,
                    Toast.LENGTH_LONG
                ).show()
            }
            return ToolResult(false, errorMessage)
        }
    }
}

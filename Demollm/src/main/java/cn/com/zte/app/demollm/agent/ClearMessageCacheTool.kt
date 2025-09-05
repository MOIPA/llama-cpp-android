package cn.com.zte.app.demollm.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import cn.com.zte.router.message.ClearMessageAttachObserver
import cn.com.zte.router.message.IMessageInterface
import cn.com.zte.router.message.MESSAGE_SERVICE
import com.alibaba.android.arouter.launcher.ARouter
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date

class ClearMessageCacheTool : ITool {
    override val name: String = "clear_message_cache"
    override val definition: String = """
    {
      "tool_name": "clear_message_cache",
      "tool_description": "清理消息模块的所有缓存，主要是图片、视频、文件等聊天附件。",
      "arguments": {}
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            val messageService = ARouter.getInstance().build(MESSAGE_SERVICE).navigation() as? IMessageInterface
            if (messageService == null) {
                return ToolResult(false, "失败：消息服务(IMessageInterface)不可用。")
            }

            val reqId = getRandomMessageId()
            val callback = object : ClearMessageAttachObserver {
                override fun onClearMessageAttachProgress(reqId: String, success: Boolean, clearAttachRate: Int) {
                    if (success && clearAttachRate == 100) {
                        Handler(Looper.getMainLooper()).post { Toast.makeText(context, "消息缓存清理成功", Toast.LENGTH_SHORT).show() }
                    } else if (!success) {
                        Handler(Looper.getMainLooper()).post { Toast.makeText(context, "消息缓存清理失败", Toast.LENGTH_SHORT).show() }
                    }
                }
            }

            messageService.clearMessageAttachProgress(reqId, callback)

            ToolResult(true, "已经成功发起了消息缓存清理任务。")
        } catch (e: Exception) {
            ToolResult(false, "执行消息缓存清理时出错: ${e.message}")
        }
    }

    private fun getRandomMessageId(): String {
        val date = Date()
        val simpleDateFormat = SimpleDateFormat("yyyyMMddHHmmssSSS")
        val formatted = simpleDateFormat.format(date)
        val random = (100..999).random()
        return "$formatted$random"
    }
}

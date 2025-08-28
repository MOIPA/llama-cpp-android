package cn.com.zte.app.demollm.agent

import android.content.Context
import com.google.gson.JsonObject

// 每个工具执行后返回的结果
data class ToolResult(
    val isSuccess: Boolean,
    val output: String // 例如 "成功创建日程" 或 "失败：时间冲突"
)

// 定义一个工具的接口，所有工具都要实现它
interface ITool {
    // 工具的唯一名称
    val name: String
    
    // 工具的详细定义，用于给大模型看
    val definition: String

    // 工具的执行逻辑，现在需要传入Context
    fun execute(context: Context, arguments: JsonObject): ToolResult
}

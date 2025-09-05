package cn.com.zte.app.demollm.agent

import android.content.Context
import cn.com.zte.app.base.ui.BaseApp
import cn.com.zte.app.settings.utils.CacheUtils
import cn.com.zte.framework.data.utils.RN_CONFIG_FILE
import cn.com.zte.framework.data.utils.RN_PACKAGE_DIR
import cn.com.zte.framework.data.utils.getRNPackageFolder
import cn.com.zte.router.appupdate.RNConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

class ClearMiniProgramCacheTool : ITool {
    override val name: String = "clear_miniprogram_cache"
    override val definition: String = """
    {
      "tool_name": "clear_miniprogram_cache",
      "tool_description": "清理所有已安装的小程序的缓存文件。",
      "arguments": {}
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            val rnPackageDir = File(BaseApp.instance.getExternalFilesDir(null), RN_PACKAGE_DIR)
            if (!rnPackageDir.exists() || !rnPackageDir.isDirectory) {
                return ToolResult(true, "小程序缓存目录不存在，无需清理。")
            }

            rnPackageDir.listFiles()?.forEach { rnFile ->
                if (rnFile.isDirectory) {
                    CacheUtils.deleteFileOrDir(rnFile)
                }
            }
            ToolResult(true, "小程序缓存清理成功。")
        } catch (e: Exception) {
            ToolResult(false, "清理小程序缓存时发生异常: ${e.message}")
        }
    }
}

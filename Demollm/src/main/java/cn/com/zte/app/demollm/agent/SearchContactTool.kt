package cn.com.zte.app.demollm.agent

import android.content.Context
import android.util.Log
import cn.com.zte.router.search.APP_SEARCH_SERVICE
import cn.com.zte.router.search.ContactsInfo
import cn.com.zte.router.search.ZTESearchService
import com.alibaba.android.arouter.launcher.ARouter
import com.google.gson.JsonObject

class SearchContactTool : ITool {
    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"
    }

    override val name = "search_contact"

    override val definition = """
    {
      "tool_name": "search_contact",
      "tool_description": "Searches for contacts based on a keyword (such as name, pinyin, or employee ID).",
      "arguments": {
        "type": "json object",
        "properties": {
          "keyword": { "type": "string", "description": "The keyword to search for, e.g., 'John Doe' or 'zhangsan'." }
        },
        "required": ["keyword"]
      }
    }
    """.trimIndent()

    override fun execute(context: Context, arguments: JsonObject): ToolResult {
        return try {
            val keyword = arguments.get("keyword")?.asString
            if (keyword.isNullOrBlank()) {
                return ToolResult(false, "Error: keyword parameter is missing.")
            }

            Log.d(AGENT_LOG_TAG, "Executing search_contact with keyword: $keyword")

            val searchService = ARouter.getInstance().build(APP_SEARCH_SERVICE).navigation() as ZTESearchService

            if (searchService == null) {
                val errorMsg = "Failed to get Search Service."
                Log.e(AGENT_LOG_TAG, errorMsg)
                return ToolResult(false, errorMsg)
            }

            val results: List<ContactsInfo> = searchService.queryContactInfoByKeyword(keyword, 50) // 0 for no limit
            Log.i(AGENT_LOG_TAG, results.toString())
            if (results.isEmpty()) {
                ToolResult(true, "No contacts found matching '$keyword'.")
            } else {
                val formattedResults = results.joinToString(separator = "\n") { contact ->
                    "-Employee Name: ${contact.employeeName}, Employee ID: ${contact.employeeID}"
                }
                ToolResult(true, "Found ${results.size} contact(s):\n$formattedResults")
            }

        } catch (e: Exception) {
            Log.e(AGENT_LOG_TAG, "Exception in SearchContactTool", e)
            ToolResult(false, "Execution exception: ${e.message}")
        }
    }
}

package cn.com.zte.app.demollm

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import cn.com.zte.app.demollm.agent.CreateCalendarEventTool
import cn.com.zte.app.demollm.agent.ToolRegistry
import cn.com.zte.app.demollm.agent.GetCalendarEventsTool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val llmAndroid: LLMAndroid = LLMAndroid.instance()
    private val gpu_layers = 0
    private val mmproj_use_gpu = 0

    companion object {
        private const val AGENT_LOG_TAG = "AgentLogic"

        @JvmStatic
        private val NanosPerSecond = 1_000_000_000.0
    }

    // --- LiveData Definitions ---
    private val _messages = MutableLiveData<List<ChatMessage>>(
        listOf(
            ChatMessage(
                "Initializing...",
                MessageType.MODEL
            )
        )
    )
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _imagePath = MutableLiveData("")
    val imagePath: LiveData<String> = _imagePath

    private val _generating = MutableLiveData(true)
    val generating: LiveData<Boolean> = _generating

    private var internalMessage: String = ""
    private var initializingJob: Job? = null
    private val gson = Gson()

    // 当前模型是否多模态
    private var isMultiModal: Boolean = false
    // 当前模型名称
    private var _modelName: String = ""

    private data class ToolCall(val tool_name: String, val arguments: JsonObject)

    init {
        ToolRegistry.register(CreateCalendarEventTool())
        ToolRegistry.register(GetCalendarEventsTool())
    }

    fun startModelLoading(
        baseModelName: String = "",
        mmprojName: String = ""
    ) {
        _messages.value = listOf(
            ChatMessage("Model : $baseModelName \nMMProj : $mmprojName", MessageType.MODEL)
        )
        viewModelScope.launch {
            llmAndroid.unload()
            delay(200)
            load(baseModelName, mmprojName, gpu_layers)
            delay(200)
        }
    }

    fun updateMessage(message: String) {
        this.internalMessage = message
    }

    fun clearImage() {
        _imagePath.postValue("")
    }

    private suspend fun addMessage(text: String, type: MessageType) {
        val currentList = _messages.value ?: emptyList()
        _messages.postValue(currentList + ChatMessage(text, type))
        delay(100)
    }

    private fun appendToLastMessage(chunk: String) {
        val currentList = _messages.value ?: emptyList()
        if (currentList.isNotEmpty()) {
            val lastMessage = currentList.last()
            val updatedList = currentList.dropLast(1) + lastMessage.copy(
                text = lastMessage.text + chunk,
                type = lastMessage.type
            )
            _messages.postValue(updatedList)
        }
    }

    private fun replaceLastMessage(newText: String) {
        val currentList = _messages.value ?: emptyList()
        if (currentList.isNotEmpty()) {
            val lastMessage = currentList.last()
            val updatedList =
                currentList.dropLast(1) + lastMessage.copy(text = newText, type = lastMessage.type)
            _messages.postValue(updatedList)
        }
    }

    fun load(modelName: String, mmprojName: String, layers: Int = 0) {
        initializingJob?.cancel()
        initializingJob = viewModelScope.launch {
            _generating.postValue(true)
            addMessage("", MessageType.MODEL)
            val animationJob = launch {
                var dots = 0
                while (true) {
                    replaceLastMessage("initializing model" + ".".repeat(dots % 7))
                    delay(500)
                    dots++
                }
            }
            try {
                val appCtx = getApplication<Application>()
                val mmprojf = File(appCtx.filesDir, mmprojName)
                if (!mmprojf.exists()) {
                    appCtx.assets.open(mmprojName)
                        .use { i -> mmprojf.outputStream().use { o -> i.copyTo(o) } }
                }
                val modelFile = File(appCtx.filesDir, modelName)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    appCtx.assets.open(modelName)
                        .use { i -> modelFile.outputStream().use { o -> i.copyTo(o) } }
                }
                // TODO 目前根据模型名称硬编码
                val isGreedy = when (modelName) {  // 是否启用贪婪采样
                    "InternVL3-2B-Instruct-Q8_0.gguf" -> true
                    else -> false
                }
                isMultiModal = when (mmprojName) {
                    "" -> false
                    else -> true
                }
                Log.d(AGENT_LOG_TAG,"is multi modality enabled:${isMultiModal} ,mmprojName:${mmprojName}")
                llmAndroid.load(
                    modelFile.absolutePath,
                    layers,
                    mmprojf.absolutePath,
                    mmproj_use_gpu,
                    isMultiModal=isMultiModal,
                    isGreedy=isGreedy,
                    temp = 0.7f,
                    topPP = 0.8f,
                )

                val systemPrompt = """你是一个强大的多模态AI助手。你的核心任务是理解并响应用户的需求。

请遵循以下优先级处理用户输入：
1.  **当用户提供了图片时**：你的首要任务是用自然语言描述图片内容或回答相关问题。**除非用户的文字指令明确要求使用工具**，否则不应调用工具。
2.  **当没有图片，或用户明确要求执行工具操作时**：判断用户的意图是否与下面列出的某个工具有明确匹配。如果匹配，请生成一个用于调用工具的JSON对象。
3.  **所有其他情况**：对于普通对话、问候、开玩笑或任何与工具功能无关的请求，请直接用自然语言回复。

**工具调用规则**：
当你决定调用工具时，必须严格按照MCP协议输出一个JSON对象，不要输出思考过程，也绝对不允许在JSON前后添加任何多余的文字。

--- 示例开始 ---
示例1：需要调用工具
用户问题: "帮我查一下今天的日程"
你的回答: {"tool_name": "get_calendar_events", "arguments": {date:"今天"}}

示例2：无需调用工具 (普通对话)
用户问题: "你好"
你的回答: "你好！有什么可以帮你的吗？"

示例3：需要调用工具
用户问题: "创建一个日程"
你的回答: {"tool_name": "create_calendar_event", "arguments": {"title": "开会"}}

示例4：无需调用工具 (问题超出工具范围)
用户问题: "今天天气怎么样？"
你的回答: "抱歉，我无法获取天气信息，但我可以帮你管理日程。"

示例5：处理图片问题 (无需调用工具)
用户问题: "这张图里有什么？"
你的回答: "这张图片展示了[此处为图片内容的描述]。"
--- 示例结束 ---

可用的工具列表如下:
${ToolRegistry.getToolDefinitions()}
""".trimIndent()
                llmAndroid.setSystemPrompt(systemPrompt)
//                llmAndroid.initSystemPrompt()
                animationJob.cancel()
                addMessage("Loaded ${modelFile.name}", MessageType.MODEL)
                addMessage(llmAndroid.sysinfo(), MessageType.MODEL)
                _modelName = modelName
            } catch (exc: Throwable) {
                animationJob.cancel()
                addMessage("failed!! ${exc.message}", MessageType.MODEL)
            } finally {
                _generating.postValue(false)
            }
        }
    }

    fun triggerInitSystemPrompt() {
        viewModelScope.launch {
            addMessage("Initializing system prompt...", MessageType.MODEL)
            llmAndroid.initSystemPrompt()
            replaceLastMessage("System prompt initialized.")
        }
    }

    // --- AGENT LOGIC ---
    fun send(context: Context) {
        viewModelScope.launch {
            Log.d(AGENT_LOG_TAG, "Agent loop started.")
            initializingJob?.join()
            var userInput = internalMessage
            if (userInput.isBlank()) return@launch
            internalMessage = ""

            val imagePathToSend = _imagePath.value ?: ""
            _imagePath.postValue("") // Clear image after sending

            addMessage(userInput, MessageType.USER)
            if (imagePathToSend.isNotEmpty()) addMessage(
                "<ImagePath>$imagePathToSend",
                MessageType.USER
            )
            addMessage("", MessageType.MODEL)
            _generating.postValue(true)

            var thinkingJob: Job? = viewModelScope.launch {
                var dots = 0
                while (true) {
                    val thinkingText = "model is thinking" + ".".repeat(dots % 4)
                    val current = _messages.value ?: emptyList()
                    if (current.isNotEmpty()) {
                        _messages.postValue(
                            current.dropLast(1) + current.last().copy(text = thinkingText)
                        )
                    }
                    delay(500)
                    dots++
                }
            }

            val responseBuilder = StringBuilder()
            var firstChunkReceived = false
            if(_modelName=="Qwen3-1.7B-Instruct-Q8_0.gguf")userInput += "/no_think"
            llmAndroid.send(userInput, true, imagePathToSend, isMultiModal)
                .catch { e ->
                    appendToLastMessage("Error: ${e.message}")
                    _generating.postValue(false)
                    Log.e(AGENT_LOG_TAG, "LLM Reasoning call failed", e)
                }
                .collect { chunk ->
                    if (!firstChunkReceived) {
                        thinkingJob?.cancel()
                        thinkingJob?.join()
                        replaceLastMessage(chunk) // Directly replace with the first chunk
                        firstChunkReceived = true
                    } else {
                        appendToLastMessage(chunk)
                    }
                    responseBuilder.append(chunk)
                }
            thinkingJob?.cancel()
            thinkingJob?.join()

            // 2. Parse and Act
            val fullResponse = responseBuilder.toString()
            Log.d(AGENT_LOG_TAG, "LLM Raw Response: $fullResponse")

            // Clean the response from <think> tags before displaying the final result
            val cleanedResponse = fullResponse.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            if (parseToolCall(fullResponse) == null) {
                replaceLastMessage(cleanedResponse) // Update UI with cleaned response if no tool call
            }

            llmAndroid.supplyMsg(cleanedResponse)
            val toolCall = parseToolCall(fullResponse) // Parse from the original response
            Log.d(AGENT_LOG_TAG, "toolCall:${toolCall}")

            if (toolCall != null) {
                Log.d(AGENT_LOG_TAG, "Tool call parsed: $toolCall")
//                addMessage("", MessageType.MODEL) // New message for tool result
                replaceLastMessage("Tool call detected: ${toolCall.tool_name}. Executing...")
                val tool = ToolRegistry.getTool(toolCall.tool_name)
                if (tool != null) {
                    val toolResult =
                        withContext(Dispatchers.IO) { tool.execute(context, toolCall.arguments) }
                    Log.d(AGENT_LOG_TAG, "Tool execution result: $toolResult")
                    appendToLastMessage("\nTool result: ${toolResult.output}\nSummarizing...")

                    // 3. Second LLM Call (Summarization) with Streaming
                    val finalPrompt = """
解读工具调用的结果，并用简体中文给用户一个友好、清晰的最终答复。你的回复必须是纯文本，绝对不允许使用JSON格式。
工具输出: '${toolResult.output}'
你的回复:
""".trimIndent()
                    Log.d(AGENT_LOG_TAG, "Summarization Prompt sent to LLM.")
                    val summaryResponseBuilder = StringBuilder()
                    var firstChunk = true
                    llmAndroid.send(finalPrompt, true, "", isMultiModal) // No image for summary
                        .catch { e ->
                            appendToLastMessage("\nError: ${e.message}"); Log.e(
                            AGENT_LOG_TAG,
                            "LLM Summarization call failed",
                            e
                        )
                        }
                        .collect { chunk ->
                            if (firstChunk) {
                                replaceLastMessage(chunk)
                                firstChunk = false
                            } else {
                                appendToLastMessage(chunk)
                            }
                            summaryResponseBuilder.append(chunk)
                        }

                    val finalSummary = summaryResponseBuilder.toString()
                    val cleanedSummary = finalSummary.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                    replaceLastMessage(cleanedSummary) // Update UI with cleaned summary

                    llmAndroid.supplyMsg(cleanedSummary)
                    Log.d(
                        AGENT_LOG_TAG,
                        "Final summary response: $cleanedSummary"
                    )
                } else {
                    replaceLastMessage("\nError: Tool '${toolCall.tool_name}' not found.")
                    Log.e(AGENT_LOG_TAG, "Tool not found: ${toolCall.tool_name}")
                }
            }
            // If no tool call, the answer is already streamed. We are done.

            _generating.postValue(false)
//            (_messages.value?.lastOrNull())?.let { llmAndroid.supplyMsg(it.text) }
            Log.d(AGENT_LOG_TAG, "Agent loop finished.")
        }
    }

    private fun parseToolCall(response: String): ToolCall? {
        try {
            val lastBrace = response.lastIndexOf('}')
            if (lastBrace == -1) return null

            var openBraces = 0
            var jsonStart = -1

            // From the last brace, search backwards to find the matching opening brace
            for (i in lastBrace downTo 0) {
                when (response[i]) {
                    '}' -> openBraces++
                    '{' -> openBraces--
                }
                if (openBraces == 0) {
                    jsonStart = i
                    break
                }
            }

            if (jsonStart == -1) return null

            val jsonString = response.substring(jsonStart, lastBrace + 1)
            val toolCall = gson.fromJson(jsonString, ToolCall::class.java)

            return if (toolCall.tool_name.isNullOrBlank() || toolCall.arguments == null) null else toolCall
        } catch (e: JsonSyntaxException) {
            Log.d(AGENT_LOG_TAG, "Not a valid tool call JSON in response: $response. ${e.message}")
            return null
        }
    }

    fun clearHistoryAndKV() {
        llmAndroid.clearHistoryAndKV()
        _messages.postValue(emptyList())
    }

    fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        viewModelScope.launch {
            try {
                _generating.postValue(true)
                addMessage("Benching", MessageType.MODEL)
                val start = System.nanoTime()
                val warmupResult = llmAndroid.bench(pp, tg, pl, nr)
                val end = System.nanoTime()
                addMessage(warmupResult, MessageType.MODEL)
                val warmup = (end - start).toDouble() / NanosPerSecond
                addMessage("Warm up time: $warmup seconds, please wait...", MessageType.MODEL)
                if (warmup > 15.0) {
                    addMessage("Warm up took too long, aborting benchmark", MessageType.MODEL)
                    return@launch
                }
                addMessage(llmAndroid.bench(pp, tg, pl, nr), MessageType.MODEL)
            } catch (exc: IllegalStateException) {
                Log.e("MainViewModel", "bench() failed", exc)
                addMessage(exc.message ?: "Bench failed", MessageType.MODEL)
            } finally {
                _generating.postValue(false)
            }
        }
    }

    fun uploadImage(uri: Uri) {
        try {
            val appCtx = getApplication<Application>()
            val file = uriToFile(appCtx, uri)
            _imagePath.postValue(file.absolutePath)
        } catch (e: Exception) {
            Log.i("MainViewModel", e.message.toString())
        }
    }

    private fun uriToFile(context: Context, uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
        val tempFile = File.createTempFile("image_", ".jpg", context.cacheDir)
        val outputStream = FileOutputStream(tempFile)
        inputStream.use { input ->
            outputStream.use { output ->
                input?.copyTo(output)
            }
        }
        return tempFile
    }
}

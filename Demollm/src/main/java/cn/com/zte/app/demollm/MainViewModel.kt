package cn.com.zte.app.demollm

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import cn.com.zte.app.demollm.AgentChatMessage
import cn.com.zte.app.demollm.agent.ClearCacheTool
import cn.com.zte.app.demollm.agent.ClearMessageCacheTool
import cn.com.zte.app.demollm.agent.ClearMiniProgramCacheTool
import cn.com.zte.app.demollm.agent.CreateCalendarEventApiTool
import cn.com.zte.app.demollm.agent.CreateCalendarEventTool
import cn.com.zte.app.demollm.agent.DecreaseFontSizeTool
import cn.com.zte.app.demollm.agent.SearchContactTool
import cn.com.zte.app.demollm.agent.UploadLogTool
import cn.com.zte.app.demollm.agent.ToolRegistry
import cn.com.zte.app.demollm.agent.GetCalendarEventsTool
import cn.com.zte.app.demollm.agent.GetCurrentDateTool
import cn.com.zte.app.demollm.agent.IncreaseFontSizeTool
import cn.com.zte.app.demollm.agent.SetFontSizeTool
import cn.com.zte.framework.base.context
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
import java.io.FileInputStream
enum class AgentMessageRole { SYSTEM, USER, ASSISTANT, TOOL }
data class AgentChatMessage(val role: AgentMessageRole, val content: String)
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

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _transcription = MutableLiveData("")
    val transcription: LiveData<String> = _transcription

    private val recorder = Recorder()
    private var recordingJob: Job? = null

    private var internalMessage: String = ""
    private var initializingJob: Job? = null
    private val gson = Gson()

    // 当前模型是否多模态
    private var isMultiModal: Boolean = false
    // 当前模型名称
    private var _modelName: String = ""
    private var _noThinkFlag: Boolean = false


    private var systemPrompt = ""

    fun getSystemPrompt(): String {
        return systemPrompt
    }

    fun updateSystemPrompt(newPrompt: String) {
        viewModelScope.launch {
            llmAndroid.setSystemPrompt(newPrompt)
            addMessage("Initializing system prompt...", MessageType.MODEL)
            llmAndroid.initSystemPrompt()
            replaceLastMessage("System prompt initialized.")
        }
    }

    private data class ToolCall(val tool_name: String, val arguments: JsonObject)

    init {
        // 一次路由，简单模式，日程创建的依赖和静默创建都注释了
        ToolRegistry.register(CreateCalendarEventTool())
        ToolRegistry.register(GetCalendarEventsTool())
//        ToolRegistry.register(ClearCacheTool())
        ToolRegistry.register(ClearMessageCacheTool())
        ToolRegistry.register(ClearMiniProgramCacheTool())
//        ToolRegistry.register(GetCurrentDateTool())
        ToolRegistry.register(UploadLogTool())
//        ToolRegistry.register(SearchContactTool())
        ToolRegistry.register(IncreaseFontSizeTool())
        ToolRegistry.register(DecreaseFontSizeTool())
        ToolRegistry.register(SetFontSizeTool())
//        ToolRegistry.register(CreateCalendarEventApiTool())
    }

    fun merge() {
        viewModelScope.launch {
            val appCtx = context()
            val outputFile = File(appCtx.filesDir, "Qwen3-4B-Instruct-Q8_0.gguf")
            if (outputFile.exists()) {
                // 如果已经合并过，就不重复合并
                return@launch
            }
            try {
                val bufferSize = 8192
                val buffer = ByteArray(bufferSize)
                // 输出流
                val out = FileOutputStream(outputFile)
                // 按顺序合并三个文件
                for (part in listOf("Qwen3-4B_part_aa-Instruct-Q8_0.gguf", "Qwen3-4B_part_ab-Instruct-Q8_0.gguf", "Qwen3-4B_part_ac-Instruct-Q8_0.gguf")) {
                    val filePart = File(appCtx.filesDir, part)
                    val partInputStream = FileInputStream(filePart)
                    var bytesRead: Int
                    while (partInputStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }

                    partInputStream.close()
                }

                out.flush()
                out.close()

                // 可选：合并完成后删除分片文件
                // File(filesDir, "gemma_a").delete()
                // File(filesDir, "gemma_b").delete()
                // File(filesDir, "gemma_c").delete()

                Log.i("merge","合并完成：$outputFile")

            } catch (e: Exception) {
                Log.i("merge","合并失败：$outputFile")
                e.printStackTrace()
            }
        }
    }

    fun startModelLoading(
        baseModelName: String = "",
        mmprojName: String = ""
    ) {
        _messages.value = listOf(
            ChatMessage("Model : $baseModelName \nMMProj : $mmprojName \n no think :$_noThinkFlag", MessageType.MODEL)
        )
        viewModelScope.launch {
            llmAndroid.unload()
            delay(200)
            load(baseModelName, mmprojName, gpu_layers)
            delay(200)
        }
    }

    fun whisperLoad(audioModelPath:String){
        Log.d(AGENT_LOG_TAG, "Whisper model loading started...")
        try {
            val appCtx = getApplication<Application>()
            val mmprojf = File(appCtx.filesDir, audioModelPath)
            if (!mmprojf.exists()) {
                appCtx.assets.open(audioModelPath)
                    .use { i -> mmprojf.outputStream().use { o -> i.copyTo(o) } }
            }
            viewModelScope.launch {
                delay(200)
                llmAndroid.whisperLoad(mmprojf.absolutePath)
                Log.d(AGENT_LOG_TAG, "Whisper model loading finished.")
                delay(200)
            }

        }catch (exc: Throwable) {
            Log.e(AGENT_LOG_TAG, "whisperLoad: failed ==>"+ exc.message)
        } finally {
            _generating.postValue(false)
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
//                merge()
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
                    temp = 0.5f,
                    topPP = 0.8f,
                )

                //                private var systemPrompt = """你是一个强大的多模态AI助手。你的核心任务是理解并响应用户的需求。
//
//请遵循以下优先级处理用户输入：
//1.  **当用户提供了图片时**：你的首要任务是用自然语言描述图片内容或回答相关问题。**除非用户的文字指令明确要求使用工具**，否则不应调用工具。
//2.  **当没有图片，或用户明确要求执行工具操作时**：判断用户的意图是否与下面列出的某个工具有明确匹配。如果匹配，请生成一个用于调用工具的JSON对象。
//3.  **所有其他情况**：对于普通对话、问候、开玩笑或任何与工具功能无关的请求，请直接用自然语言回复。
//
//**工具调用规则**：
//当你决定调用工具时，必须严格按照MCP协议输出一个JSON对象，不要输出思考过程，也绝对不允许在JSON前后添加任何多余的文字。
//
//**核心规则：处理前置条件**
//某些工具的描述中包含了 `[前置条件: ...]`。在调用这些工具之前，你**必须**首先确保它的前置条件已经被满足。如果前置条件尚未满足（例如，你还不知道今天的日期），你的**唯一任务**就是去调用那个能满足前置条件的工具（例如 `get_current_date`）。**绝对不能**在一次输出中，同时输出多个工具调用。
//
//**核心规则：判断任务完成**
//当工具的返回结果 `[tool_result]` 中包含了明确的成功标识，如 "success", "成功", "已创建", "已完成", "已打开页面" 等关键词时，这代表你的任务已经完成。此时，你**必须**停止调用任何工具，并基于这个成功的结果，为用户生成一个最终的、确认性的自然语言回答。**绝对不能**再次调用相同的或其他的工具。
//
//**核心规则：处理日期**
//你没有任何关于当前日期的先验知识。当用户的请求中包含“今天”、“明天”、“下周三”这样的相对时间描述时，它们本身并不是一个可用的日期。你**必须**首先调用 `get_current_date` 工具来获取当前的绝对日期，然后基于这个结果计算出用户所指的目标日期，才能调用其他工具。
//
//---
//**重要提示：以下示例仅用于说明格式，不应被视为真实对话历史。示例中出现的日期（如 “2099-01-01”）均为虚构，绝不能在实际任务中使用。
//
//# 示例 1: 简单工具调用
//[conversation_history]
//用户: "帮我查一下2025年9月10号有什么安排"
//[your_turn]
//{"tool_name": "get_calendar_events", "arguments": {"date": "2025-09-10"}}
//
//# 示例 2: 无需调用工具 (普通对话)
//[conversation_history]
//用户: "你好"
//[your_turn]
//你好！有什么可以帮你的吗？
//
//# 示例 3: 链式调用的第一步 (信息不足，需获取日期)
//[conversation_history]
//用户: "帮我在明天下午2点安排一个讨论需求的会议，大约持续一小时"
//[your_turn]
//{"tool_name": "get_current_date", "arguments": {}}
//
//# 示例 4: 链式调用的第二步 (已获取日期，继续执行任务)
//[conversation_history]
//用户: "帮我在明天下午2点安排一个讨论需求的会议，大约持续一小时"
//<|im_start|>assistant
//{"tool_name": "get_current_date", "arguments": {}}
//<|im_end|>
//<|im_start|>tool
//[tool_result]
//get_current_date() -> "20xx-01-01" #示例模拟数据，非真实数据
//[/tool_result]
//<|im_end|>
//[your_turn]
//<think>
//我收到了用户的原始请求“明天开会”。我检查了历史记录，发现我已经调用了 get_current_date 并得到了结果 "2099-01-01"。现在 create_calendar_event_api 的日期前置条件已经满足。因此，我可以计算出“明天”是“2099-01-02”，并调用创建工具。
//</think>
//{"tool_name": "create_calendar_event_api", "arguments": {"title": "讨论需求的会议", "start_time": "2099-01-02 14:00:00", "end_time": "2099-01-02 15:00:00"}}
//
//
//# 示例 5: 任务完成
//[conversation_history]
//用户: "帮我在明天下午2点安排一个讨论需求的会议，大约持续一小时"
//[tool_result]
//get_current_date() -> "20xx-01-01"   # 请注意！！！ 这里的是模拟数据，只是为了告诉你多阶段时怎么调用，这里的调用结果错误，请重新调用！
//[/tool_result]
//[tool_code]
//{"tool_name": "create_calendar_event_api", "arguments": {"title": "讨论需求的会议", "start_time": "2099-01-02 14:00:00", "end_time": "2099-01-02 15:00:00"}}
//[/tool_code]
//[tool_result]
//create_calendar_event_api() -> "{\"status\": \"success\", \"message\": \"日程已创建\"}"     # 示例模拟数据，非真实数据
//[/tool_result]
//[your_turn]
//好的，会议已经为您安排在明天下午2点。
//---
//可用的工具列表如下:
//${ToolRegistry.getToolDefinitions()}
//""".trimIndent()
                systemPrompt = """你是一个强大的多模态AI助手。你的核心任务是理解并响应用户的需求。

请遵循以下规则处理用户输入：

**工具调用规则**：
当你决定调用工具时，必须严格按照MCP协议输出一个JSON对象，不要输出思考过程，也绝对不允许在JSON前后添加任何多余的文字。

**用户输入处理规则**
用户的输入可能存在错别字，这种情况下就不要再和用户确认了，直接根据用户的输入进行猜测。

---
可用的工具列表如下:
${ToolRegistry.getToolDefinitions()}

---
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
    fun triggerThink() {
        viewModelScope.launch {
            _noThinkFlag = !_noThinkFlag
            addMessage("think model is :${!_noThinkFlag}", MessageType.MODEL)
        }
    }

    fun startRecording(context: Context) {
        if (_isRecording.value == true) return
        Log.d(AGENT_LOG_TAG, "Start recording...")
        _isRecording.postValue(true)
        recordingJob = viewModelScope.launch {
            var thinkingJob: Job? = null
            try {
                val recordedAudio = recorder.start()
                Log.d(AGENT_LOG_TAG, "Recording finished. Starting transcription...")
                addMessage("", MessageType.USER)
                thinkingJob = showThinkingAnimation("Transcribing")

                val floatArray = recorder.convertPcm16leToFloat32(recordedAudio)
                val transcriptionResult = llmAndroid.transcribe(floatArray, "zh")
                Log.d(AGENT_LOG_TAG, "Transcription finished. Content: $transcriptionResult")
                thinkingJob?.cancel()
                _transcription.postValue(transcriptionResult)
                replaceLastMessage("Voice Message")
                // 5. 自动将转录结果发送给模型
                delay(200)
                executeAgentLoop(transcriptionResult, "", context)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) {
                    // Ignore cancellation exceptions
                } else {
                    Log.e(AGENT_LOG_TAG, "Recording or transcription failed", e)
                    _transcription.postValue("Error: ${e.message}")
                }
            } finally {
                _isRecording.postValue(false)
            }
        }
    }

    fun stopRecording() {
        if (_isRecording.value == false) return
        Log.d(AGENT_LOG_TAG, "Stop recording...")
        recorder.stop()
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
            if(_modelName.startsWith("Qwen3") && _noThinkFlag)userInput += "/no_think"
            llmAndroid.send(userInput, true, imagePathToSend, isMultiModal,isPreformatted = false)
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
            if (parseToolCall(fullResponse,true) == null) {
                replaceLastMessage(cleanedResponse) // Update UI with cleaned response if no tool call
            }

            llmAndroid.supplyMsg(cleanedResponse)
            val toolCall = parseToolCall(fullResponse,true) // Parse from the original response
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

//                    // 3. Second LLM Call (Summarization) with Streaming
//                    val finalPrompt = """
//解读工具调用的结果，并用简体中文给用户一个友好、清晰的最终答复。你的回复必须是纯文本，绝对不允许使用JSON格式。
//工具输出: '${toolResult.output}'
//你的回复:
//""".trimIndent()
//                    Log.d(AGENT_LOG_TAG, "Summarization Prompt sent to LLM.")
//                    val summaryResponseBuilder = StringBuilder()
//                    var firstChunk = true
//                    llmAndroid.send(finalPrompt, true, "", isMultiModal,isPreformatted = false) // No image for summary
//                        .catch { e ->
//                            appendToLastMessage("\nError: ${e.message}"); Log.e(
//                            AGENT_LOG_TAG,
//                            "LLM Summarization call failed",
//                            e
//                        )
//                        }
//                        .collect { chunk ->
//                            if (firstChunk) {
//                                replaceLastMessage(chunk)
//                                firstChunk = false
//                            } else {
//                                appendToLastMessage(chunk)
//                            }
//                            summaryResponseBuilder.append(chunk)
//                        }
//
//                    val finalSummary = summaryResponseBuilder.toString()
//                    val cleanedSummary = finalSummary.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
//                        .replace("<think>","").trim()
//                    replaceLastMessage(cleanedSummary) // Update UI with cleaned summary
//
//                    llmAndroid.supplyMsg(cleanedSummary)
//                    Log.d(
//                        AGENT_LOG_TAG,
//                        "Final summary response: $cleanedSummary"
//                    )
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

    fun testClearCache(toolName: String) {
        viewModelScope.launch {
            val tool = ToolRegistry.getTool(toolName)
            if (tool != null) {
                addMessage("Testing ${tool.name}...", MessageType.MODEL)
                val result = tool.execute(getApplication(), JsonObject())
                replaceLastMessage("Test Result: ${result.output}")
            } else {
                addMessage("Error: Tool '$toolName' not found.", MessageType.MODEL)
            }
        }
    }

    /**
     * 解析模型结果的地方，两个模式，简单模式只匹配输出里是否存在工具名称，调用该工具
     * 解析模式是真正提取模型json结果的解析
     */
    private fun parseToolCall(response: String,simple: Boolean): ToolCall? {
        try {
            // 1. Clean the response by removing any <think>...</think> blocks.
            val cleanedResponse = response.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

            // 2. Find the first opening brace in the cleaned response.
            val jsonStart = cleanedResponse.indexOf('{')
            if (jsonStart == -1) {
                Log.d(AGENT_LOG_TAG, "No JSON object found after cleaning <think> blocks.")
                return null
            }

            var openBraces = 1 // Start with 1 since we found the first brace.
            var jsonEnd = -1

            // 3. Iterate to find the corresponding closing brace.
            for (i in jsonStart + 1 until cleanedResponse.length) {
                when (cleanedResponse[i]) {
                    '{' -> openBraces++
                    '}' -> openBraces--
                }
                if (openBraces == 0) {
                    jsonEnd = i
                    break // Found the end of the JSON object.
                }
            }

            if (jsonEnd == -1) {
                Log.d(AGENT_LOG_TAG, "Incomplete JSON object found in cleaned response.")
                return null
            }

            // 4. Extract and parse the JSON string.
            val jsonString = cleanedResponse.substring(jsonStart, jsonEnd + 1)
            Log.d(AGENT_LOG_TAG, "Attempting to parse JSON: $jsonString")
            val toolCall = gson.fromJson(jsonString, ToolCall::class.java)

            // 5. Basic validation.
            return if (toolCall.tool_name.isNullOrBlank() || toolCall.arguments == null) {
                Log.d(AGENT_LOG_TAG, "Parsed JSON is not a valid ToolCall: $jsonString")
                null
            } else {
                toolCall
            }
        } catch (e: JsonSyntaxException) {
            Log.e(AGENT_LOG_TAG, "Failed to parse JSON object from response: '$response'. Error: ${e.message}")
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

    fun sendWithLoop(context: Context) {
        viewModelScope.launch {
            Log.d(AGENT_LOG_TAG, "Agent loop started.")
            initializingJob?.join()
            val userInput = internalMessage
            if (userInput.isBlank()) return@launch
            internalMessage = ""

            val imagePathToSend = _imagePath.value ?: ""
            _imagePath.postValue("")

            addMessage(userInput, MessageType.USER)
            if (imagePathToSend.isNotEmpty()) addMessage("<ImagePath>$imagePathToSend", MessageType.USER)

            executeAgentLoop(userInput, imagePathToSend, context)
        }
    }

    private suspend fun executeAgentLoop(userInput: String, imagePath: String, context: Context) {
        Log.d(AGENT_LOG_TAG, "Agent loop started for input: $userInput")
        initializingJob?.join()
        _generating.postValue(true)

        val conversationHistory = mutableListOf<AgentChatMessage>()
        conversationHistory.add(AgentChatMessage(AgentMessageRole.USER, userInput))

        var loopCount = 0
        val maxLoops = 1

        while (loopCount < maxLoops) {
            loopCount++
            Log.d(AGENT_LOG_TAG, "Loop #$loopCount")

            addMessage("", MessageType.MODEL)
            val thinkingJob = showThinkingAnimation("Thinking")

            val responseBuilder = StringBuilder()
            var firstChunkReceived = false

            val currentTurnPrompt = buildChatMLPrompt(conversationHistory)
            Log.d(AGENT_LOG_TAG, "Loop #$loopCount Prompt:\n$currentTurnPrompt")

            val finalPrompt = if (loopCount == 1 && _modelName.startsWith("Qwen3") && _noThinkFlag) {
                currentTurnPrompt.replace("<|im_start|>assistant", "<|im_start|>assistant/no_think")
            } else {
                currentTurnPrompt
            }

            llmAndroid.send(finalPrompt, true, if(loopCount == 1) imagePath else "", isMultiModal, isPreformatted = true)
                .catch { e ->
                    thinkingJob.cancel()
                    appendToLastMessage("Error: ${e.message}")
                    Log.e(AGENT_LOG_TAG, "LLM call failed in loop #$loopCount", e)
                    loopCount = maxLoops // End loop
                }
                .collect { chunk ->
                    if (!firstChunkReceived) {
                        thinkingJob.cancel()
                        replaceLastMessage("")
                        firstChunkReceived = true
                    }
                    appendToLastMessage(chunk)
                    responseBuilder.append(chunk)
                }
            if (!firstChunkReceived) thinkingJob.cancel()

            val fullResponse = responseBuilder.toString()
            Log.d(AGENT_LOG_TAG, "Loop #$loopCount Raw Response: $fullResponse")

            val cleanedResponse = fullResponse.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim().replace("<think>", "").trim()
            val toolCall = parseToolCall(fullResponse,false)
            conversationHistory.add(AgentChatMessage(AgentMessageRole.ASSISTANT, cleanedResponse))

            if (toolCall != null) {
                replaceLastMessage("正在调用工具: ${toolCall.tool_name}...")
                val tool = ToolRegistry.getTool(toolCall.tool_name)
                if (tool != null) {
                    val toolResult = withContext(Dispatchers.IO) { tool.execute(context, toolCall.arguments) }
                    val toolResultText = "[tool_result]\n${toolCall.tool_name}() -> ${toolResult.output}\n[/tool_result]"
                    Log.d(AGENT_LOG_TAG, "Loop #$loopCount: Tool result: $toolResultText")
                    
                    conversationHistory.add(AgentChatMessage(AgentMessageRole.TOOL, toolResultText))

                    appendToLastMessage("\n工具返回: ${toolResult.output}")
                    llmAndroid.supplyMsg(cleanedResponse)
                    llmAndroid.supplyMsg(toolResultText)
                    continue
                } else {
                    val errorMsg = "错误: 未找到工具 '${toolCall.tool_name}'."
                    appendToLastMessage(errorMsg)
                    llmAndroid.supplyMsg(errorMsg)
                    break
                }
            } else {
                Log.d(AGENT_LOG_TAG, "Loop #$loopCount: No tool call detected. Assuming final answer.")
                replaceLastMessage(cleanedResponse)
                llmAndroid.supplyMsg(cleanedResponse)
                break
            }
        }

        if (loopCount >= maxLoops) {
            Log.w(AGENT_LOG_TAG, "Max loops reached. Exiting.")
//            appendToLastMessage("\n(已达到最大尝试次数)")
        }

        _generating.postValue(false)
        Log.d(AGENT_LOG_TAG, "Agent loop finished.")
    }

    private fun buildChatMLPrompt(history: List<AgentChatMessage>): String {
        return history.joinToString("\n") { msg ->
            "<|im_start|>${msg.role.name.lowercase()}\n${msg.content}<|im_end|>"
        } + "\n<|im_start|>assistant"
    }
    private fun showThinkingAnimation(baseText: String = "Thinking"): Job {
        return viewModelScope.launch {
            var dots = 0
            while (true) {
                replaceLastMessage(baseText + ".".repeat(dots % 4))
                delay(500)
                dots++
            }
        }
    }
}
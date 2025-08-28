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
    private val _messages = MutableLiveData<List<ChatMessage>>(listOf(ChatMessage("Initializing...", MessageType.MODEL)))
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _imagePath = MutableLiveData("")
    val imagePath: LiveData<String> = _imagePath

    private val _generating = MutableLiveData(true)
    val generating: LiveData<Boolean> = _generating

    private var internalMessage: String = ""
    private var initializingJob: Job? = null
    private val gson = Gson()

    // 当前模型是否多模态
    private var isMultiModal: Boolean=false

    private data class ToolCall(val tool_name: String, val arguments: JsonObject)

    init {
        ToolRegistry.register(CreateCalendarEventTool())
        ToolRegistry.register(GetCalendarEventsTool())
    }

    fun startModelLoading(baseModelName:String="InternVL3-2B-Instruct-Q8_0.gguf",mmprojName:String="mmproj-InternVL3-2B-Instruct-Q8_0.gguf"){
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
        delay(100)
        val currentList = _messages.value ?: emptyList()
        _messages.postValue(currentList + ChatMessage(text, type))
    }

    private suspend fun appendToLastMessage(chunk: String) {
        delay(100)
        val currentList = _messages.value ?: emptyList()
        if (currentList.isNotEmpty()) {
            val lastMessage = currentList.last()
            val updatedList = currentList.dropLast(1) + lastMessage.copy(text = lastMessage.text + chunk)
            _messages.postValue(updatedList)
        }
    }
    
    private suspend fun replaceLastMessage(newText: String) {
        delay(100)
        val currentList = _messages.value ?: emptyList()
        if (currentList.isNotEmpty()) {
            val lastMessage = currentList.last()
            val updatedList = currentList.dropLast(1) + lastMessage.copy(text = newText)
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
                while(true){
                    replaceLastMessage("initializing model"+".".repeat(dots%7))
                    delay(500)
                    dots++
                }
            }
            try {
                val appCtx = getApplication<Application>()
                val mmprojf = File(appCtx.filesDir, mmprojName)
                if (!mmprojf.exists()) { appCtx.assets.open(mmprojName).use { i -> mmprojf.outputStream().use { o -> i.copyTo(o) } } }
                val modelFile = File(appCtx.filesDir, modelName)
                if (!modelFile.exists() || modelFile.length() == 0L) { 
                    appCtx.assets.open(modelName).use { i -> modelFile.outputStream().use { o -> i.copyTo(o) } } 
                }
                // TODO 目前根据模型名称硬编码
                val isGreedy = when(modelName) {  // 是否启用贪婪采样
                    "InternVL3-2B-Instruct-Q8_0.gguf" -> true
                    else -> false
                }
                isMultiModal = when(mmprojName) {
                    "" -> false
                    else -> true
                }
                llmAndroid.load(modelFile.absolutePath, layers, mmprojf.absolutePath, mmproj_use_gpu,isGreedy,isMultiModal)

                val systemPrompt = """ 你是一个能干的 AI 助手。你的任务是判断用户的意图。只有当用户的意图明确匹配以下可用工具之一时，你才应该生成工具调用的JSON。对于其他所有情况，包括但不限于闲聊、打招呼、问候、讲笑话、或者任何与工具功能无关的请求，你都必须直接用自然语言回答，绝对不能生成JSON.
                    当你决定调用工具时，请严格按照 MCP 协议输出一个 JSON 对象。不要在JSON前后添加任何多余的文字。
                    --- 示例开始 ---
                    示例1：需要调用工具
                    用户提问: "帮我查一下今天的日程"
                    你的回答: {"tool_name": "get_calendar_events", "arguments": {"date": "2025-08-28"}}

                    示例2：不需要调用工具
                    用户提问: "你好啊"
                    你的回答: "你好！有什么可以帮你的吗？"

                    示例3：需要调用工具  
                    用户提问: "创建一个日程，明天下午3点开会"
                    你的回答: {"tool_name": "create_calendar_event", "arguments": {"title": "开会", "start_time": "2025-08-08T15:00:00"}}

                    示例4：不需要调用工具
                    用户提问: "你觉得今天天气怎么样？"
                    你的回答: "抱歉，我无法获取天气信息，但我可以帮你管理日程。"
                    --- 示例结束 ---
                    可用的工具列表如下:
                    ${ToolRegistry.getToolDefinitions()}
                    """.trimIndent()
                llmAndroid.setSystemPrompt(systemPrompt)
                llmAndroid.initSystemPrompt()
                animationJob.cancel()
                addMessage("Loaded ${modelFile.name}", MessageType.MODEL)
                addMessage(llmAndroid.sysinfo(), MessageType.MODEL)
            } catch (exc: Throwable) {
                animationJob.cancel()
                addMessage("failed!! ${exc.message}", MessageType.MODEL)
            } finally {
                _generating.postValue(false)
            }
        }
    }

    // --- AGENT LOGIC ---
    fun send(context: Context) {
        viewModelScope.launch {
            Log.d(AGENT_LOG_TAG, "Agent loop started.")
            initializingJob?.join()
            val userInput = internalMessage
            if (userInput.isBlank()) return@launch
            internalMessage = ""

            val imagePathToSend = _imagePath.value ?: ""
            _imagePath.postValue("") // Clear image after sending

            addMessage(userInput, MessageType.USER)
            if(imagePathToSend.isNotEmpty()) addMessage("<ImagePath>$imagePathToSend", MessageType.USER)
            addMessage("", MessageType.MODEL)
            _generating.postValue(true)

            var thinkingJob: Job? = viewModelScope.launch {
                var dots = 0
                while (true) {
                    val thinkingText = "model is thinking" + ".".repeat(dots % 4)
                    val current = _messages.value ?: emptyList()
                    if (current.isNotEmpty()) {
                        _messages.postValue(current.dropLast(1) + current.last().copy(text = thinkingText))
                    }
                    delay(500)
                    dots++
                }
            }

            val responseBuilder = StringBuilder()
            var firstChunkReceived = false
            llmAndroid.send(userInput, true, imagePathToSend,isMultiModal)
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
            llmAndroid.supplyMsg(fullResponse)
            val toolCall = parseToolCall(fullResponse)
            Log.d(AGENT_LOG_TAG, "toolCall:${toolCall}")

            if (toolCall != null) {
                Log.d(AGENT_LOG_TAG, "Tool call parsed: $toolCall")
//                addMessage("", MessageType.MODEL) // New message for tool result
                replaceLastMessage("Tool call detected: ${toolCall.tool_name}. Executing...")
                val tool = ToolRegistry.getTool(toolCall.tool_name)
                if (tool != null) {
                    val toolResult = withContext(Dispatchers.IO) { tool.execute(context, toolCall.arguments) }
                    Log.d(AGENT_LOG_TAG, "Tool execution result: $toolResult")
                    appendToLastMessage("\nTool result: ${toolResult.output}\nSummarizing...")

                    // 3. Second LLM Call (Summarization) with Streaming
                    val finalPrompt = "工具 ${tool.name} 的执行结果是: '${toolResult.output}'. 请根据这个结果，给用户一个友好的、最终的回复。"
                    Log.d(AGENT_LOG_TAG, "Summarization Prompt sent to LLM.")
                    val summaryResponseBuilder = StringBuilder()
                    var firstChunk = true
                    llmAndroid.send(finalPrompt, true, "",isMultiModal) // No image for summary
                        .catch { e -> appendToLastMessage("\nError: ${e.message}"); Log.e(AGENT_LOG_TAG, "LLM Summarization call failed", e) }
                        .collect{ chunk ->
                            if(firstChunk) {
                                replaceLastMessage(chunk)
                                firstChunk = false
                            } else {
                                appendToLastMessage(chunk)
                            }
                            summaryResponseBuilder.append(chunk)
                        }
                    llmAndroid.supplyMsg(summaryResponseBuilder.toString())
                    Log.d(AGENT_LOG_TAG, "Final summary response: ${summaryResponseBuilder.toString()}")
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
        return try {
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) return null

            val jsonString = response.substring(jsonStart, jsonEnd + 1)
            val toolCall = gson.fromJson(jsonString, ToolCall::class.java)
            if (toolCall.tool_name.isNullOrBlank()) null else toolCall
        } catch (e: JsonSyntaxException) {
            Log.d(AGENT_LOG_TAG, "Not a valid tool call JSON: $response")
            null
        }
    }

    fun clearHistoryAndKV(){
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

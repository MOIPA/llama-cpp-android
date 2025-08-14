package cn.com.zte.app.demollm

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val llmAndroid: LLMAndroid = LLMAndroid.instance()
    private val gpu_layers = 0
    private val mmproj_use_gpu = 0

    companion object {
        @JvmStatic
        private val NanosPerSecond = 1_000_000_000.0
    }

    // --- LiveData Definitions using ChatMessage ---
    private val _messages = MutableLiveData<List<ChatMessage>>(listOf(ChatMessage("Initializing...", MessageType.MODEL)))
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _imagePath = MutableLiveData("")
    val imagePath: LiveData<String> = _imagePath

    private val _generating = MutableLiveData(true)
    val generating: LiveData<Boolean> = _generating


    private var internalMessage: String = ""
    private var initializingJob: Job? = null

//    init {
//        _messages.value = listOf(
//            ChatMessage("Model : InternVL3-2B-Instruct-Q8_0", MessageType.MODEL)
//        )
//        load("InternVL3-2B-Instruct-Q8_0.gguf", "mmproj-InternVL3-2B-Instruct-Q8_0.gguf", gpu_layers)
//    }

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

    private fun addMessage(text: String, type: MessageType) {
        val current = _messages.value ?: emptyList()
        _messages.postValue(current + ChatMessage(text, type))
    }

    fun load(modelName: String, mmprojName: String, layers: Int = 0) {
        initializingJob?.cancel()

        initializingJob = viewModelScope.launch {
            _generating.postValue(true)

            val animationJob = launch {
                delay(500)
                var dots = 0
                while(true){
                    var text = "initializing model"+".".repeat(dots%7)
                    val current = _messages.value?:emptyList()
                    withContext(Dispatchers.Main) {
                        _messages.value = current.dropLast(1)+current.last().copy(text=text)
                    }
                    delay(500)
                }
            }

            try {
                val appCtx = getApplication<Application>()
                val mmprojf = File(appCtx.filesDir, mmprojName)
                if (!mmprojf.exists()) {
                    addMessage("copying mmproj model", MessageType.MODEL)
                    appCtx.assets.open(mmprojName).use { i -> mmprojf.outputStream().use { o -> i.copyTo(o) } }
                    addMessage("copied mmproj model", MessageType.MODEL)
                }
                val modelFile = File(appCtx.filesDir, modelName)
                if (!modelFile.exists()) {
                    addMessage("coping model", MessageType.MODEL)
                    appCtx.assets.open(modelName).use { i -> modelFile.outputStream().use { o -> i.copyTo(o) } }
                    addMessage("Copied $modelName to ${modelFile.absolutePath}", MessageType.MODEL)
                } else {
                    addMessage("model exists", MessageType.MODEL)
                }
                llmAndroid.load(modelFile.absolutePath, layers, mmprojf.absolutePath, mmproj_use_gpu)
                val sysInfo = llmAndroid.sysinfo()
                
                animationJob.cancel()
                val currentMessages = _messages.value ?: emptyList()
                val finalMessages = currentMessages +
                        ChatMessage("Loaded ${modelFile.absolutePath}", MessageType.MODEL) +
                        ChatMessage(sysInfo, MessageType.MODEL)
                Log.d("MainViewModel", "finalMessages: $finalMessages")
                _messages.postValue(finalMessages)

            } catch (exc: Throwable) {
                animationJob.cancel()
                Log.e("MainViewModel", "load() failed", exc)
                addMessage("failed!! ${exc.message}", MessageType.MODEL)
            } finally {
                _generating.postValue(false)
                addMessage("load completed", MessageType.MODEL)
            }
        }
    }

    fun send() {
        viewModelScope.launch {
            initializingJob?.cancel()
            initializingJob?.join()

            val text = internalMessage
            if (text.isBlank()) return@launch
            internalMessage = ""

            val current = _messages.value ?: emptyList()
            var newMessages = current + ChatMessage(text, MessageType.USER)
            val currentImagePath = _imagePath.value
            if (!currentImagePath.isNullOrEmpty()) {
                newMessages = newMessages + ChatMessage("<ImagePath>$currentImagePath", MessageType.USER)
            }
            newMessages = newMessages + ChatMessage("", MessageType.MODEL)
            Log.d("MainViewModel", "newMessages: $newMessages")
            _messages.postValue(newMessages)

            _generating.postValue(true)

            var thinkingJob: Job? = null

            val imagePathToSend = _imagePath.value ?: ""
            _imagePath.postValue("")

            delay(200)

            thinkingJob = viewModelScope.launch {
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

            var firstChunkReceived = false
            llmAndroid.send(text, true, imagePathToSend).catch {
                Log.e("MainViewModel", "Error sending message", it)
                thinkingJob?.cancel()
                thinkingJob?.join()
                val current = _messages.value ?: emptyList()
                if (current.isNotEmpty()) {
                    _messages.postValue(current.dropLast(1) + current.last().copy(text = it.message ?: "Unknown error"))
                }
            }.collect {
                if (!firstChunkReceived) {
                    thinkingJob?.cancel()
                    thinkingJob?.join()
                    firstChunkReceived = true
                    val current = _messages.value ?: emptyList()
                    if (current.isNotEmpty()) {
                        _messages.postValue(current.dropLast(1) + current.last().copy(text = it))
                    }
                } else {
                    val current = _messages.value ?: emptyList()
                    if (current.isNotEmpty()) {
                        val lastMessage = current.last()
                        _messages.postValue(current.dropLast(1) + lastMessage.copy(text = lastMessage.text + it))
                    }
                }
            }
            thinkingJob?.cancel()
            thinkingJob?.join()
            _generating.postValue(false)
            (_messages.value?.lastOrNull())?.let { llmAndroid.supplyMsg(it.text) }
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
                addMessage("Benching",MessageType.MODEL)
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
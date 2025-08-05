package com.example.myllm

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val llmAndroid: LLMAndroid = LLMAndroid.instance()
    private val gpu_layers = 0
    private val mmproj_use_gpu = 0
    private val testPic = "300k.jpg"

    companion object {
        @JvmStatic
        private val NanosPerSecond = 1_000_000_000.0
    }

    init {
//        load("smol.gguf", gpu_layers) // smol256m q8_0
//        load("qwen.gguf", gpu_layers)  qwen2.5-1.5b-Q8_0
//        load("qwen3.gguf","",gpu_layers) // qwen3-1.7b Q4_K_M
//        load("qwen3-q40.gguf", gpu_layers) // qwen3-1.5b Q4_0
//        load("Llama-3.2-1BQ4_0.gguf",gpu_layers)

        // 视觉测试
//        load("Qwen2.5-VL-3B-Q4_0.gguf", "mmpro-qwen2.5.gguf",gpu_layers)
        load("InternVL3-2B-Instruct-Q8_0.gguf", "mmproj-InternVL3-2B-Instruct-Q8_0.gguf",gpu_layers)
//        load("SmolVLM2-500M-Video-Instruct-Q8_0.gguf", "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",gpu_layers)
//        load("gemma-3-4b-it-Q4_K_M.gguf", "mmproj-model-f16.gguf",gpu_layers)
//        merge()
    }

    fun merge() {
        viewModelScope.launch {

        val appCtx = application.applicationContext
        val outputFile = File(appCtx.filesDir, "gemma-3-4b-it-Q4_K_M.gguf")

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
            for (part in listOf("gemma_part_aa", "gemma_part_ab", "gemma_part_ac")) {
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

    private val tag: String? = this::class.simpleName
    var messages by mutableStateOf(listOf("Initializing..."))
        private set
    var message by mutableStateOf("")
        private set
    var imagePath by mutableStateOf("")
        private set

    fun clearImage(){
        this.imagePath = ""
    }

    fun updateMessage(message: String) {
        this.message = message;
    }

    fun clear() {
        this.messages = listOf()
    }

    fun log(message: String) {
        messages += message
    }

    fun load(modelName: String, mmprojName:String,layers: Int = 0) {
        viewModelScope.launch {
            try {
                // 加载前判断模型是否存在，不存在从assets拷贝
                val appCtx = application.applicationContext

//                //测试代码，拷贝测试图片 10k.jpeg
//                val picf = File(appCtx.filesDir, testPic)
//                if(!picf.exists()){
//                    withContext(Dispatchers.Main) {
//                        messages += "copying pic"
//                    }
//                    appCtx.assets.open("testPic/$testPic").use { inputStream ->
//                        picf.outputStream().use { outputStream ->
//                            inputStream.copyTo(outputStream)
//                        }
//                    }
//                    withContext(Dispatchers.Main) {
//                        messages += "copied pic"
//                    }
//                }
                // 加载视觉模型
                val mmprojf = File(appCtx.filesDir, mmprojName)
                if(!mmprojf.exists()){
                    withContext(Dispatchers.Main) {
                        messages += "copying mmproj model"
                    }
                    appCtx.assets.open(mmprojName).use { inputStream ->
                        mmprojf.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        messages += "copied mmproj model"
                    }
                }
                // 基础语言模型
                val modelFile = File(appCtx.filesDir, modelName)
                if (!modelFile.exists()) {
                    withContext(Dispatchers.Main) {
                        messages += "coping model"
                    }
                    appCtx.assets.open(modelName).use { inputStream ->
                        modelFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        withContext(Dispatchers.Main) {
                            messages += "Copied $modelName to ${modelFile.absolutePath}"
                        }
                        Log.d(tag, "Model copied to ${modelFile.absolutePath}")
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        messages += "model exists"
                    }
                }
                llmAndroid.load(modelFile.absolutePath, layers,mmprojf.absolutePath,mmproj_use_gpu)
                val sysInfo = llmAndroid.sysinfo()
                withContext(Dispatchers.Main) {
                    messages += "Loaded ${modelFile.absolutePath}"
                    messages += sysInfo
                }
            } catch (exc: Throwable) {
                Log.e(tag, "load() failed", exc)
                withContext(Dispatchers.Main) {
                    messages += "failed!!"
                    messages += exc.message!!
                }
            } finally {
                withContext(Dispatchers.Main) {
                    messages += "load complete"
                }
            }
        }
    }

    fun send() {
        val text = message
        message = "";
        messages += text
        if(imagePath!="")messages += "<ImagePath>$imagePath"
        messages += ""
        viewModelScope.launch {
            val imagePathToSend = imagePath
            imagePath = ""
            // 测试代码，加载图片给消息生成 后期删除 @TODO
//            val appCtx = application.applicationContext
////            val picf = File(appCtx.filesDir, "3m.jpg")
//            val picf = File(appCtx.filesDir, testPic)
            llmAndroid.send(text,true,imagePathToSend).catch {
                Log.e("MainViewModel", "Error sending message", it)
                messages += it.message!!
            }.collect {
                messages = messages.dropLast(1) + (messages.last() + it)
            }
//            Log.i("MainViewModel", "Sent message: ${messages.last()}")
            // 模型response添加到会话历史
            Log.i("send","生成结束")
            llmAndroid.supplyMsg(messages.last())
        }
    }

    fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        viewModelScope.launch {
            try {
                val start = System.nanoTime()
                val warmupResult = llmAndroid.bench(pp, tg, pl, nr)
                val end = System.nanoTime()
                messages += warmupResult
                val warmup = (end - start).toDouble() / NanosPerSecond
                messages += "Warm up time: $warmup seconds, please wait..."
                if (warmup > 5.0) {
                    messages += "Warm up took too long, aborting benchmark"
                    return@launch
                }
                messages += llmAndroid.bench(pp, tg, pl, nr)
            } catch (exc: IllegalStateException) {
                Log.e(tag, "bench() failed", exc)
                messages += exc.message!!
            }
        }
    }

    /**
     * 无法直接打开系统相册，Uri是虚拟地址，需要进行拷贝，拷贝成临时文件在File打开
     */
    fun uriToFile(context: Context, uri: Uri): File {
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

    fun uploadImage(uri: Uri){
        try{
            Log.i("MainViewModel", uri.path.toString())
            val appCtx = application.applicationContext
            val file = uriToFile(appCtx,uri)
            this.imagePath = file.absolutePath
        }catch (e: Exception){
            Log.i("MainViewModel",e.message.toString())
        }
    }
}
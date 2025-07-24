package com.example.myllm

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import com.example.myllm.LLMAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application): AndroidViewModel(application) {

    private val llmAndroid: LLMAndroid= LLMAndroid.instance()

    companion object {
        @JvmStatic
        private val NanosPerSecond = 1_000_000_000.0
    }
    init{
        load("smol.gguf")
    }
    private val tag: String? = this::class.simpleName
    var messages by mutableStateOf(listOf("Initializing..."))
        private set
    var message by mutableStateOf("")
        private set
    fun updateMessage(message: String) {
        this.message = message;
    }
    fun clear(){
        this.messages = listOf()
    }
    fun log(message:String){
        messages += message
    }

    fun load(modelName: String) {
        viewModelScope.launch {
            try {
                // 加载前判断模型是否存在，不存在从assets拷贝
                val appCtx = application.applicationContext
                val modelFile = File(appCtx.filesDir,modelName)
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
                }else{
                    withContext(Dispatchers.Main) {
                        messages+="model exists"
                    }
                }
                llmAndroid.load(modelFile.absolutePath)
                withContext(Dispatchers.Main) {
                    messages += "Loaded ${modelFile.absolutePath}"
                }
            } catch (exc: Throwable) {
                Log.e(tag, "load() failed", exc)
                withContext(Dispatchers.Main) {
                    messages += "failed!!"
                    messages += exc.message!!
                }
            }finally {
                withContext(Dispatchers.Main) {
                    messages += "load complete"
                }
            }
        }
    }
    fun send(){
        val text = message;
        message = "";
        messages+=text;
        messages += ""
        viewModelScope.launch {
            llmAndroid.send(text).catch {
                Log.e("MainViewModel", "Error sending message", it)
                messages+=it.message!!
            }.collect {
                messages = messages.dropLast(1)+(messages.last()+it)
            }
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
                messages += llmAndroid.bench(512, 128, 1, 3)
            } catch (exc: IllegalStateException) {
                Log.e(tag, "bench() failed", exc)
                messages += exc.message!!
            }
        }
    }

}
package cn.com.zte.app.demollm
import android.content.Context
import android.util.Log
import cn.com.zte.app.demollm.DemoLLMApiUtils.APP_DEMO_LLM_SERVICE
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import cn.com.zte.router.demollm.*
import com.alibaba.android.arouter.facade.annotation.Route

@Route(path = APP_DEMO_LLM_SERVICE)
class LLMAndroid(): DemoLLMInterface {
    private val tag: String? = this::class.simpleName
    private var systemPrompt: String = ""

    fun getSystemPrompt(): String {
        return this.systemPrompt
    }

    private val threadLocalState: ThreadLocal<State> = object : ThreadLocal<State>() {
        override fun initialValue(): State {
            return State.Idle
        }
    }

    private val whisperThreadLocalState: ThreadLocal<WhisperState> = object : ThreadLocal<WhisperState>() {
        override fun initialValue(): WhisperState {
            return WhisperState.Idle
        }
    }

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Dedicated thread for native code: ${Thread.currentThread().name}")

            // No-op if called more than once.
//            System.loadLibrary("OpenCL")
//            System.loadLibrary("llm")
            System.loadLibrary("demollm-native-lib")

            // Set llama log handler to Android
            log_to_android()
            backend_init(false)

            Log.d(tag, system_info())

            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                Log.e(tag, "Unhandled exception", exception)
            }
        }
    }.asCoroutineDispatcher()

    private val nlen: Int = 4096

    private external fun log_to_android()
    private external fun load_model(
        filename: String,
        layers: Int = 0,
        mmprojf: String,
        useGpu: Int = 0,
        isMultiModal:Boolean
    ): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(isGreedy: Boolean=false,
        minPP: Int=0,
        minPMinKeep: Int=1,
        temp: Float=0.6f,
        topK: Int=20,
        topPP: Float=0.95f,
        topPMinKeep: Int=1): Long
    private external fun free_sampler(sampler: Long)
    private external fun bench_model(
        context: Long,
        model: Long,
        batch: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

    private external fun system_info(): String
    private external fun completion_init_vision(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int,
        picf:String,
        isPreformatted: Boolean
    ): Int
    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int,
        isPreformatted: Boolean
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int
    ): String?

    private external fun kv_cache_clear(context: Long)

    private external fun supply(resp: String,model:Long)

    private external fun set_system_prompt(prompt: String)
    private external fun init_system_prompt(context_pointer: Long, model_pointer: Long)

    // Whisper native methods
    private external fun whisper_init_context_from_file(model_path_str: String): Long
    private external fun whisper_free_context(context_ptr: Long)
    private external fun whisper_full_transcribe(context_ptr: Long, num_threads: Int, audio_data: FloatArray, language: String): Int
    private external fun whisper_get_n_segments(context_ptr: Long): Int
    private external fun whisper_get_segment_text(context_ptr: Long, index: Int): String

    override suspend fun sysinfo(): String {
        return withContext(runLoop) {
            system_info()
        }
    }

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    Log.d(tag, "bench(): $state")
                    bench_model(state.context, state.model, state.batch, pp, tg, pl, nr)
                }

                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    override suspend fun load(pathToModel: String, layers: Int, mmprojf: String, useGpu: Int,isMultiModal: Boolean,
                              isGreedy: Boolean,minPP:Int,minPMinKeep:Int,temp:Float,topK:Int,topPP:Float,topPMinKeep:Int) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel,layers,mmprojf,useGpu,isMultiModal) // 默认值
                    if (model == 0L)  throw IllegalStateException("load_model() failed")

                    val context = new_context(model)
                    if (context == 0L) throw IllegalStateException("new_context() failed")

                    val batch = new_batch(512, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler(isGreedy,minPP,minPMinKeep,temp,topK,topPP,topPMinKeep)
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    Log.i("MainViewModel", "Loaded model $pathToModel")
                    threadLocalState.set(State.Loaded(model, context, batch, sampler))
                }
                else -> throw IllegalStateException("Model already loaded")
            }
        }
    }

    fun clearHistoryAndKV(){
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                kv_cache_clear(state.context)
            }
            else -> {}
        }
    }

    override fun send(message: String, formatChat: Boolean, picf:String,isMultiModal: Boolean, isPreformatted: Boolean): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                var ncur = when(isMultiModal){
                    true -> completion_init_vision(state.context, state.batch, message, formatChat, nlen,picf, isPreformatted)
                    false -> completion_init(state.context, state.batch, message, formatChat, nlen, isPreformatted)
                }
                while (ncur <= nlen) {
                    ncur++
                    val str = completion_loop(state.context, state.batch, state.sampler, nlen)
                    if (str == null) {
                        break
                    }
                    emit(str)
                }
                Log.i("LLMAndroid", "DONE!!!")
//                kv_cache_clear(state.context)
            }
            else -> {}
        }
    }.flowOn(runLoop)

    override suspend fun supplyMsg(resp:String){
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    Log.d(tag, "supplyMsg(): $state resp: $resp")
                    supply(resp, state.model)
                }
                else -> throw IllegalStateException("No model loaded failed to supply msg")
            }
        }
    }

    suspend fun setSystemPrompt(prompt: String) {
        withContext(runLoop) {
            set_system_prompt(prompt)
        }
    }

    suspend fun initSystemPrompt() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    init_system_prompt(state.context, state.model)
                    Log.i("MainViewModel","init_system_prompt")
                }
                else -> {}
            }
        }
    }

    /**
     * Unloads the model and frees resources.
     *
     * This is a no-op if there's no model loaded.
     */
    override suspend fun unload() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    free_context(state.context)
                    free_model(state.model)
                    free_batch(state.batch)
                    free_sampler(state.sampler)

                    threadLocalState.set(State.Idle)
                    Log.i("MainViewModel","Model Unloaded")
                }
                else -> {}
            }
        }
    }

    suspend fun whisperLoad(pathToModel: String) {
        withContext(runLoop) {
            when (whisperThreadLocalState.get()) {
                is WhisperState.Idle -> {
                    val context = whisper_init_context_from_file(pathToModel)
                    if (context == 0L) throw IllegalStateException("whisper_init_context_from_file() failed")
                    whisperThreadLocalState.set(WhisperState.Loaded(context))
                    Log.i(tag, "Whisper model loaded: $pathToModel")
                }
                else -> throw IllegalStateException("Whisper model already loaded")
            }
        }
    }

    suspend fun whisperUnload() {
        withContext(runLoop) {
            when (val state = whisperThreadLocalState.get()) {
                is WhisperState.Loaded -> {
                    whisper_free_context(state.context)
                    whisperThreadLocalState.set(WhisperState.Idle)
                    Log.i(tag, "Whisper model unloaded")
                }
                else -> {}
            }
        }
    }

    suspend fun transcribe(audioData: FloatArray, language: String = "en"): String {
        return withContext(runLoop) {
            when (val state = whisperThreadLocalState.get()) {
                is WhisperState.Loaded -> {
                    val nThreads = 4 // Or get from somewhere else
                    val result = whisper_full_transcribe(state.context, nThreads, audioData, language)
                    if (result != 0) {
                        throw RuntimeException("Transcription failed with code $result")
                    }
                    val nSegments = whisper_get_n_segments(state.context)
                    val text = StringBuilder()
                    for (i in 0 until nSegments) {
                        text.append(whisper_get_segment_text(state.context, i))
                    }
                    text.toString()
                }
                else -> throw IllegalStateException("Whisper model not loaded")
            }
        }
    }

    override fun init(context: Context?) {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        TODO("Not yet implemented")
    }

    companion object {
        private class IntVar(value: Int) {
            @Volatile
            var value: Int = value
                private set

            fun inc() {
                synchronized(this) {
                    value += 1
                }
            }
        }

        private sealed interface State {
            data object Idle: State
            data class Loaded(val model: Long, val context: Long, val batch: Long, val sampler: Long): State
        }

        private sealed interface WhisperState {
            data object Idle: WhisperState
            data class Loaded(val context: Long): WhisperState
        }

        // Enforce only one instance of Llm.
        private val _instance: LLMAndroid = LLMAndroid()

        fun instance(): LLMAndroid = _instance
    }
}
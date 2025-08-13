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

    private val threadLocalState: ThreadLocal<State> = object : ThreadLocal<State>() {
        override fun initialValue(): State {
            return State.Idle
        }
    }
    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Dedicated thread for native code: ${Thread.currentThread().name}")

            // No-op if called more than once.
//            System.loadLibrary("OpenCL")
            System.loadLibrary("llm")

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

    private val nlen: Int = 512

    private external fun log_to_android()
    private external fun load_model(filename: String,layers:Int=0,mmprojf:String,useGpu: Int=0): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
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
        picf:String
    ): Int
    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int
    ): String?

    private external fun kv_cache_clear(context: Long)

    private external fun supply(resp: String,model:Long)

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

    override suspend fun load(pathToModel: String, layers: Int, mmprojf: String, useGpu: Int) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel,layers,mmprojf,useGpu)
                    if (model == 0L)  throw IllegalStateException("load_model() failed")

                    val context = new_context(model)
                    if (context == 0L) throw IllegalStateException("new_context() failed")

                    val batch = new_batch(512, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler()
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    Log.i(tag, "Loaded model $pathToModel")
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

    override fun send(message: String, formatChat: Boolean, picf:String): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                var ncur = completion_init_vision(state.context, state.batch, message, formatChat, nlen,picf)
//                val ncur = IntVar(completion_init(state.context, state.batch, message, formatChat, nlen))
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
                    free_sampler(state.sampler);

                    threadLocalState.set(State.Idle)
                }
                else -> {}
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

        // Enforce only one instance of Llm.
        private val _instance: LLMAndroid = LLMAndroid()

        fun instance(): LLMAndroid = _instance
    }
}
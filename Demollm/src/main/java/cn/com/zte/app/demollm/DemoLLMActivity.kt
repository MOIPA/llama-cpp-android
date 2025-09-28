package cn.com.zte.app.demollm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.util.Log

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.com.zte.account.AccountApiUtils
import cn.com.zte.app.demollm.databinding.ActivityDemollmBinding
import cn.com.zte.framework.base.templates.BaseActivity
import cn.com.zte.zmail.lib.calendar.*
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.zte.app.common.db.database.dao.EventInfoDBDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Route(path = "/demollm/main")
class DemoLLMActivity : BaseActivity() {

    private val viewModel by viewModels<MainViewModel>()
    private lateinit var binding: ActivityDemollmBinding
    private lateinit var messageAdapter: MessageAdapter

    private val models = mapOf(
        "Qwen3-1.7B" to "",   // 纯文本模型，不支持多模态
        "270m-router-final" to "",   // 纯文本模型，不支持多模态
        "gemma-3-270m" to "",   // 纯文本模型，不支持多模态
        "Qwen3-4B" to "",   // 纯文本模型，不支持多模态
        "Qwen3-1.7B-Q4_K_M" to "",   // 纯文本模型，不支持多模态
        "sft-270m-v1.0" to "",   // 纯文本模型，不支持多模态
        "Qwen3-1.7B-tq1_0" to "",   // 纯文本模型，不支持多模态
        "SmolVLM2-500M-Video" to "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
        "InternVL3-2B" to "mmproj-InternVL3-2B-Instruct-Q8_0.gguf",
        "Qwen3-0.6B" to "",   // 纯文本模型，不支持多模态
    )

    private val pickMedia = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.uploadImage(uri)
            Log.d("PhotoPicker", "Selected URI: $uri")
        } else {
            Log.d("PhotoPicker", "No media selected")
        }
    }

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Log.e("DemoLLMActivity", "Audio permission denied")
        }
    }


    private val editSystemPromptLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val editedPrompt = result.data?.getStringExtra(EditSystemPromptActivity.EXTRA_EDITED_PROMPT)
            editedPrompt?.let { viewModel.updateSystemPrompt(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDemollmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
        setupModelSpinner()
    }

    private fun setupModelSpinner() {
        val modelNames = models.keys.toTypedArray()
        val adapter = ArrayAdapter(this, R.layout.spinner_item_top, modelNames)
        adapter.setDropDownViewResource(R.layout.spinner_item_centered)
        binding.modelSpinner.adapter = adapter

        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedModelName = modelNames[position]
                val mmprojName = models[selectedModelName]!!
                viewModel.startModelLoading(selectedModelName + "-Instruct-Q8_0.gguf", mmprojName)
            }

            override fun onNothingSelected(parent: AdapterView<*>){}
        }

        if (modelNames.isNotEmpty()) {
            val defaultModelName = modelNames[0]
            val defaultMmprojName = models[defaultModelName]!!
            viewModel.startModelLoading(defaultModelName + "-Instruct-Q8_0.gguf", defaultMmprojName)
        }
        // 加载音频模型
//        viewModel.whisperLoad("ggml-base.en.bin")
//        viewModel.whisperLoad("ggml-medium-q4_0.bin")  // 一般 有点慢啊
//        viewModel.whisperLoad("ggml-tiny-zh_q8_0.bin")  //不行
        viewModel.whisperLoad("ggml-tiny.bin")  // 不行 很容易有错别字
//        viewModel.whisperLoad("ggml-small.bin")  // 还行吧
//        viewModel.whisperLoad("ggml-small-q5_0.bin")  // 还行吧

    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(mutableListOf())
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@DemoLLMActivity)
            adapter = messageAdapter
        }
    }

    private fun setupClickListeners() {
        binding.sendButton.setOnClickListener {
            val message = binding.messageEditText.text.toString()
            if (message.isNotBlank()) {
                viewModel.updateMessage(message)
                binding.messageEditText.text.clear()
//                viewModel.sendWithLoop(this@DemoLLMActivity)
                viewModel.send(this@DemoLLMActivity)
            }
        }
        binding.attachButton.setOnClickListener { pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
        binding.benchButton.setOnClickListener { viewModel.bench(32, 32, 3) }
        binding.clearImageButton.setOnClickListener { viewModel.clearImage() }
        binding.clearButton.setOnClickListener { viewModel.clearHistoryAndKV() }
//        binding.initSysPromptButton.setOnClickListener {
//            viewModel.triggerInitSystemPrompt()
//        }
        binding.initThinkButton.setOnClickListener { viewModel.triggerThink() }
        binding.editSystemPromptButton.setOnClickListener {
            val intent = Intent(this, EditSystemPromptActivity::class.java).apply {
                putExtra(EditSystemPromptActivity.EXTRA_CURRENT_PROMPT, viewModel.getSystemPrompt())
            }
            editSystemPromptLauncher.launch(intent)
        }

        binding.recordButton.setOnTouchListener { _, event ->
            val recordButtonBackground = binding.recordButton.background.mutate()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recordButtonBackground.setColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP)
                    binding.recordButton.setImageResource(android.R.drawable.ic_media_pause)
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording(this@DemoLLMActivity)
                    } else {
                        requestAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    recordButtonBackground.clearColorFilter()
                    binding.recordButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                    if (viewModel.isRecording.value == true) {
                        viewModel.stopRecording()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) {
            messageAdapter.updateMessages(it)
            binding.recyclerView.scrollToPosition((it.size - 1).coerceAtLeast(0))
        }
        viewModel.imagePath.observe(this) {
            if (it.isNullOrEmpty()) {
                binding.previewContainer.visibility = View.GONE
            } else {
                binding.previewImage.setImageURI(Uri.parse(it))
                binding.previewContainer.visibility = View.VISIBLE
            }
        }
        viewModel.generating.observe(this) {
            binding.modelSpinner.isEnabled = !it
            binding.sendButton.isEnabled = !it
            binding.attachButton.isEnabled = !it
            binding.benchButton.isEnabled = !it
            binding.clearButton.isEnabled = !it
            binding.messageEditText.isEnabled = !it
        }
    }

    private fun createTestEvent() {
        try {
            val service = ARouter.getInstance().build(CalendarApiUtils.MAIN_SERVICE).navigation() as? ICalendarService
            if (service == null) {
                Log.e("CalendarTest", "Calendar Service not found!")
                return
            }
            val account = AccountApiUtils.getCurrAccount(true)
            if (account == null) {
                Log.e("CalendarTest", "User account is null.")
                return
            }
            if (account.userId.isNullOrBlank()) {
                Log.e("CalendarTest", "User ID is invalid.")
                return
            }

            val uno = account.userId!!
            val n = account.nameZn ?: ""
            val y = account.nameEn ?: ""
            val e = ""
            val p = account.mobile ?: ""
            val t = account.departmentZh ?: ""
            val te = account.departmentEn ?: ""
            val f = "6"

            val contact = CalRequestContactBisModel(uno, n, y, e, p, t, te, f)
            val model = CalCreateBisModel(T = "Generated Test Event", M = "Test event from DemoLLM", P = arrayOf(contact)).apply { from = "3" }
            val config = CalendarLauncherConfig(language = Locale.getDefault(), requestBisModel = model)
            service.startCreateEventActivity(this, config, null)
            Log.d("CalendarTest", "startCreateEventActivity called.")
        } catch (e: Exception) {
            Log.e("CalendarTest", "Exception during createTestEvent", e)
        }
    }

    private fun viewTodayEvents() {
        lifecycleScope.launch {
            try {
                Log.d("CalendarTest", "Step 1: Forcing a refresh to ensure local DB is up-to-date.")
                val calendarService = ARouter.getInstance().build(CalendarApiUtils.MAIN_SERVICE).navigation() as? ICalendarService
                val userNo = AccountApiUtils.getCurrUserNo(true)

                if (calendarService == null || userNo.isNullOrBlank()) {
                    Log.e("CalendarTest", "Service or UserNo is not available. Aborting.")
                    return@launch
                }
                calendarService.loadTodayEventFromNetwork(userNo)

                Log.d("CalendarTest", "Step 2: Waiting for 3 seconds to allow sync to complete.")
                delay(3000)

                Log.d("CalendarTest", "Step 3: Querying local database directly.")
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val todayStr = sdf.format(Date())
                val startTime = "$todayStr 00:00:00"
                val endTime = "$todayStr 23:59:59"

                val eventList = EventInfoDBDao.getInstance().queryEventBetweenTime(startTime, endTime)

                if (eventList.isNullOrEmpty()) {
                    Log.d("CalendarTest", "Result: No events found in local DB for today.")
                } else {
                    val formattedEvents = StringBuilder("Result from local DB: Today's events are:\n")
                    eventList.forEach { event ->
                        formattedEvents.append("- ${event.startTimeParam}: ${event.title}\n")
                    }
                    Log.d("CalendarTest", formattedEvents.toString())
                }

            } catch (e: Exception) {
                Log.e("CalendarTest", "An exception occurred in viewTodayEvents", e)
            }
        }
    }
}

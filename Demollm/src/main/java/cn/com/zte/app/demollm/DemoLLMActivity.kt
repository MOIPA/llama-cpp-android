package cn.com.zte.app.demollm

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import cn.com.zte.app.demollm.databinding.ActivityDemollmBinding
import cn.com.zte.framework.base.templates.BaseActivity
import com.alibaba.android.arouter.facade.annotation.Route

import android.widget.ArrayAdapter
import android.widget.AdapterView

@Route(path = "/demollm/main")
class DemoLLMActivity : BaseActivity() {

    private val viewModel by viewModels<MainViewModel>()
    private lateinit var binding: ActivityDemollmBinding
    private lateinit var messageAdapter: MessageAdapter

    private val models = mapOf(
        "SmolVLM2-500M-Video" to "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
        "InternVL3-2B" to "mmproj-InternVL3-2B-Instruct-Q8_0.gguf" // 举例
    )

    private val pickMedia = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.uploadImage(uri)
            Log.d("PhotoPicker", "Selected URI: $uri")
        } else {
            Log.d("PhotoPicker", "No media selected")
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
                
                // 通知ViewModel加载新模型
                viewModel.startModelLoading(selectedModelName + "-Instruct-Q8_0.gguf", mmprojName)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Do nothing
            }
        }

        // Manually load the default model
        if (modelNames.isNotEmpty()) {
            val defaultModelName = modelNames[0]
            val defaultMmprojName = models[defaultModelName]!!
            viewModel.startModelLoading(defaultModelName + "-Instruct-Q8_0.gguf", defaultMmprojName)
        }
    }

    private fun setupRecyclerView() {
        // Initialize with an empty list, the observer will populate it.
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
                viewModel.send()
            }
        }

        binding.attachButton.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }

        binding.benchButton.setOnClickListener {
            viewModel.bench(32, 32, 3)
        }
        binding.clearImageButton.setOnClickListener {
            viewModel.clearImage()
        }

        binding.clearButton.setOnClickListener {
            viewModel.clearHistoryAndKV()
        }
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this, Observer { messages ->
            messageAdapter.updateMessages(messages)
            binding.recyclerView.scrollToPosition((messages.size - 1).coerceAtLeast(0))
        })

        viewModel.imagePath.observe(this, Observer { path ->
            if (path.isNullOrEmpty()) {
                binding.previewContainer.visibility = View.GONE
            } else {
                binding.previewImage.setImageURI(Uri.parse(path))
                binding.previewContainer.visibility = View.VISIBLE
            }
        })

        viewModel.generating.observe(this, Observer { isGenerating ->
            binding.modelSpinner.isEnabled = !isGenerating
            binding.sendButton.isEnabled = !isGenerating
            binding.attachButton.isEnabled = !isGenerating
            binding.benchButton.isEnabled = !isGenerating
            binding.clearButton.isEnabled = !isGenerating
            binding.messageEditText.isEnabled = !isGenerating
        })
    }
}

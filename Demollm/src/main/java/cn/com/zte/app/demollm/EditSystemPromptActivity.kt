package cn.com.zte.app.demollm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class EditSystemPromptActivity : AppCompatActivity() {

    private lateinit var systemPromptEditText: EditText
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_system_prompt)

        systemPromptEditText = findViewById(R.id.systemPromptEditText)
        saveButton = findViewById(R.id.saveButton)

        // 获取传入的当前 systemPrompt
        val currentPrompt = intent.getStringExtra(EXTRA_CURRENT_PROMPT)
        systemPromptEditText.setText(currentPrompt)

        saveButton.setOnClickListener {
            val editedPrompt = systemPromptEditText.text.toString()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_EDITED_PROMPT, editedPrompt)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    companion object {
        const val EXTRA_CURRENT_PROMPT = "extra_current_prompt"
        const val EXTRA_EDITED_PROMPT = "extra_edited_prompt"
    }
}

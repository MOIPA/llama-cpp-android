package cn.com.zte.app.demollm

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

// 1. Define the data class and enum for message types
data class ChatMessage(val text: String, val type: MessageType)
enum class MessageType {
    USER, MODEL, USER_IMAGE
}

class MessageAdapter(private var messages: MutableList<ChatMessage>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // 2. Define ViewHolders for each message type
    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.messageTextView)
    }

    class ModelMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.messageTextView)
    }

    class UserImageMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.messageImageView)
    }

    // 3. Override getItemViewType to return a type based on the message
    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return if (message.type == MessageType.USER && message.text.startsWith("<ImagePath>")) {
            MessageType.USER_IMAGE.ordinal
        } else {
            message.type.ordinal
        }
    }

    // 4. In onCreateViewHolder, inflate the correct layout based on viewType
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            MessageType.USER_IMAGE.ordinal -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_user_image_message, parent, false)
                UserImageMessageViewHolder(view)
            }
            MessageType.USER.ordinal -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_user_message, parent, false)
                UserMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_model_message, parent, false)
                ModelMessageViewHolder(view)
            }
        }
    }

    // 5. In onBindViewHolder, bind data to the correct ViewHolder
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserImageMessageViewHolder -> {
                val imagePath = message.text.removePrefix("<ImagePath>")
                holder.imageView.setImageURI(Uri.fromFile(File(imagePath)))
            }
            is UserMessageViewHolder -> {
                holder.textView.text = message.text
            }
            is ModelMessageViewHolder -> {
                holder.textView.text = message.text
            }
        }
    }

    override fun getItemCount() = messages.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }
}

package com.example.myllm

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.getSystemService
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.myllm.ui.theme.MyLLMTheme
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity(
    activityManager: ActivityManager? = null,
    downloadManager: DownloadManager? = null,
    clipboardManager: ClipboardManager? = null,
) : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val activityManager by lazy { activityManager ?: getSystemService<ActivityManager>()!! }
    private val downloadManager by lazy { downloadManager ?: getSystemService<DownloadManager>()!! }
    private val clipboardManager by lazy {
        clipboardManager ?: getSystemService<ClipboardManager>()!!
    }
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
        enableEdgeToEdge()
        setContent {
            MyLLMTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainCompose(
                        viewModel,
                        clipboardManager,
                        downloadManager,
                        pickMedia,
                        Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}


@Composable
fun MainCompose(
    viewModel: MainViewModel,
    clipboard: ClipboardManager,
    dm: DownloadManager,
    pickMedia: ActivityResultLauncher<PickVisualMediaRequest>,
//    models: List<Downloadable>
    modifier: Modifier = Modifier
) {
    // 输入区在软键盘弹出时有动画

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Column(
        modifier = modifier
            .fillMaxSize()
//            .navigationBarsPadding()
    ) {
        val scrollState = rememberLazyListState()
        LaunchedEffect(Unit) {
            snapshotFlow { viewModel.messages }
                .onEach {
                    scrollState.animateScrollToItem(index = viewModel.messages.size - 1)
                }.launchIn(this)
        }
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(state = scrollState) {
                items(viewModel.messages) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        // 输入区始终在底部
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(durationMillis = 1000)),
            exit = fadeOut(animationSpec = tween(durationMillis = 1000))
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (viewModel.imagePath != "")
                    ImageWithCloseButton(viewModel)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = viewModel.message,
                        onValueChange = { viewModel.updateMessage(it) },
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                colorResource(R.color.white),
                                shape = RoundedCornerShape(24.dp)
                            ),
                        shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("Message") },
                        singleLine = true,
                        leadingIcon = {
                            IconButton(
                                onClick = {
                                    pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                                },
                                enabled = !viewModel.generating,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Upload Image",
                                    tint = colorResource(R.color.teal_700)
                                )
                            }
                        },
                        trailingIcon = {
                            if (viewModel.message.isNotBlank()) {
                                IconButton(
                                    onClick = { viewModel.send() },
                                    enabled = !viewModel.generating
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = colorResource(R.color.teal_200)
                                    )
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            unfocusedIndicatorColor = Color(0xFFCCCCCC),
                            focusedIndicatorColor = Color(0xFFCCCCCC),
                            disabledIndicatorColor = Color(0xFFCCCCCC),
                            errorIndicatorColor = Color.Red
                        ),
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (!viewModel.generating && viewModel.message.isNotBlank()) viewModel.send()
                            }
                        )
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Button(
                        onClick = { viewModel.bench(32, 32, 3) },
                        enabled = !viewModel.generating,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Bench") }
                }

            }
        }
    }
}

@Composable
fun ImageWithCloseButton(viewModel: MainViewModel) {
    val borderColor = Color(0xFFCCCCCC)
    Box(
        modifier = Modifier
            .padding(bottom = 10.dp)
            .wrapContentSize(align = Alignment.TopStart),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context = LocalContext.current)
                .data(viewModel.imagePath)
                .crossfade(true)
                .build(),
            contentDescription = "preview",
            contentScale = ContentScale.Crop, // 裁切图片
            modifier = Modifier
                .size(96.dp)    // 图片大小
                .clip(RoundedCornerShape(16.dp)) // 圆角图片
                .border(width = 3.dp, color = borderColor, shape = RoundedCornerShape(16.dp)),
            error = painterResource(R.drawable.ic_broken_image),
            placeholder = painterResource(R.drawable.loading_img),
        )
        IconButton(
            onClick = { viewModel.clearImage() },
            modifier = Modifier
                .offset(x = 80.dp, y = (-10).dp)
                .background(colorResource(R.color.white), shape = CircleShape)
                .border(width = 2.dp, color = borderColor, shape = CircleShape)
                .size(24.dp)
                .zIndex(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = colorResource(R.color.black),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun preview() {
//    ImageWithCloseButton()
}

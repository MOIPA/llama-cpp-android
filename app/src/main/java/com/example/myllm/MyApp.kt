//import android.os.Bundle
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.material3.Text
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import com.alibaba.android.arouter.BuildConfig
//import com.alibaba.android.arouter.launcher.ARouter
//import com.alibaba.android.arouter.facade.annotation.Route
//// 1. 新建 Application 类并初始化 ARouter
//// 请确保 AndroidManifest.xml 的 <application android:name=".MyApp" />
//class MyApp : android.app.Application() {
//    override fun onCreate() {
//        super.onCreate()
//        if (BuildConfig.DEBUG) {
//            ARouter.openLog()
//            ARouter.openDebug()
//        }
//        ARouter.init(this)
//    }
//}
//// 2. 示例目标页面（Activity）
//@Route(path = "/test/activity")
//class TestActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContent {
//            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
//                Text("这是TestActivity页面")
//            }
//        }
//    }
//}
package cn.com.zte.app.demollm

import android.content.Context
import cn.com.zte.app.demollm.DemoLLMApiUtils.APP_DEMO_LLM_NAVIGATION_SERVICE
import cn.com.zte.router.demollm.IDemoLLMNavigation
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter

@Route(path = APP_DEMO_LLM_NAVIGATION_SERVICE)
class DemoLLMNavigationService : IDemoLLMNavigation {
    override fun navigateToDemoLLM(context: Context) {
        ARouter.getInstance().build("/demollm/main").navigation(context)
    }

    override fun init(context: Context?) {
        // Do nothing
    }
}

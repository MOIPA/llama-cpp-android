package cn.com.zte.app.demollm

import android.content.Context
import cn.com.zte.framework.data.extension.getRouterService
import cn.com.zte.router.demollm.DemoLLMInterface
import cn.com.zte.router.demollm.IDemoLLMNavigation

object DemoLLMApiUtils {
    private const val MODULE_NAME = "/cn_com_zte_app_demollm"
    const val APP_DEMO_LLM_SERVICE = "$MODULE_NAME/LocalLLMService"
    const val APP_DEMO_LLM_NAVIGATION_SERVICE = "$MODULE_NAME/NavigationService"

    @JvmStatic
    fun getService(): DemoLLMInterface? {
        return getRouterService(APP_DEMO_LLM_SERVICE)
    }

    @JvmStatic
    fun getNavigationService(): IDemoLLMNavigation? {
        return getRouterService(APP_DEMO_LLM_NAVIGATION_SERVICE)
    }

    @JvmStatic
    fun navigateToDemoLLM(context: Context) {
    getNavigationService()?.navigateToDemoLLM(context)
    }
}
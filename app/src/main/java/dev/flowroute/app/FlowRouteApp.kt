package dev.flowroute.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.flowroute.app.data.StateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FlowRouteApp : Application(), Application.ActivityLifecycleCallbacks {
    val store: StateStore by lazy { StateStore(this) }
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities += 1
        if (startedActivities == 1) mutableAppInForeground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) mutableAppInForeground.value = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private val mutableAppInForeground = MutableStateFlow(false)
        val appInForeground = mutableAppInForeground.asStateFlow()
    }
}

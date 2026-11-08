package io.github.androidconnect

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var discovery: Discovery
        private set
    lateinit var link: Link
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private val disconnectInBackground = Runnable { link.disconnect() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        discovery = Discovery(this)
        link = Link(this, store, discovery)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                handler.removeCallbacks(disconnectInBackground)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                // Nobody can see the screen while the app is in the background; don't
                // keep both devices busy with it for long.
                if (startedActivities == 0) handler.postDelayed(disconnectInBackground, BACKGROUND_TIMEOUT_MS)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        private const val BACKGROUND_TIMEOUT_MS = 60_000L

        lateinit var instance: App
            private set
    }
}

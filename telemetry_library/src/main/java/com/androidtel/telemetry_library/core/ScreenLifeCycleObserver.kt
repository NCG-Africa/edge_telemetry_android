package com.androidtel.telemetry_library.core


import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

// This observer is designed to be attached to a FragmentManager.
class ScreenLifecycleObserver(private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()) :
    FragmentManager.FragmentLifecycleCallbacks() {

    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
        super.onFragmentStarted(fm, f)
        telemetryManager.recordScreenView(f.javaClass.simpleName)
    }

}
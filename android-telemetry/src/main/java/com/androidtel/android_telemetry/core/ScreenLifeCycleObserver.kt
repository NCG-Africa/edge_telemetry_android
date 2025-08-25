package com.androidtel.android_telemetry.core


import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

// This observer is designed to be attached to a FragmentManager.
class ScreenLifecycleObserver(private val telemetryManager: TelemetryManager) :
    FragmentManager.FragmentLifecycleCallbacks() {

    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
        super.onFragmentStarted(fm, f)
        telemetryManager.recordScreenView(f.javaClass.simpleName)
    }
//
//    override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
//        super.onFragmentStopped(fm, f)
//        telemetryManager.recordScreenEnd(f.javaClass.simpleName)
//    }
}
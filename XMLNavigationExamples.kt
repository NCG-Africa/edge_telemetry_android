// Examples for XML Navigation and Activity tracking

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.navigation.FragmentScreenTracker
import com.androidtel.telemetry_library.core.navigation.NavigationComponentTracker

// 1. Activity with Navigation Component
class MainActivity : AppCompatActivity() {
    
    private lateinit var navController: NavController
    private lateinit var navigationTracker: NavigationComponentTracker
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Setup Navigation Component
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        
        // Start Navigation Component tracking
        navigationTracker = NavigationComponentTracker(navController)
        navigationTracker.startTracking()
        
        // Track app launch
        TelemetryManager.getInstance().recordEvent(
            eventName = "app.launched",
            attributes = mapOf(
                "launch_source" to "main_activity",
                "navigation_type" to "navigation_component"
            )
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop tracking to prevent memory leaks
        navigationTracker.stopTracking()
    }
}

// 2. Fragment with Manual Tracking (when needed)
class ProfileFragment : Fragment() {
    
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Manual fragment tracking (optional - automatic tracking may already work)
        FragmentScreenTracker.trackFragmentScreen(
            fragment = this,
            screenName = "ProfileScreen",
            fromScreen = "HomeScreen", // You can determine this from arguments or navigation
            attributes = mapOf(
                "user_id" to "12345",
                "feature" to "user_profile"
            )
        )
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        
        // Track screen exit
        FragmentScreenTracker.trackFragmentScreenExit(this, "ProfileScreen")
    }
}

// 3. Activity with Manual Navigation Tracking
class DetailActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        
        // Get data from intent
        val itemId = intent.getStringExtra("ITEM_ID") ?: "unknown"
        val sourceScreen = intent.getStringExtra("SOURCE_SCREEN") ?: "unknown"
        
        // Track navigation to this activity
        TelemetryManager.getInstance().recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to sourceScreen,
                "navigation.to_screen" to "DetailActivity",
                "navigation.method" to "push",
                "navigation.route_type" to "activity_intent",
                "navigation.has_arguments" to true,
                "navigation.timestamp" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()),
                "item_id" to itemId
            )
        )
        
        // Track screen view
        TelemetryManager.getInstance().recordComposeScreenView("DetailActivity")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Track screen duration
        TelemetryManager.getInstance().recordComposeScreenEnd("DetailActivity")
    }
}

// 4. Base Activity for Consistent Tracking
abstract class BaseTrackingActivity : AppCompatActivity() {
    
    protected open val screenName: String = this.javaClass.simpleName
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Track activity creation
        val telemetryManager = TelemetryManager.getInstance()
        telemetryManager.addBreadcrumb(
            message = "$screenName created",
            category = "lifecycle",
            level = "info"
        )
    }
    
    override fun onResume() {
        super.onResume()
        
        // Track screen view (automatic tracking may already handle this)
        val telemetryManager = TelemetryManager.getInstance()
        telemetryManager.addBreadcrumb(
            message = "$screenName resumed",
            category = "lifecycle",
            level = "info"
        )
    }
    
    override fun onPause() {
        super.onPause()
        
        val telemetryManager = TelemetryManager.getInstance()
        telemetryManager.addBreadcrumb(
            message = "$screenName paused",
            category = "lifecycle",
            level = "info"
        )
    }
}

// 5. Usage with Navigation Graph XML
/*
In your navigation graph (res/navigation/nav_graph.xml):

<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/nav_graph"
    app:startDestination="@id/homeFragment">

    <fragment
        android:id="@+id/homeFragment"
        android:name="com.example.HomeFragment"
        android:label="HomeScreen"
        tools:layout="@layout/fragment_home" />

    <fragment
        android:id="@+id/profileFragment"
        android:name="com.example.ProfileFragment"
        android:label="ProfileScreen"
        tools:layout="@layout/fragment_profile">
        
        <argument
            android:name="userId"
            app:argType="string" />
    </fragment>

    <activity
        android:id="@+id/detailActivity"
        android:name="com.example.DetailActivity"
        android:label="DetailScreen"
        tools:layout="@layout/activity_detail">
        
        <argument
            android:name="itemId"
            app:argType="string" />
    </activity>
</navigation>
*/

// 6. Integration in Application class
class MyApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize telemetry with screen tracking enabled
        val config = com.androidtel.telemetry_library.core.TelemetryConfig(
            apiKey = BuildConfig.TELEMETRY_API_KEY,
            endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
            enableScreenTracking = true, // This enables automatic Activity tracking
            enableLegacyScreenEvents = false, // Use navigation events instead
            // ... other config
        )
        
        com.androidtel.telemetry_library.core.TelemetryManager.initialize(this, config)
    }
}

// Navigation with integrated telemetry tracking
import android.os.Bundle
import android.os.Parcelable
import androidx.navigation.NavController
import com.androidtel.telemetry_library.core.TelemetryManager
import java.text.SimpleDateFormat
import java.util.*

// 1. Fixed Navigation Directions with Telemetry
object TransactionNavigationDirections {
    
    fun navigateToTransactionDetails(
        data: TransactionData?
    ): NavDirections {
        return object : NavDirections {
            override val actionId: Int = R.id.action_nav_transaction_details
            
            override val arguments: Bundle
                get() = createArgumentsBundle(data)
        }
    }
    
    private fun createArgumentsBundle(data: TransactionData?): Bundle {
        val bundle = Bundle()
        
        // Fixed: Check if data is actually Parcelable and not null
        if (data != null && data is Parcelable) {
            bundle.putParcelable("transaction_data", data)
        }
        
        return bundle
    }
    
    // 2. Navigation with Telemetry Tracking
    fun navigateToTransactionDetailsWithTelemetry(
        navController: NavController,
        data: TransactionData?,
        fromScreen: String? = null
    ) {
        val telemetryManager = TelemetryManager.getInstance()
        val screenName = "TransactionDetails"
        
        // Track navigation event before actual navigation
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to (fromScreen ?: getCurrentScreen(navController)),
                "navigation.to_screen" to screenName,
                "navigation.method" to "push",
                "navigation.route_type" to "navigation_component",
                "navigation.has_arguments" to (data != null),
                "navigation.timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
                "transaction_id" to (data?.id ?: "unknown"),
                "transaction_type" to (data?.type ?: "unknown")
            )
        )
        
        // Add breadcrumb for navigation context
        telemetryManager.addBreadcrumb(
            message = "Navigating to $screenName with transaction ${data?.id}",
            category = "navigation",
            level = "info",
            data = mapOf(
                "from_screen" to (fromScreen ?: "unknown"),
                "transaction_id" to (data?.id ?: "unknown")
            )
        )
        
        // Perform actual navigation
        navController.navigate(navigateToTransactionDetails(data))
        
        // Track screen view start
        telemetryManager.recordComposeScreenView(screenName)
    }
    
    private fun getCurrentScreen(navController: NavController): String {
        return navController.currentDestination?.label?.toString() ?: 
               "destination_${navController.currentDestination?.id}"
    }
}

// 3. Extension Function for NavController
fun NavController.navigateToTransactionDetailsWithTelemetry(
    data: TransactionData?,
    fromScreen: String? = null
) {
    TransactionNavigationDirections.navigateToTransactionDetailsWithTelemetry(
        navController = this,
        data = data,
        fromScreen = fromScreen
    )
}

// 4. Base Navigation Helper for Consistent Tracking
abstract class BaseNavigationHelper {
    
    protected fun trackNavigation(
        navController: NavController,
        toScreen: String,
        fromScreen: String? = null,
        arguments: Map<String, Any> = emptyMap(),
        routeType: String = "navigation_component"
    ) {
        val telemetryManager = TelemetryManager.getInstance()
        
        // Track navigation event
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to (fromScreen ?: getCurrentScreen(navController)),
                "navigation.to_screen" to toScreen,
                "navigation.method" to "push",
                "navigation.route_type" to routeType,
                "navigation.has_arguments" to arguments.isNotEmpty(),
                "navigation.timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
            ) + arguments
        )
        
        // Add breadcrumb
        telemetryManager.addBreadcrumb(
            message = "Navigating to $toScreen",
            category = "navigation",
            level = "info",
            data = arguments.mapKeys { it.key.toString() }
        )
    }
    
    protected fun trackScreenStart(screenName: String) {
        TelemetryManager.getInstance().recordComposeScreenView(screenName)
    }
    
    protected fun trackScreenEnd(screenName: String) {
        TelemetryManager.getInstance().recordComposeScreenEnd(screenName)
    }
    
    private fun getCurrentScreen(navController: NavController): String {
        return navController.currentDestination?.label?.toString() ?: 
               "destination_${navController.currentDestination?.id}"
    }
}

// 5. Example Usage in Fragment/Activity
class TransactionListFragment : Fragment() {
    
    private fun onTransactionClicked(transaction: TransactionData) {
        val navController = findNavController()
        
        // Method 1: Extension function
        navController.navigateToTransactionDetailsWithTelemetry(
            data = transaction,
            fromScreen = "TransactionList"
        )
        
        // Method 2: Direct call
        TransactionNavigationDirections.navigateToTransactionDetailsWithTelemetry(
            navController = navController,
            data = transaction,
            fromScreen = "TransactionList"
        )
    }
}

// 6. TransactionData (assuming this structure)
data class TransactionData(
    val id: String,
    val type: String,
    val amount: Double,
    val date: Long,
    // ... other properties
) : Parcelable {
    
    // Parcelable implementation
    constructor(parcel: android.os.Parcel) : this(
        id = parcel.readString() ?: "",
        type = parcel.readString() ?: "",
        amount = parcel.readDouble(),
        date = parcel.readLong()
    )
    
    override fun writeToParcel(parcel: android.os.Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(type)
        parcel.writeDouble(amount)
        parcel.writeLong(date)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<TransactionData> {
        override fun createFromParcel(parcel: android.os.Parcel): TransactionData {
            return TransactionData(parcel)
        }
        
        override fun newArray(size: Int): Array<TransactionData?> {
            return arrayOfNulls(size)
        }
    }
}

// 7. Alternative: Safe Navigation with Error Handling
object SafeTransactionNavigation {
    
    fun navigateSafely(
        navController: NavController,
        data: TransactionData?,
        fromScreen: String? = null
    ) {
        try {
            if (data == null) {
                // Log warning and navigate without data
                TelemetryManager.getInstance().addBreadcrumb(
                    message = "Navigating to TransactionDetails without data",
                    category = "navigation",
                    level = "warning"
                )
                
                navController.navigate(R.id.action_nav_transaction_details)
                return
            }
            
            // Validate data before navigation
            if (data.id.isBlank()) {
                TelemetryManager.getInstance().recordEvent(
                    eventName = "navigation.error",
                    attributes = mapOf(
                        "error_type" to "invalid_data",
                        "target_screen" to "TransactionDetails",
                        "validation_failed" to "transaction_id_missing"
                    )
                )
                return
            }
            
            // Proceed with tracked navigation
            TransactionNavigationDirections.navigateToTransactionDetailsWithTelemetry(
                navController = navController,
                data = data,
                fromScreen = fromScreen
            )
            
        } catch (e: Exception) {
            // Track navigation error
            TelemetryManager.getInstance().recordCrash(e)
            
            TelemetryManager.getInstance().recordEvent(
                eventName = "navigation.failed",
                attributes = mapOf(
                    "target_screen" to "TransactionDetails",
                    "error_message" to e.message,
                    "transaction_id" to (data?.id ?: "unknown")
                )
            )
        }
    }
}

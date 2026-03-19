# API Key Management Guide

## Table of Contents
- [Overview](#overview)
- [Obtaining an API Key](#obtaining-an-api-key)
- [Secure Storage Methods](#secure-storage-methods)
- [Integration Examples](#integration-examples)
- [Security Best Practices](#security-best-practices)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)

---

## Overview

The EdgeTelemetry Android SDK requires an API key for authentication starting from version 1.2.6. This guide provides comprehensive instructions on how to obtain, store, and use API keys securely.

### Why API Keys?

- **Authentication**: Verify requests are from authorized applications
- **Rate Limiting**: Control usage and prevent abuse
- **Analytics**: Track usage patterns per application
- **Security**: Prevent unauthorized access to telemetry backend

### API Key Format

EdgeTelemetry API keys follow this format:
```
edge_<random_characters>_<checksum>
```

Example: `edge_a1b2c3d4e5f6g7h8_xyz123`

**Requirements:**
- Must start with `edge_` prefix
- Cannot be blank or empty
- Validated at SDK initialization

---

## Obtaining an API Key

### Step 1: Contact Your Backend Administrator

API keys are generated and managed by your backend infrastructure team. Contact them to request a new API key for your application.

**Information to Provide:**
- Application name
- Package name (e.g., `com.example.myapp`)
- Environment (development, staging, production)
- Expected usage volume

### Step 2: Receive Your API Key

Your administrator will provide:
- API key string (e.g., `edge_abc123...`)
- Telemetry endpoint URL
- Any environment-specific configuration

### Step 3: Store Securely

**⚠️ CRITICAL: Never commit API keys to version control!**

See [Secure Storage Methods](#secure-storage-methods) below.

---

## Secure Storage Methods

### Method 1: local.properties (Recommended for Development)

**Best for:** Local development, team collaboration

#### Step 1: Add to local.properties

Create or edit `local.properties` in your project root:

```properties
# local.properties (this file is gitignored by default)
TELEMETRY_API_KEY=edge_your_api_key_here
```

#### Step 2: Configure build.gradle.kts

Add to your app's `build.gradle.kts`:

```kotlin
import java.util.Properties
import java.io.FileInputStream

android {
    defaultConfig {
        // Load properties from local.properties
        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }
        
        // Add API key to BuildConfig
        buildConfigField(
            "String",
            "TELEMETRY_API_KEY",
            "\"${properties.getProperty("TELEMETRY_API_KEY", "")}\""
        )
    }
    
    buildFeatures {
        buildConfig = true
    }
}
```

#### Step 3: Use in Code

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val config = TelemetryConfig.builder(this, BuildConfig.TELEMETRY_API_KEY)
            .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
            .build()
        
        TelemetryManager.initialize(config)
    }
}
```

#### Step 4: Verify .gitignore

Ensure your `.gitignore` includes:

```gitignore
# API Keys and Secrets
../local.properties
*.keystore
*.jks
secrets.properties
```

---

### Method 2: Environment Variables (Recommended for CI/CD)

**Best for:** CI/CD pipelines, automated builds

#### Step 1: Set Environment Variable

**Linux/macOS:**
```bash
export TELEMETRY_API_KEY="edge_your_api_key_here"
```

**Windows:**
```cmd
set TELEMETRY_API_KEY=edge_your_api_key_here
```

**CI/CD (GitHub Actions example):**
```yaml
env:
  TELEMETRY_API_KEY: ${{ secrets.TELEMETRY_API_KEY }}
```

#### Step 2: Configure build.gradle.kts

```kotlin
android {
    defaultConfig {
        buildConfigField(
            "String",
            "TELEMETRY_API_KEY",
            "\"${System.getenv("TELEMETRY_API_KEY") ?: ""}\""
        )
    }
    
    buildFeatures {
        buildConfig = true
    }
}
```

#### Step 3: Use in Code

Same as Method 1 - access via `BuildConfig.TELEMETRY_API_KEY`

---

### Method 3: Gradle Properties (Team-wide Configuration)

**Best for:** Team-wide defaults, multi-module projects

#### Step 1: Add to gradle.properties

Create or edit `gradle.properties` in project root:

```properties
# gradle.properties (DO NOT commit if contains real keys)
TELEMETRY_API_KEY=edge_default_dev_key
```

**⚠️ Warning:** Only use this for non-sensitive default values. For production keys, use local.properties or environment variables.

#### Step 2: Configure build.gradle.kts

```kotlin
android {
    defaultConfig {
        val apiKey = project.findProperty("TELEMETRY_API_KEY") as String? ?: ""
        buildConfigField("String", "TELEMETRY_API_KEY", "\"$apiKey\"")
    }
    
    buildFeatures {
        buildConfig = true
    }
}
```

---

### Method 4: Android Keystore (Maximum Security)

**Best for:** Production apps requiring maximum security

#### Overview

Android Keystore System provides hardware-backed encryption for sensitive data.

#### Implementation

```kotlin
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

class SecureApiKeyStorage(private val context: Context) {
    
    private val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private val KEY_ALIAS = "telemetry_api_key"
    private val PREFS_NAME = "secure_telemetry_prefs"
    private val ENCRYPTED_KEY_PREF = "encrypted_api_key"
    private val IV_PREF = "encryption_iv"
    
    fun saveApiKey(apiKey: String) {
        val cipher = getCipher(Cipher.ENCRYPT_MODE)
        val encryptedBytes = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(ENCRYPTED_KEY_PREF, Base64.encodeToString(encryptedBytes, Base64.DEFAULT))
            putString(IV_PREF, Base64.encodeToString(iv, Base64.DEFAULT))
        }
    }
    
    fun getApiKey(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedKey = prefs.getString(ENCRYPTED_KEY_PREF, null) ?: return null
        val iv = prefs.getString(IV_PREF, null) ?: return null
        
        val cipher = getCipher(Cipher.DECRYPT_MODE, Base64.decode(iv, Base64.DEFAULT))
        val decryptedBytes = cipher.doFinal(Base64.decode(encryptedKey, Base64.DEFAULT))
        return String(decryptedBytes, Charsets.UTF_8)
    }
    
    private fun getCipher(mode: Int, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getOrCreateSecretKey()
        
        if (mode == Cipher.ENCRYPT_MODE) {
            cipher.init(mode, secretKey)
        } else {
            cipher.init(mode, secretKey, GCMParameterSpec(128, iv))
        }
        
        return cipher
    }
    
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
}

// Usage
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val secureStorage = SecureApiKeyStorage(this)
        
        // First time: save API key (get from secure source)
        // secureStorage.saveApiKey("edge_your_api_key_here")
        
        // Retrieve and use
        val apiKey = secureStorage.getApiKey() ?: BuildConfig.TELEMETRY_API_KEY
        
        val config = TelemetryConfig.builder(this, apiKey)
            .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
            .build()
        
        TelemetryManager.initialize(config)
    }
}
```

**Note:** This is an advanced approach. For most applications, BuildConfig with local.properties is sufficient.

---

## Integration Examples

### Example 1: Simple Development Setup

```kotlin
// local.properties
TELEMETRY_API_KEY=edge_dev_key_12345

// app/build.gradle.kts
android {
    defaultConfig {
        val properties = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }?.let {
            properties.load(FileInputStream(it))
        }
        buildConfigField("String", "TELEMETRY_API_KEY", 
            "\"${properties.getProperty("TELEMETRY_API_KEY", "")}\"")
    }
    buildFeatures { buildConfig = true }
}

// MyApplication.kt
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TelemetryManager.initialize(
            application = this,
            apiKey = BuildConfig.TELEMETRY_API_KEY,
            endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
        )
    }
}
```

### Example 2: Multi-Environment Setup

```kotlin
// local.properties
TELEMETRY_API_KEY_DEV=edge_dev_key_12345
TELEMETRY_API_KEY_STAGING=edge_staging_key_67890
TELEMETRY_API_KEY_PROD=edge_prod_key_abcdef

// app/build.gradle.kts
android {
    buildTypes {
        debug {
            buildConfigField("String", "TELEMETRY_API_KEY", 
                "\"${getLocalProperty("TELEMETRY_API_KEY_DEV")}\"")
        }
        release {
            buildConfigField("String", "TELEMETRY_API_KEY", 
                "\"${getLocalProperty("TELEMETRY_API_KEY_PROD")}\"")
        }
    }
    
    flavorDimensions += "environment"
    productFlavors {
        create("staging") {
            dimension = "environment"
            buildConfigField("String", "TELEMETRY_API_KEY", 
                "\"${getLocalProperty("TELEMETRY_API_KEY_STAGING")}\"")
        }
    }
}

fun getLocalProperty(key: String): String {
    val properties = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.let {
        properties.load(FileInputStream(it))
    }
    return properties.getProperty(key, "")
}
```

### Example 3: TelemetryConfig with Validation

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val apiKey = BuildConfig.TELEMETRY_API_KEY
        
        // Validate API key before initialization
        if (apiKey.isBlank()) {
            Log.e("Telemetry", "API key is not configured!")
            return
        }
        
        if (!apiKey.startsWith("edge_")) {
            Log.e("Telemetry", "Invalid API key format!")
            return
        }
        
        try {
            val config = TelemetryConfig.builder(this, apiKey)
                .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
                .debugMode(BuildConfig.DEBUG)
                .batchSize(30)
                .enableCrashReporting(true)
                .enableUserProfiles(true)
                .enableSessionTracking(true)
                .build()
            
            TelemetryManager.initialize(config)
            Log.i("Telemetry", "SDK initialized successfully")
        } catch (e: IllegalArgumentException) {
            Log.e("Telemetry", "Failed to initialize SDK: ${e.message}")
        }
    }
}
```

---

## Security Best Practices

### ✅ DO

1. **Use BuildConfig** for API key storage
2. **Store in local.properties** (gitignored file)
3. **Use environment variables** for CI/CD
4. **Validate API key** before initialization
5. **Enable ProGuard/R8** for release builds
6. **Rotate API keys** periodically
7. **Use different keys** for dev/staging/prod
8. **Monitor API key usage** in backend logs
9. **Revoke compromised keys** immediately
10. **Document key management** process for your team

### ❌ DON'T

1. **Never hardcode** API keys in source code
2. **Never commit** API keys to version control
3. **Never log** API keys in plain text
4. **Never share** API keys in public channels
5. **Never use production keys** in development
6. **Never store** API keys in strings.xml
7. **Never include** API keys in crash reports
8. **Never expose** API keys in client-side code
9. **Never reuse** API keys across multiple apps
10. **Never ignore** API key validation errors

### ProGuard/R8 Configuration

The SDK includes consumer ProGuard rules that protect API keys in release builds. Ensure ProGuard/R8 is enabled:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

The SDK's `consumer-rules.pro` automatically:
- Obfuscates string constants
- Protects API key references
- Maintains public SDK API

---

## Troubleshooting

### Error: "API key cannot be blank"

**Cause:** API key is empty or not configured

**Solution:**
```kotlin
// Check BuildConfig value
Log.d("Telemetry", "API Key: ${BuildConfig.TELEMETRY_API_KEY}")

// Verify local.properties exists and contains key
// Rebuild project after adding API key
```

### Error: "API key is invalid"

**Cause:** API key doesn't start with "edge_" prefix

**Solution:**
```kotlin
// Verify API key format
val apiKey = BuildConfig.TELEMETRY_API_KEY
if (!apiKey.startsWith("edge_")) {
    Log.e("Telemetry", "Invalid API key format: $apiKey")
}

// Contact backend administrator for correct key
```

### Error: 401 Unauthorized from Backend

**Cause:** Backend rejects API key

**Possible Reasons:**
- API key is incorrect
- API key has been revoked
- API key has expired
- Backend not configured to accept key

**Solution:**
1. Verify API key with backend administrator
2. Check backend logs for authentication errors
3. Ensure API key is active and not expired
4. Test with a known-good API key

### API Key Not Found in BuildConfig

**Cause:** BuildConfig not generated or API key not configured

**Solution:**
1. Ensure `buildFeatures { buildConfig = true }` is set
2. Verify `local.properties` contains `TELEMETRY_API_KEY`
3. Clean and rebuild project: `./gradlew clean build`
4. Check `build/generated/source/buildConfig` for generated file

### API Key Visible in Logs

**Cause:** Debug mode enabled or custom logging

**Solution:**
- SDK automatically redacts API keys in debug logs
- Shows only: `edge_****_xyz1` (first 5 and last 4 characters)
- Remove any custom logging of API keys
- Never log `BuildConfig.TELEMETRY_API_KEY` directly

---

## FAQ

### Q: How do I get an API key?

**A:** Contact your backend administrator or infrastructure team. They will generate and provide an API key for your application.

### Q: Can I use the same API key for multiple apps?

**A:** Not recommended. Each application should have its own API key for better tracking, security, and rate limiting.

### Q: What happens if my API key is compromised?

**A:** Immediately contact your backend administrator to revoke the compromised key and issue a new one. Update your application with the new key.

### Q: Do API keys expire?

**A:** This depends on your backend configuration. Check with your backend administrator for your organization's API key expiration policy.

### Q: Can I rotate API keys without app downtime?

**A:** Currently, the SDK doesn't support runtime API key rotation. You'll need to release an app update with the new API key. Plan key rotation during maintenance windows.

### Q: How do I test API key integration?

**A:** Use the SDK's built-in testing utilities:

```kotlin
// Test connectivity and API key
TelemetryManager.getInstance().testConnectivity()

// Run comprehensive test
EdgeTelemetryTester.runComprehensiveTest()
```

### Q: What if I accidentally commit an API key?

**A:**
1. **Immediately revoke** the exposed key via backend
2. **Remove from git history** using `git filter-branch` or BFG Repo-Cleaner
3. **Request new API key** from backend administrator
4. **Update local.properties** with new key
5. **Force push** cleaned history (if using private repo)
6. **Notify team members** to pull latest changes

### Q: How do I handle API keys in open-source projects?

**A:**
1. **Never include** real API keys in open-source code
2. **Provide template** `local.properties.template` with placeholder
3. **Document** in README how to obtain and configure API key
4. **Use example keys** in documentation (clearly marked as examples)

Example `local.properties.template`:
```properties
# Copy this file to local.properties and add your real API key
TELEMETRY_API_KEY=edge_your_api_key_here
```

### Q: Can I use different API keys for different build variants?

**A:** Yes! See [Example 2: Multi-Environment Setup](#example-2-multi-environment-setup) above.

---

## Additional Resources

- [README.md](README.md) - Main SDK documentation
- [INTEGRATION_SUMMARY.md](INTEGRATION_SUMMARY.md) - Integration guide
- [CHANGELOG.md](CHANGELOG.md) - Version history and migration guides
- [Android Keystore System](https://developer.android.com/training/articles/keystore) - Official Android documentation

---

## Support

If you encounter issues with API key management:

1. **Check this guide** for common solutions
2. **Review logs** with `debugMode = true`
3. **Contact backend administrator** for key-related issues
4. **Report SDK bugs** at [GitHub Issues](https://github.com/NCG-Africa/edge-telemetry-android/issues)

---

**Last Updated:** January 2025  
**SDK Version:** 1.2.8+

# Enterprise Camera Test App - Technical Specifications

## Problem Statement
- **Business Issue**: Current camera app has scattered implementation with multiple separate activities for different camera APIs (Camera1, Camera2, CameraX) and features, leading to code duplication and poor maintainability
- **Current State**: App consists of 10+ separate activities with inconsistent UI patterns, no unified camera management, and duplicated helper implementations
- **Expected Outcome**: Unified camera application with full-screen preview, icon-based controls, seamless API switching, background recording capability, and optimized performance through design patterns

## Solution Overview
- **Approach**: Refactor existing camera functionality into a unified architecture using design patterns for modularity and extensibility
- **Core Changes**: Consolidate camera activities into single main activity, implement strategy pattern for camera APIs, create unified camera manager, and redesign UI for full-screen experience
- **Success Criteria**: Single activity supporting all camera APIs with 50% reduction in code duplication, 30% faster camera initialization, and seamless background recording

## Technical Implementation

### Architecture Design with Design Patterns

#### 1. Strategy Pattern - Camera API Switching
```java
// Interface
package com.android.mycamera.camera.strategy

interface CameraStrategy {
    fun openCamera(config: CameraConfig)
    fun startPreview(surface: Surface)
    fun startRecording()
    fun stopRecording()
    fun closeCamera()
    fun getSupportedResolutions(): List<Resolution>
    fun getSupportedFrameRates(): List<Int>
}

// Implementations
class Camera1Strategy : CameraStrategy
class Camera2Strategy : CameraStrategy  
class CameraXStrategy : CameraStrategy

// Context
class CameraContext(private var strategy: CameraStrategy) {
    fun setStrategy(strategy: CameraStrategy) {
        this.strategy = strategy
    }
    
    fun executeCameraOperation(operation: CameraOperation) {
        when (operation) {
            CameraOperation.OPEN -> strategy.openCamera(currentConfig)
            CameraOperation.START_PREVIEW -> strategy.startPreview(currentSurface)
            // ... other operations
        }
    }
}
```

#### 2. Factory Pattern - Camera Helper Creation
```java
package com.android.mycamera.camera.factory

interface CameraFactory {
    fun createCameraHelper(apiType: CameraApiType): CameraHelper
    fun createMediaRecorder(): MediaRecorder
    fun createCameraConfig(): CameraConfig
}

class CameraHelperFactory : CameraFactory {
    override fun createCameraHelper(apiType: CameraApiType): CameraHelper {
        return when (apiType) {
            CameraApiType.CAMERA1 -> Camera1Helper()
            CameraApiType.CAMERA2 -> Camera2Helper()
            CameraApiType.CAMERAX -> CameraXHelper()
        }
    }
}
```

#### 3. Observer Pattern - Camera State Management
```java
package com.android.mycamera.camera.observer

interface CameraStateListener {
    fun onCameraOpened()
    fun onPreviewStarted()
    fun onRecordingStarted()
    fun onRecordingStopped()
    fun onCameraClosed()
    fun onError(error: CameraError)
}

class CameraStateManager {
    private val listeners = mutableListOf<CameraStateListener>()
    
    fun addListener(listener: CameraStateListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: CameraStateListener) {
        listeners.remove(listener)
    }
    
    fun notifyStateChanged(state: CameraState) {
        when (state) {
            CameraState.OPENED -> listeners.forEach { it.onCameraOpened() }
            CameraState.PREVIEW_STARTED -> listeners.forEach { it.onPreviewStarted() }
            // ... other states
        }
    }
}
```

#### 4. Builder Pattern - Configuration Settings
```java
package com.android.mycamera.camera.config

data class CameraConfig(
    val cameraId: String,
    val resolution: Resolution,
    val frameRate: Int,
    val quality: Quality,
    val audioEnabled: Boolean,
    val saveLocation: File
) {
    class Builder {
        private var cameraId: String = "0"
        private var resolution: Resolution = Resolution.HD_720P
        private var frameRate: Int = 30
        private var quality: Quality = Quality.HD
        private var audioEnabled: Boolean = true
        private var saveLocation: File = File(Environment.getExternalStorageDirectory(), "Camera")
        
        fun setCameraId(id: String) = apply { this.cameraId = id }
        fun setResolution(resolution: Resolution) = apply { this.resolution = resolution }
        fun setFrameRate(rate: Int) = apply { this.frameRate = rate }
        fun setQuality(quality: Quality) = apply { this.quality = quality }
        fun setAudioEnabled(enabled: Boolean) = apply { this.audioEnabled = enabled }
        fun setSaveLocation(location: File) = apply { this.saveLocation = location }
        
        fun build() = CameraConfig(cameraId, resolution, frameRate, quality, audioEnabled, saveLocation)
    }
}
```

#### 5. Singleton Pattern - Camera Manager
```java
package com.android.mycamera.camera.manager

class CameraManager private constructor(
    private val context: Context,
    private val factory: CameraFactory,
    private val stateManager: CameraStateManager
) {
    companion object {
        @Volatile
        private var instance: CameraManager? = null
        
        fun getInstance(context: Context): CameraManager {
            return instance ?: synchronized(this) {
                instance ?: CameraManager(
                    context.applicationContext,
                    CameraHelperFactory(),
                    CameraStateManager()
                ).also { instance = it }
            }
        }
    }
    
    private var currentStrategy: CameraStrategy? = null
    private var currentConfig: CameraConfig? = null
    
    fun switchCameraApi(apiType: CameraApiType, config: CameraConfig) {
        currentStrategy?.closeCamera()
        currentStrategy = factory.createCameraHelper(apiType)
        currentConfig = config
        currentStrategy?.openCamera(config)
    }
}
```

### UI/UX Design

#### 1. Full-Screen Camera Preview Layout
```xml
<!-- layout/activity_main_camera.xml -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/black">

    <!-- Camera Preview -->
    <TextureView
        android:id="@+id/cameraPreview"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Top Control Bar -->
    <LinearLayout
        android:id="@+id/topControls"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="16dp"
        android:background="@drawable/top_control_background"
        app:layout_constraintTop_toTopOf="parent">

        <ImageButton
            android:id="@+id/settingsButton"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:src="@drawable/ic_settings"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Settings" />

        <Space
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1" />

        <ImageButton
            android:id="@+id/switchCameraButton"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:src="@drawable/ic_camera_switch"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Switch Camera" />

        <ImageButton
            android:id="@+id/flashButton"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:src="@drawable/ic_flash_off"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Flash" />
    </LinearLayout>

    <!-- Bottom Control Panel -->
    <LinearLayout
        android:id="@+id/bottomControls"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="24dp"
        android:background="@drawable/bottom_control_background"
        app:layout_constraintBottom_toBottomOf="parent">

        <!-- Mode Switcher -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center">

            <ImageButton
                android:id="@+id/photoModeButton"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:src="@drawable/ic_photo_camera"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Photo Mode" />

            <Space
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/videoModeButton"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:src="@drawable/ic_video_camera"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Video Mode" />
        </LinearLayout>

        <!-- Capture Button -->
        <ImageButton
            android:id="@+id/captureButton"
            android:layout_width="72dp"
            android:layout_height="72dp"
            android:layout_gravity="center"
            android:layout_marginTop="24dp"
            android:src="@drawable/ic_capture_button"
            android:background="@drawable/capture_button_background"
            android:contentDescription="Capture" />

        <!-- Status Bar -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center"
            android:layout_marginTop="16dp">

            <TextView
                android:id="@+id/recordingTime"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="00:00"
                android:textColor="@color/white"
                android:textSize="16sp"
                android:visibility="gone" />

            <TextView
                android:id="@+id/statusText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Ready"
                android:textColor="@color/white"
                android:textSize="14sp" />
        </LinearLayout>
    </LinearLayout>

    <!-- Loading Overlay -->
    <ProgressBar
        android:id="@+id/loadingIndicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:visibility="gone"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

#### 2. Settings Activity Layout
```xml
<!-- layout/activity_settings.xml -->
<ScrollView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- Camera API Selection -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Camera API"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginBottom="8dp"/>

        <RadioGroup
            android:id="@+id/cameraApiGroup"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <RadioButton
                android:id="@+id/apiCameraX"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="CameraX (Recommended)"
                android:checked="true"/>

            <RadioButton
                android:id="@+id/apiCamera2"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Camera2 API"/>

            <RadioButton
                android:id="@+id/apiCamera1"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Camera1 API (Legacy)"/>
        </RadioGroup>

        <!-- Resolution Settings -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Resolution"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"
            android:layout_marginBottom="8dp"/>

        <Spinner
            android:id="@+id/resolutionSpinner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"/>

        <!-- Frame Rate Settings -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Frame Rate"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"
            android:layout_marginBottom="8dp"/>

        <Spinner
            android:id="@+id/frameRateSpinner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"/>

        <!-- Recording Settings -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Recording"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"
            android:layout_marginBottom="8dp"/>

        <Switch
            android:id="@+id/audioEnabledSwitch"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Enable Audio"/>

        <Switch
            android:id="@+id/backgroundRecordingSwitch"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Background Recording"/>

        <!-- Save Location -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Save Location"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"
            android:layout_marginBottom="8dp"/>

        <TextView
            android:id="@+id/saveLocationText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="/sdcard/Movies/Camera"
            android:padding="12dp"
            android:background="@drawable/edit_text_background"/>

        <Button
            android:id="@+id/changeLocationButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Change Location"
            android:layout_marginTop="8dp"/>

        <!-- Performance Settings -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Performance"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"
            android:layout_marginBottom="8dp"/>

        <Switch
            android:id="@+id/hardwareAccelerationSwitch"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Hardware Acceleration"
            android:checked="true"/>

        <Switch
            android:id="@+id/memoryOptimizationSwitch"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Memory Optimization"
            android:checked="true"/>

    </LinearLayout>
</ScrollView>
```

### Code Structure Refactoring

#### 1. Package Organization
```
com.android.mycamera/
├── camera/
│   ├── strategy/           # Strategy pattern implementations
│   │   ├── CameraStrategy.kt
│   │   ├── Camera1Strategy.kt
│   │   ├── Camera2Strategy.kt
│   │   └── CameraXStrategy.kt
│   ├── factory/           # Factory pattern implementations
│   │   ├── CameraFactory.kt
│   │   └── CameraHelperFactory.kt
│   ├── observer/          # Observer pattern implementations
│   │   ├── CameraStateListener.kt
│   │   └── CameraStateManager.kt
│   ├── config/            # Configuration classes
│   │   ├── CameraConfig.kt
│   │   └── CameraConfigBuilder.kt
│   └── manager/           # Singleton manager
│       └── CameraManager.kt
├── ui/
│   ├── activity/          # Activities
│   │   ├── MainActivity.kt
│   │   └── SettingsActivity.kt
│   ├── fragment/          # Fragments
│   │   ├── CameraFragment.kt
│   │   └── SettingsFragment.kt
│   ├── adapter/           # Adapters
│   │   └── SettingsAdapter.kt
│   └── view/              # Custom views
│       ├── CameraPreview.kt
│       └── CaptureButton.kt
├── service/               # Services
│   ├── CameraService.kt
│   └── BackgroundRecordService.kt
├── utils/                 # Utility classes
│   ├── PermissionUtils.kt
│   ├── StorageUtils.kt
│   └── PerformanceUtils.kt
├── model/                 # Data models
│   ├── CameraState.kt
│   ├── Resolution.kt
│   └── CameraError.kt
└── repository/            # Data repositories
    ├── CameraRepository.kt
    └── SettingsRepository.kt
```

#### 2. Interface Definitions
```kotlin
// Core interfaces
interface CameraStrategy {
    suspend fun openCamera(config: CameraConfig): Result<Unit>
    suspend fun startPreview(surface: Surface): Result<Unit>
    suspend fun startRecording(): Result<Unit>
    suspend fun stopRecording(): Result<Unit>
    suspend fun closeCamera(): Result<Unit>
    fun getSupportedResolutions(): List<Resolution>
    fun getSupportedFrameRates(): List<Int>
    fun getCurrentState(): CameraState
}

interface CameraStateListener {
    fun onStateChanged(state: CameraState)
    fun onError(error: CameraError)
}

interface MediaSaver {
    suspend fun saveMedia(mediaFile: File, mediaType: MediaType): Result<Uri>
    suspend fun getAvailableSpace(): Long
}

interface PerformanceMonitor {
    fun startMonitoring()
    fun stopMonitoring()
    fun getMetrics(): PerformanceMetrics
}
```

#### 3. Base Classes and Abstractions
```kotlin
// Base camera strategy
abstract class BaseCameraStrategy : CameraStrategy {
    protected var currentState: CameraState = CameraState.IDLE
    protected val stateListeners = mutableListOf<CameraStateListener>()
    
    override fun addStateListener(listener: CameraStateListener) {
        stateListeners.add(listener)
    }
    
    override fun removeStateListener(listener: CameraStateListener) {
        stateListeners.remove(listener)
    }
    
    protected fun notifyStateChanged(newState: CameraState) {
        currentState = newState
        stateListeners.forEach { it.onStateChanged(newState) }
    }
    
    protected fun notifyError(error: CameraError) {
        stateListeners.forEach { it.onError(error) }
    }
}

// Base activity
abstract class BaseActivity : AppCompatActivity() {
    protected lateinit var cameraManager: CameraManager
    protected lateinit var permissionManager: PermissionManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeManagers()
    }
    
    private fun initializeManagers() {
        cameraManager = CameraManager.getInstance(this)
        permissionManager = PermissionManager.getInstance(this)
    }
    
    protected fun requestPermissions(permissions: Array<String>, callback: (Boolean) -> Unit) {
        permissionManager.requestPermissions(permissions, callback)
    }
}
```

#### 4. Utility Classes
```kotlin
// Permission utilities
class PermissionManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: PermissionManager? = null
        
        fun getInstance(context: Context): PermissionManager {
            return instance ?: synchronized(this) {
                instance ?: PermissionManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    fun requestPermissions(permissions: Array<String>, callback: (Boolean) -> Unit) {
        // Implementation
    }
    
    fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}

// Storage utilities
class StorageUtils {
    companion object {
        fun getCameraDirectory(): File {
            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        }
        
        fun getAvailableStorageSpace(): Long {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            return stat.availableBlocksLong * stat.blockSizeLong
        }
        
        fun generateUniqueFileName(extension: String): String {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            return "CAM_$timestamp.$extension"
        }
    }
}

// Performance utilities
class PerformanceUtils {
    companion object {
        fun measureExecutionTime(block: () -> Unit): Long {
            val startTime = System.currentTimeMillis()
            block()
            return System.currentTimeMillis() - startTime
        }
        
        fun logMemoryUsage(tag: String) {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            Log.d(tag, "Memory usage: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB")
        }
    }
}
```

#### 5. Configuration Management
```kotlin
// Settings repository
class SettingsRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val sharedPreferences = context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
    
    fun getCameraApi(): CameraApiType {
        val apiName = sharedPreferences.getString("camera_api", "CAMERAX") ?: "CAMERAX"
        return CameraApiType.valueOf(apiName)
    }
    
    fun setCameraApi(apiType: CameraApiType) {
        sharedPreferences.edit().putString("camera_api", apiType.name).apply()
    }
    
    fun getResolution(): Resolution {
        val resolutionString = sharedPreferences.getString("resolution", "1920x1080") ?: "1920x1080"
        return Resolution.fromString(resolutionString)
    }
    
    fun setResolution(resolution: Resolution) {
        sharedPreferences.edit().putString("resolution", resolution.toString()).apply()
    }
    
    fun getFrameRate(): Int {
        return sharedPreferences.getInt("frame_rate", 30)
    }
    
    fun setFrameRate(frameRate: Int) {
        sharedPreferences.edit().putInt("frame_rate", frameRate).apply()
    }
    
    fun isBackgroundRecordingEnabled(): Boolean {
        return sharedPreferences.getBoolean("background_recording", false)
    }
    
    fun setBackgroundRecordingEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("background_recording", enabled).apply()
    }
}
```

### Performance Optimization

#### 1. Camera Initialization Optimization
```kotlin
// Optimized camera initialization
class OptimizedCameraManager {
    private val cameraCache = mutableMapOf<String, CameraStrategy>()
    private val initializationLock = Mutex()
    
    suspend fun initializeCamera(config: CameraConfig): Result<CameraStrategy> {
        val cacheKey = "${config.cameraId}_${config.resolution}"
        
        return if (cameraCache.containsKey(cacheKey)) {
            Result.success(cameraCache[cacheKey]!!)
        } else {
            initializationLock.withLock {
                if (cameraCache.containsKey(cacheKey)) {
                    Result.success(cameraCache[cacheKey]!!)
                } else {
                    val strategy = createCameraStrategy(config)
                    strategy.openCamera(config).onSuccess {
                        cameraCache[cacheKey] = strategy
                    }
                    Result.success(strategy)
                }
            }
        }
    }
    
    private fun createCameraStrategy(config: CameraConfig): CameraStrategy {
        return when (config.apiType) {
            CameraApiType.CAMERA1 -> Camera1Strategy()
            CameraApiType.CAMERA2 -> Camera2Strategy()
            CameraApiType.CAMERAX -> CameraXStrategy()
        }
    }
}
```

#### 2. Memory Management Improvements
```kotlin
// Memory optimization utilities
class MemoryManager {
    private val weakReferences = mutableMapOf<String, WeakReference<Any>>()
    private val memoryThreshold = 0.8 // 80% of available memory
    
    fun addCachedObject(key: String, obj: Any) {
        if (isMemoryAvailable()) {
            weakReferences[key] = WeakReference(obj)
        } else {
            clearCache()
            weakReferences[key] = WeakReference(obj)
        }
    }
    
    fun getCachedObject(key: String): Any? {
        return weakReferences[key]?.get()
    }
    
    private fun isMemoryAvailable(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        return (usedMemory.toDouble() / maxMemory) < memoryThreshold
    }
    
    private fun clearCache() {
        weakReferences.clear()
        System.gc()
    }
}
```

#### 3. UI Rendering Optimization
```kotlin
// Optimized UI rendering
class OptimizedCameraPreview(context: Context, attrs: AttributeSet) : TextureView(context, attrs) {
    private var isHardwareAccelerated = true
    
    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }
    
    override fun onDraw(canvas: Canvas?) {
        if (isHardwareAccelerated) {
            // Hardware-accelerated rendering
            super.onDraw(canvas)
        } else {
            // Fallback to software rendering
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            super.onDraw(canvas)
        }
    }
    
    fun optimizeForPerformance() {
        // Set optimal surface size
        val displayMetrics = DisplayMetrics()
        (context as Activity).windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        val optimalWidth = displayMetrics.widthPixels
        val optimalHeight = displayMetrics.heightPixels
        
        surfaceTexture?.setDefaultBufferSize(optimalWidth, optimalHeight)
    }
}
```

#### 4. Background Processing Efficiency
```kotlin
// Efficient background processing
class BackgroundCameraService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraManager = CameraManager.getInstance(this)
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startBackgroundRecording()
            ACTION_STOP_RECORDING -> stopBackgroundRecording()
        }
        return START_STICKY
    }
    
    private fun startBackgroundRecording() {
        serviceScope.launch {
            try {
                val config = getBackgroundRecordingConfig()
                cameraManager.switchCameraApi(config.apiType, config)
                cameraManager.startRecording()
                updateNotification("Recording...")
            } catch (e: Exception) {
                Log.e(TAG, "Background recording failed", e)
                stopSelf()
            }
        }
    }
    
    private fun stopBackgroundRecording() {
        serviceScope.launch {
            try {
                cameraManager.stopRecording()
                updateNotification("Recording stopped")
                stopForeground(true)
            } catch (e: Exception) {
                Log.e(TAG, "Stop recording failed", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
```

### Implementation Sequence

#### Phase 1: Core Architecture Setup
1. **Create package structure** - Reorganize existing code into new package hierarchy
2. **Implement design patterns** - Create strategy, factory, observer, builder, and singleton patterns
3. **Create base classes** - Implement BaseActivity, BaseCameraStrategy, and utility classes
4. **Set up configuration management** - Create SettingsRepository and CameraConfig

#### Phase 2: Camera API Integration
1. **Refactor existing camera helpers** - Convert Camera1Helper, Camera2Helper, CameraXHelper to strategy pattern
2. **Create unified camera manager** - Implement CameraManager singleton with strategy switching
3. **Integrate camera state management** - Add observer pattern for camera state changes
4. **Create camera configuration system** - Implement builder pattern for camera settings

#### Phase 3: UI Implementation
1. **Create main activity** - Implement unified camera interface with full-screen preview
2. **Design icon-based controls** - Create custom views for camera controls
3. **Implement settings activity** - Create comprehensive settings interface
4. **Add mode switching** - Implement photo/video mode switching in unified interface

#### Phase 4: Background Recording Setup
1. **Create background service** - Implement BackgroundCameraService for background recording
2. **Add notification system** - Create persistent notification for background recording
3. **Implement service binding** - Connect main activity to background service
4. **Add lifecycle management** - Handle service lifecycle properly

#### Phase 5: Performance Optimization
1. **Optimize camera initialization** - Implement caching and lazy loading
2. **Improve memory management** - Add memory monitoring and cleanup
3. **Optimize UI rendering** - Implement hardware acceleration and efficient rendering
4. **Add performance monitoring** - Create metrics collection and reporting

### Validation Plan

#### Unit Tests
1. **Camera strategy tests** - Test each camera API implementation
2. **Design pattern tests** - Verify strategy, factory, observer, builder, and singleton patterns
3. **Configuration tests** - Test settings persistence and camera configuration
4. **Performance tests** - Test memory management and initialization speed

#### Integration Tests
1. **Camera switching tests** - Test seamless switching between camera APIs
2. **Background recording tests** - Test background recording functionality
3. **UI interaction tests** - Test user interface responsiveness and functionality
4. **Settings tests** - Test settings persistence and application

#### Business Logic Verification
1. **Feature completeness** - Verify all required features are implemented
2. **Performance metrics** - Measure camera initialization time and memory usage
3. **User experience** - Test overall user experience and interface consistency
4. **Compatibility testing** - Test on different Android versions and devices

## Success Metrics

### Code Quality Metrics
- **Code duplication reduction**: Target 50% reduction in duplicated code
- **Test coverage**: Target 80% test coverage for core functionality
- **Code complexity**: Reduce cyclomatic complexity by 30%

### Performance Metrics
- **Camera initialization time**: Target 30% improvement
- **Memory usage**: Target 25% reduction in peak memory usage
- **UI responsiveness**: Target 60fps rendering performance
- **Battery efficiency**: Target 20% reduction in battery consumption

### User Experience Metrics
- **Feature availability**: All requested features must be functional
- **Interface consistency**: Unified interface across all camera modes
- **Error handling**: Graceful handling of camera errors and edge cases
- **Accessibility**: Full accessibility support for all UI elements

## Risk Mitigation

### Technical Risks
- **Camera API compatibility**: Implement fallback mechanisms for different Android versions
- **Memory management**: Add robust memory monitoring and cleanup
- **Performance regression**: Implement continuous performance monitoring
- **Background recording**: Ensure proper lifecycle management and resource cleanup

### Implementation Risks
- **Code refactoring complexity**: Use incremental refactoring approach
- **Testing coverage**: Implement comprehensive test suite
- **Feature regression**: Maintain existing functionality during refactoring
- **Timeline management**: Break implementation into manageable phases

## Conclusion

This technical specification provides a comprehensive blueprint for refactoring the camera application using modern design patterns and performance optimization techniques. The implementation will result in a unified, maintainable, and performant camera application that meets all business requirements while providing an excellent user experience.

The architecture emphasizes modularity, extensibility, and performance, making it easier to maintain and extend in the future. The use of design patterns ensures clean separation of concerns and makes the codebase more testable and maintainable.
# 后台录制功能集成指南

## 概述

本指南展示了如何将新的后台录制功能集成到现有的相机应用中。新的后台录制策略基于 `bgr_yes` 包的实现，并与重构后的相机架构完全集成。

## 核心组件

### 1. BackgroundRecordingService
- **位置**: `com.android.mycamera.camera.strategy.BackgroundRecordingService`
- **功能**: 前台服务，处理后台录制操作
- **特点**: 
  - 集成到新的相机架构
  - 使用 `SettingsManager.isBackgroundRecordingEnabled()` 控制功能
  - 支持状态管理和错误处理

### 2. BackgroundRecordingStrategy
- **位置**: `com.android.mycamera.camera.strategy.BackgroundRecordingStrategy`
- **功能**: 后台录制策略，封装录制逻辑
- **特点**:
  - 使用现有的相机策略
  - 支持动态配置更新
  - 集成性能监控

### 3. BackgroundRecordingHelper
- **位置**: `com.android.mycamera.camera.helper.BackgroundRecordingHelper`
- **功能**: 简化后台录制的使用
- **特点**:
  - 自动服务绑定和管理
  - 简化的API接口
  - 状态监控

### 4. CameraManager 集成
- **新增方法**:
  - `isBackgroundRecordingEnabled()` - 检查是否启用后台录制
  - `startBackgroundRecordingService()` - 启动后台录制服务
  - `startBackgroundRecording()` - 开始后台录制
  - `stopBackgroundRecording()` - 停止后台录制
  - `getBackgroundRecordingStatus()` - 获取状态

## 使用示例

### 1. 在 MainActivity 中集成

```java
// 在 MainActivity 中添加后台录制支持
public class MainActivity extends AppCompatActivity implements CameraStateObserver {
    
    private CameraManager cameraManager;
    private BackgroundRecordingHelper backgroundRecordingHelper;
    private ImageButton backgroundRecordingButton;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // 初始化相机管理器
        cameraManager = CameraManager.getInstance(this);
        
        // 初始化后台录制助手
        backgroundRecordingHelper = new BackgroundRecordingHelper(this, cameraManager);
        
        // 设置UI
        setupBackgroundRecordingUI();
    }
    
    private void setupBackgroundRecordingUI() {
        backgroundRecordingButton = findViewById(R.id.backgroundRecordingButton);
        
        backgroundRecordingButton.setOnClickListener(v -> {
            if (backgroundRecordingHelper.isRecording()) {
                stopBackgroundRecording();
            } else {
                startBackgroundRecording();
            }
        });
        
        // 更新按钮状态
        updateBackgroundRecordingButton();
    }
    
    private void startBackgroundRecording() {
        if (backgroundRecordingHelper.startRecording()) {
            Toast.makeText(this, "后台录制已开始", Toast.LENGTH_SHORT).show();
            updateBackgroundRecordingButton();
        } else {
            Toast.makeText(this, "启动后台录制失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void stopBackgroundRecording() {
        if (backgroundRecordingHelper.stopRecording()) {
            Toast.makeText(this, "后台录制已停止", Toast.LENGTH_SHORT).show();
            updateBackgroundRecordingButton();
        } else {
            Toast.makeText(this, "停止后台录制失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateBackgroundRecordingButton() {
        if (backgroundRecordingHelper.isRecording()) {
            backgroundRecordingButton.setImageResource(R.drawable.ic_stop_button);
            backgroundRecordingButton.setBackgroundColor(Color.RED);
        } else {
            backgroundRecordingButton.setImageResource(R.drawable.ic_record_button);
            backgroundRecordingButton.setBackgroundColor(Color.GRAY);
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 初始化后台录制
        backgroundRecordingHelper.initialize();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放后台录制资源
        if (backgroundRecordingHelper != null) {
            backgroundRecordingHelper.release();
        }
    }
}
```

### 2. 在 SettingsActivity 中集成

```java
// 在 SettingsActivity 中添加后台录制设置开关
public class SettingsActivity extends AppCompatActivity {
    
    private Switch backgroundRecordingSwitch;
    private CameraManager cameraManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        cameraManager = CameraManager.getInstance(this);
        backgroundRecordingSwitch = findViewById(R.id.backgroundRecordingSwitch);
        
        // 设置当前状态
        backgroundRecordingSwitch.setChecked(cameraManager.isBackgroundRecordingEnabled());
        
        backgroundRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cameraManager.setBackgroundRecordingEnabled(isChecked);
            
            if (isChecked) {
                Toast.makeText(this, "后台录制已启用", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "后台录制已禁用", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
```

### 3. 在 XML 布局中添加按钮

```xml
<!-- 在 activity_main_camera.xml 中添加后台录制按钮 -->
<ImageButton
    android:id="@+id/backgroundRecordingButton"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:src="@drawable/ic_record_button"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="Background Recording"
    android:tint="@color/white"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintEnd_toEndOf="parent"
    android:layout_marginEnd="16dp"
    android:layout_marginTop="16dp"/>
```

## 权限要求

确保在 AndroidManifest.xml 中包含以下权限：

```xml
<!-- 已有的权限 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

<!-- 后台录制所需权限 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## 服务配置

BackgroundRecordingService 已在 AndroidManifest.xml 中注册：

```xml
<service
    android:name=".camera.strategy.BackgroundRecordingService"
    android:enabled="true"
    android:exported="false" />
```

## 状态管理

后台录制支持以下状态：

- **Disabled**: 在设置中禁用
- **Service not running**: 服务未启动
- **Initializing**: 正在初始化
- **Ready**: 准备就绪，可以开始录制
- **Recording**: 正在录制

## 错误处理

所有后台录制操作都包含错误处理：

- 服务绑定失败
- 录制启动失败
- 权限不足
- 相机不可用

## 性能监控

后台录制集成了性能监控功能：

- 初始化时间监控
- 录制性能监控
- 内存使用监控
- 错误日志记录

## 注意事项

1. **电池消耗**: 后台录制会消耗较多电量，建议在使用时提示用户
2. **存储空间**: 确保设备有足够的存储空间
3. **设备兼容性**: 某些设备可能不支持后台录制
4. **用户隐私**: 确保遵守相关隐私法规，明确告知用户正在录制

## 调试

使用以下方法进行调试：

```java
// 获取后台录制状态
String status = cameraManager.getBackgroundRecordingStatus();
Log.d("BackgroundRecording", "Status: " + status);

// 获取性能统计
String stats = cameraManager.getPerformanceStats();
Log.d("BackgroundRecording", "Performance: " + stats);
```

这个后台录制策略现在完全集成到了新的相机架构中，使用了 `SettingsManager.isBackgroundRecordingEnabled()` 方法，并参考了 `bgr_yes` 包的实现方式。
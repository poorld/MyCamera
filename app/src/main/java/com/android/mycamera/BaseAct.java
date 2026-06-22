package com.android.mycamera;

import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.android.mycamera.utils.LocaleUtils;
import com.android.mycamera.utils.SettingsManager;

public class BaseAct extends AppCompatActivity {

    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LocaleUtils.applySavedLanguage(this);
        super.onCreate(savedInstanceState);

        // 设置全屏和沉浸式模式
        setupFullScreenMode();

    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKeepScreenOnSetting();
    }

    private void setupFullScreenMode() {
        applyKeepScreenOnSetting();

        // 设置沉浸式全屏模式
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener(){
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (e1 == null || e2 == null) {
                    return false;
                }

                if(e2.getRawY()-e1.getRawY()>50){//
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE
                    );
                    return true;
                }
                if(e1.getRawY()-e2.getRawY()>50){
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_VISIBLE |View.SYSTEM_UI_FLAG_IMMERSIVE);
                    return true;
                }
                return super.onFling(e1, e2, velocityX, velocityY);
            }
        });
    }

    private void applyKeepScreenOnSetting() {
        if (new SettingsManager(this).isKeepScreenOnEnabled()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

}

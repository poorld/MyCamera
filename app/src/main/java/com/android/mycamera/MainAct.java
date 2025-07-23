package com.android.mycamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.mycamera.bgr.BackgroudCameraActivity;
import com.android.mycamera.bgr_yes.BgrYesActivity;
import com.android.mycamera.camera.Cam1ApiActivity;
import com.android.mycamera.camera.Cam2ApiActivity;
import com.android.mycamera.camera.CamSizeActivity;
import com.android.mycamera.camera.CamXApiActivity;
import com.android.mycamera.record.VideoRecordActivity;

public class MainAct extends BaseAct {

    private Button camera_x;
    private Button camera2_api;
    private Button camera_api;
    private Button camera_size;
    private Button video_record_button;
    private Button background_record;
    private Button background_record_test;
    private TextView titleTextView;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE };

    private static final int REQUEST_CODE_PERMISSIONS = 10;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.act_main);

        titleTextView = findViewById(R.id.titleTextView);
        camera_size = findViewById(R.id.camera_size);
        camera_x = findViewById(R.id.camera_x);
        camera2_api = findViewById(R.id.camera2_api);
        camera_api = findViewById(R.id.camera_api);
        video_record_button = findViewById(R.id.video_record_button);
        background_record = findViewById(R.id.background_record);
        background_record_test = findViewById(R.id.background_record_test);

        camera_size.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainAct.this, CamSizeActivity.class));
            }
        });

        camera_x.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainAct.this, CamXApiActivity.class));
            }
        });

        camera2_api.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainAct.this, Cam2ApiActivity.class));
            }
        });

        camera_api.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainAct.this, Cam1ApiActivity.class));
            }
        });

        video_record_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainAct.this, VideoRecordActivity.class));
            }
        });
        background_record.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // startActivity(new Intent(MainAct.this, CameraActivity.class));
                startActivity(new Intent(MainAct.this, BgrYesActivity.class));
            }
        });
        background_record_test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // startActivity(new Intent(MainAct.this, CameraActivity.class));
                startActivity(new Intent(MainAct.this, BackgroudCameraActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allPermissionsGranted()) {
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {

            } else {
                Toast.makeText(this, "权限被拒绝", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}

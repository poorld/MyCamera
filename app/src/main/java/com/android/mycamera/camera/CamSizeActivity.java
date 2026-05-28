package com.android.mycamera.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.android.mycamera.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * Camera Support Size
 */
public class CamSizeActivity extends AppCompatActivity {

    private CameraManager cameraManager;
    private String mCurrentCameraId = "0";
    private Spinner size_spinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_cam_size_api);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        size_spinner = findViewById(R.id.size_spinner);
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, cameraIdList);
            sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            size_spinner.setAdapter(sizeAdapter);
            size_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    chooseCam(cameraIdList[position]);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        chooseCam(mCurrentCameraId);
    }

    private void chooseCam(String camId) {
        CameraCharacteristics characteristics = null;
        try {
            characteristics = cameraManager.getCameraCharacteristics(camId);
            StreamConfigurationMap s = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<Size> supportedSizes = getSupportedPictureSize(s, ImageFormat.JPEG);
            if (supportedSizes != null) {
                StringBuffer sb = new StringBuffer();
                for (Size supportedSize : supportedSizes) {
                    Log.d("GG", "supportedSize: " + supportedSize);
                    sb.append(supportedSize);
                    sb.append("\n");
                }
                TextView tv = findViewById(R.id.gg);
                tv.setText(sb.toString());
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private List<Size> getSupportedPictureSize(StreamConfigurationMap s, int format) {
        if (s == null) {
            return null;
        }
        List<Size> supportedValues = new ArrayList<>();
        Size[] highSizes = s.getHighResolutionOutputSizes(format);
        if (highSizes != null) {
            for (Size size : highSizes) {
                supportedValues.add(size);
            }
        }

        Size[] sizes = s.getOutputSizes(format);
        if (sizes != null) {
            for (Size size : sizes) {
                supportedValues.add(size);
            }
        }
        return supportedValues;
    }
}
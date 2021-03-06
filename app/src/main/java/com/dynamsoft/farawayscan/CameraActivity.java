package com.dynamsoft.farawayscan;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceManager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dynamsoft.dbr.BarcodeReader;
import com.dynamsoft.dbr.BarcodeReaderException;
import com.dynamsoft.dbr.DBRLTSLicenseVerificationListener;
import com.dynamsoft.dbr.DMLTSConnectionParameters;
import com.dynamsoft.dbr.EnumConflictMode;
import com.dynamsoft.dbr.EnumImagePixelFormat;
import com.dynamsoft.dbr.EnumIntermediateResultSavingMode;
import com.dynamsoft.dbr.EnumIntermediateResultType;
import com.dynamsoft.dbr.EnumResultCoordinateType;
import com.dynamsoft.dbr.EnumScaleUpMode;
import com.dynamsoft.dbr.IntermediateResult;
import com.dynamsoft.dbr.LocalizationResult;
import com.dynamsoft.dbr.Point;
import com.dynamsoft.dbr.PublicRuntimeSettings;
import com.dynamsoft.dbr.TextResult;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends AppCompatActivity {
    private PreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private TextView resultView;
    private ImageView codeImageView;
    private ImageView srImageView;
    private ExecutorService exec;
    private Camera camera;
    private ImageAnalysis imageAnalysis;
    private BarcodeReader dbr;
    private SeekBar zoomRatioSeekBar;
    private Long TouchDownTime;
    private SharedPreferences prefs;
    private SuperResolution sr;
    private ImageAnalysis.Analyzer analyzer;
    private Context ctx;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();
        setContentView(R.layout.activity_camera);
        ctx=this;
        previewView = findViewById(R.id.previewView);
        resultView = findViewById(R.id.resultView);
        codeImageView = findViewById(R.id.codeImageView);
        srImageView = findViewById(R.id.srImageView);
        codeImageView.setImageBitmap(null);
        srImageView.setImageBitmap(null);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        exec = Executors.newSingleThreadExecutor();
        sr = new SuperResolution(this);
        analyzer = getAnalyzer();
        try {
            dbr = new BarcodeReader("t0068MgAAAJWPwDybm7nk0f9xYH25MMaVrZYcmhsiVoZrVo2hfcwRS74T6QA79OfzyvhC+9fgFI2noI8zBc66WHFCusVUgqk=");
            Utils.updateDBRSettings(dbr,prefs);
        } catch (BarcodeReaderException e) {
            e.printStackTrace();
        }

        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindPreviewAndImageAnalysis(cameraProvider);
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, ContextCompat.getMainExecutor(this));

        zoomRatioSeekBar = findViewById(R.id.zoomRatioSeekBar);
        zoomRatioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                camera.getCameraControl().setLinearZoom((float) progress / 100);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }

    @Override
    public void onBackPressed() {
        if (zoomRatioSeekBar.getProgress() > 0) {
            zoomRatioSeekBar.setProgress(0);
            codeImageView.setImageBitmap(null);
            srImageView.setImageBitmap(null);
            Boolean continuous_scan = prefs.getBoolean("continuous_scan",false);
            if (continuous_scan==false){
                imageAnalysis.clearAnalyzer();
                imageAnalysis.setAnalyzer(exec,analyzer);
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // TODO Auto-generated method stub
        if (event.getAction() == MotionEvent.ACTION_UP) {
            Long heldTime = System.currentTimeMillis() - TouchDownTime;

            if (heldTime > 600) { //long press
                focus(previewView.getWidth(), previewView.getHeight(), event.getX(), event.getY(), false);
                Toast.makeText(this, "Refocus after 5 seconds.", Toast.LENGTH_SHORT).show();
                focus(previewView.getWidth(), previewView.getHeight(), event.getX(), event.getY(), true);
            }

        } else if (event.getAction() == MotionEvent.ACTION_DOWN) {
            TouchDownTime = System.currentTimeMillis();
        }
        return super.onTouchEvent(event);
    }

    private void focus(float width, float height, float x, float y, boolean autoCancel) {
        CameraControl cameraControl = camera.getCameraControl();
        MeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(width, height);
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction.Builder builder = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF);
        if (autoCancel) {
            // auto calling cancelFocusAndMetering in 5 seconds
            builder.setAutoCancelDuration(5, TimeUnit.SECONDS);
        } else {
            builder.disableAutoCancel();
        }
        FocusMeteringAction action = builder.build();
        ListenableFuture future = cameraControl.startFocusAndMetering(action);
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private void bindPreviewAndImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        int resWidth=720;
        int resHeight=1280;
        if (prefs.getString("resolution", "1080P").equals("1080P")) {
            resWidth=1080;
            resHeight=1920;
        } else if (prefs.getString("resolution", "4K").equals("4K")){
            resWidth=2160;
            resHeight=3840;
        }
        Size resolution = new Size(resWidth, resHeight);
        Display d = getDisplay();
        if (d.getRotation() != Surface.ROTATION_0) {
            resolution = new Size(resHeight, resWidth);
        }
        Log.d("DBR",resHeight+"x"+resWidth);
        Preview.Builder previewBuilder = new Preview.Builder();

        previewBuilder.setTargetResolution(resolution);
        Preview preview = previewBuilder.build();

        ImageAnalysis.Builder imageAnalysisBuilder = new ImageAnalysis.Builder();

        //Camera2Interop.Extender ext = new Camera2Interop.Extender<>(imageAnalysisBuilder);
        //ext.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE,CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE);

        imageAnalysisBuilder.setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

        imageAnalysis = imageAnalysisBuilder.build();
        imageAnalysis.setAnalyzer(exec, analyzer);

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
        preview.setSurfaceProvider(previewView.createSurfaceProvider());

        UseCaseGroup useCaseGroup = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .build();
        camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, useCaseGroup);
        Log.d("DBR","max ratio: "+camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio());
    }

    private ImageAnalysis.Analyzer getAnalyzer(){
        return new ImageAnalysis.Analyzer() {
            @SuppressLint("UnsafeExperimentalUsageError")
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void analyze(@NonNull ImageProxy image) {
                int rotationDegrees = image.getImageInfo().getRotationDegrees();
                Log.d("DBR",image.getWidth()+"x"+image.getHeight());
                Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                YuvToRgbConverter converter = new YuvToRgbConverter(CameraActivity.this);
                converter.yuvToRgb(image.getImage(), bitmap);
                bitmap = Utils.rotatedBitmap(bitmap, rotationDegrees);
                TextResult[] decoded = decodeBitmap(bitmap);
                if (decoded.length == 0 || decoded == null){
                    if (prefs.getBoolean("superresolution", false) == true){
                        try {
                            Point[] resultPoints = Utils.getResultsPointsWithHighestConfidence(dbr.getIntermediateResults());
                            if (resultPoints!=null){
                                Log.d("DBR","run super resolution");
                                Bitmap cropped = Utils.DetecetdBarcodeZone(resultPoints,bitmap);
                                Bitmap srbm = sr.SuperResolutionImage(cropped);
                                decoded = decodeBitmap(srbm);
                                if (decoded.length>0){
                                    UpdateCodeAndSRImage(cropped,srbm);
                                    Utils.saveRecord(Utils.getBarcodeResult(decoded),cropped,srbm,ctx,prefs);
                                }
                            }
                        } catch (BarcodeReaderException | IOException e) {
                            e.printStackTrace();
                        }
                    }
                } else if (decoded.length>0){
                    UpdateCodeImage(decoded[0].localizationResult.resultPoints,bitmap);
                    if (prefs.getBoolean("save_only_superresolution", false) == false){
                        try {
                            Utils.saveRecord(Utils.getBarcodeResult(decoded),bitmap,null,ctx,prefs);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                image.close();
            }
        };
    }

    private TextResult[] decodeBitmap(Bitmap bitmap){
        TextResult[] results = null;
        try {
            results = dbr.decodeBufferedImage(bitmap, "");
        } catch (BarcodeReaderException | IOException e) {
            e.printStackTrace();
        }

        if (results.length==0 && zoomRatioSeekBar.getProgress()<40){
            if (prefs.getBoolean("autozoom", true) == true){
                Log.d("DBR", "auto zoom");
                try {
                    AutoZoom(dbr.getIntermediateResults(),bitmap);
                } catch (BarcodeReaderException e) {
                    e.printStackTrace();
                }
            }
        }
        Boolean continuous_scan = prefs.getBoolean("continuous_scan",false);
        if (results.length>0 && continuous_scan==false){
            imageAnalysis.clearAnalyzer();
        }
        String resultString = Utils.getBarcodeResult(results);
        Log.d("DBR", resultString);
        UpdateResult(resultString);
        return results;
    }

    private void AutoZoom(IntermediateResult[] intermediateResults,Bitmap bitmap){
        Log.d("DBR", "results: "+intermediateResults.length);
        Point[] resultPoints=Utils.getResultsPointsWithHighestConfidence(intermediateResults);
        if (resultPoints!=null){
            ZoominToBarcodeZone(resultPoints,bitmap);
        }
    }

    private double UpdateCodeImage(Point[] resultPoints,Bitmap bitmap){
        int minX,maxX,minY,maxY;
        minX=resultPoints[0].x;
        minY=resultPoints[0].y;
        maxX=0;
        maxY=0;
        for (Point point:resultPoints){
            minX=Math.min(point.x,minX);
            minY=Math.min(point.y,minY);
            maxX=Math.max(point.x,maxX);
            maxY=Math.max(point.y,maxY);
        }
        int width = maxX-minX;
        int height = maxY-minY;
        if (width<0 || height<0){
            return 0;
        }
        Bitmap cropped = Bitmap.createBitmap(bitmap, minX, minY, width, height);
        double percent = Math.min((double) minX/bitmap.getWidth(),(double) minY/bitmap.getHeight());
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // This code will always run on the UI thread, therefore is safe to modify UI elements.
                codeImageView.setImageBitmap(cropped);
            }
        });
        return percent;
    }

    private void UpdateCodeAndSRImage(Bitmap cropped,Bitmap sr){
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // This code will always run on the UI thread, therefore is safe to modify UI elements.
                codeImageView.setImageBitmap(cropped);
                srImageView.setImageBitmap(sr);
            }
        });
    }

    private void UpdateResult(String result){
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // This code will always run on the UI thread, therefore is safe to modify UI elements.
                resultView.setText(result);
            }
        });
    }

    private void ZoominToBarcodeZone(Point[] resultPoints,Bitmap bitmap){
        double percent = UpdateCodeImage(resultPoints,bitmap);
        int progress = (int) (percent*100);
        int finalProgress = 100-progress;
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // This code will always run on the UI thread, therefore is safe to modify UI elements.
                zoomRatioSeekBar.setProgress(finalProgress);
            }
        });

    }

}

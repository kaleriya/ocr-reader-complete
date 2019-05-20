/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.gms.samples.vision.ocrreader.ui.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.support.annotation.StringDef;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.google.android.gms.common.images.Size;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;

import java.io.IOException;
import java.lang.Thread.State;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@SuppressWarnings("deprecation")
public class CameraSource {
    @SuppressLint("InlinedApi")
    public static final int CAMERA_FACING_BACK = CameraInfo.CAMERA_FACING_BACK;
    @SuppressLint("InlinedApi")
    public static final int CAMERA_FACING_FRONT = CameraInfo.CAMERA_FACING_FRONT;

    private static final String TAG = "OpenCameraSource";
    private static final int DUMMY_TEXTURE_NAME = 100;
    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;

    @StringDef({
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
            Camera.Parameters.FOCUS_MODE_AUTO,
            Camera.Parameters.FOCUS_MODE_EDOF,
            Camera.Parameters.FOCUS_MODE_FIXED,
            Camera.Parameters.FOCUS_MODE_INFINITY,
            Camera.Parameters.FOCUS_MODE_MACRO
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface FocusMode {}

    @StringDef({
            Camera.Parameters.FLASH_MODE_ON,
            Camera.Parameters.FLASH_MODE_OFF,
            Camera.Parameters.FLASH_MODE_AUTO,
            Camera.Parameters.FLASH_MODE_RED_EYE,
            Camera.Parameters.FLASH_MODE_TORCH
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface FlashMode {}

    private Context context;

    private final Object cameraLock = new Object();

    // Guarded by cameraLock
    private Camera camera;

    private int mFacing = CAMERA_FACING_BACK;

    /**
     * Вращение устройства
     */
    private int rotation;

    private Size previewSize;

    private float requestedFps = 30.0f;
    private int requestedPreviewWidth = 1024;
    private int requestedPreviewHeight = 768;


    private String focusMode = null;
    private String flashMode = null;


    private SurfaceView dummySurfaceView;
    private SurfaceTexture dummySurfaceTexture;


    private Thread processingThread;
    private FrameProcessingRunnable frameProcessor;
    private Map<byte[], ByteBuffer> bytesToByteBuffer = new HashMap<>();

    // Builder

    /**
     * для настройки и создания соответствующего источника камеры
     */
    public static class Builder {
        private final Detector<?> detector;
        private CameraSource cameraSource = new CameraSource();

        public Builder(Context context, Detector<?> detector) {
            if (context == null) {
                throw new IllegalArgumentException("No context supplied.");
            }
            if (detector == null) {
                throw new IllegalArgumentException("No detector supplied.");
            }

            this.detector = detector;
            cameraSource.context = context;
        }

        public Builder setRequestedFps(float fps) {
            if (fps <= 0) {
                throw new IllegalArgumentException("Invalid fps: " + fps);
            }
            cameraSource.requestedFps = fps;
            return this;
        }

        public Builder setFocusMode(@FocusMode String mode) {
            cameraSource.focusMode = mode;
            return this;
        }

        public Builder setFlashMode(@FlashMode String mode) {
            cameraSource.flashMode = mode;
            return this;
        }

        public Builder setRequestedPreviewSize(int width, int height) {
            final int MAX = 1000000;
            if ((width <= 0) || (width > MAX) || (height <= 0) || (height > MAX)) {
                throw new IllegalArgumentException("Invalid preview size: " + width + "x" + height);
            }
            cameraSource.requestedPreviewWidth = width;
            cameraSource.requestedPreviewHeight = height;
            return this;
        }

        //Устанавливает камеру для использования
        public Builder setFacing(int facing) {
            if ((facing != CAMERA_FACING_BACK) && (facing != CAMERA_FACING_FRONT)) {
                throw new IllegalArgumentException("Invalid camera: " + facing);
            }
            cameraSource.mFacing = facing;
            return this;
        }

        //Создание экземплярa источника камеры
        public CameraSource build() {
            cameraSource.frameProcessor = cameraSource.new FrameProcessingRunnable(detector);
            return cameraSource;
        }
    }

     // Интерфейс обратного вызова  для сигнализации момента фактического захвата изображения

    public interface ShutterCallback {
        void onShutter();
    }

   //предоставления данных изображения из фотоснимка

    public interface PictureCallback {
        void onPictureTaken(byte[] data);
    }

    /**
     * уведомления о завершении автофокусировки камеры
     */
    public interface AutoFocusCallback {
        void onAutoFocus(boolean success);
    }

    public interface AutoFocusMoveCallback {

        void onAutoFocusMoving(boolean start);
    }


    // Останавливает камеру и освобождает ресурсы камеры и базового детектора

    public void release() {
        synchronized (cameraLock) {
            stop();
            frameProcessor.release();
        }
    }

   //Открывает камеру и начинает отправлять кадры предварительного просмотра
   // на базовый детектор. Кадры предварительного просмотра не отображаются.
    @RequiresPermission(Manifest.permission.CAMERA)
    public CameraSource start() throws IOException {
        synchronized (cameraLock) {
            if (camera != null) {
                return this;
            }
            camera = createCamera();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                dummySurfaceTexture = new SurfaceTexture(DUMMY_TEXTURE_NAME);
                camera.setPreviewTexture(dummySurfaceTexture);
            } else {
                dummySurfaceView = new SurfaceView(context);
                camera.setPreviewDisplay(dummySurfaceView.getHolder());
            }
            camera.startPreview();

            processingThread = new Thread(frameProcessor);
            frameProcessor.setActive(true);
            processingThread.start();
        }
        return this;
    }

   //Открывает камеру и начинает отправлять кадры предварительного просмотра на базовый детектор.
    @RequiresPermission(Manifest.permission.CAMERA)
    public CameraSource start(SurfaceHolder surfaceHolder) throws IOException {
        synchronized (cameraLock) {
            if (camera != null) {
                return this;
            }

            camera = createCamera();
            camera.setPreviewDisplay(surfaceHolder);
            camera.startPreview();

            processingThread = new Thread(frameProcessor);
            frameProcessor.setActive(true);
            processingThread.start();
        }
        return this;
    }

    //Закрывает камеру и прекращает отправку кадров
    public void stop() {
        synchronized (cameraLock) {
            frameProcessor.setActive(false);
            if (processingThread != null) {
                try {
                    //ждем завершения потока
                    processingThread.join();
                } catch (InterruptedException e) {
                    Log.d(TAG, "Поток обработки кадров прерван.");
                }
                processingThread = null;
            }

            // отчистка буфера
            bytesToByteBuffer.clear();

            if (camera != null) {
                camera.stopPreview();
                camera.setPreviewCallbackWithBuffer(null);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        camera.setPreviewTexture(null);

                    } else {
                        camera.setPreviewDisplay(null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Не удалось очистить предварительный просмотр: " + e);
                }
                camera.release();
                camera = null;
            }
        }
    }


    public Size getPreviewSize() {
        return previewSize;
    }

    public int getCameraFacing() {
        return mFacing;
    }

    public int doZoom(float scale) {
        synchronized (cameraLock) {
            if (camera == null) {
                return 0;
            }
            int currentZoom = 0;
            int maxZoom;
            Camera.Parameters parameters = camera.getParameters();
            if (!parameters.isZoomSupported()) {
                Log.w(TAG, "Увеличение не поддерживается на этом устройстве");
                return currentZoom;
            }
            maxZoom = parameters.getMaxZoom();

            currentZoom = parameters.getZoom() + 1;
            float newZoom;
            if (scale > 1) {
                newZoom = currentZoom + scale * (maxZoom / 10);
            } else {
                newZoom = currentZoom * scale;
            }
            currentZoom = Math.round(newZoom) - 1;
            if (currentZoom < 0) {
                currentZoom = 0;
            } else if (currentZoom > maxZoom) {
                currentZoom = maxZoom;
            }
            parameters.setZoom(currentZoom);
            camera.setParameters(parameters);
            return currentZoom;
        }
    }

    public void takePicture(ShutterCallback shutter, PictureCallback jpeg) {
        synchronized (cameraLock) {
            if (camera != null) {
                PictureStartCallback startCallback = new PictureStartCallback();
                startCallback.mDelegate = shutter;
                PictureDoneCallback doneCallback = new PictureDoneCallback();
                doneCallback.mDelegate = jpeg;
                camera.takePicture(startCallback, null, null, doneCallback);
            }
        }
    }


    @Nullable
    @FocusMode
    public String getFocusMode() {
        return focusMode;
    }

    /**
     * Устанавливается режим фокусировки
     */
    public boolean setFocusMode(@FocusMode String mode) {
        synchronized (cameraLock) {
            if (camera != null && mode != null) {
                Camera.Parameters parameters = camera.getParameters();
                if (parameters.getSupportedFocusModes().contains(mode)) {
                    parameters.setFocusMode(mode);
                    camera.setParameters(parameters);
                    focusMode = mode;
                    return true;
                }
            }

            return false;
        }
    }

    @Nullable
    @FlashMode
    public String getFlashMode() {
        return flashMode;
    }

    public boolean setFlashMode(@FlashMode String mode) {
        synchronized (cameraLock) {
            if (camera != null && mode != null) {
                Camera.Parameters parameters = camera.getParameters();
                if (parameters.getSupportedFlashModes().contains(mode)) {
                    parameters.setFlashMode(mode);
                    camera.setParameters(parameters);
                    flashMode = mode;
                    return true;
                }
            }

            return false;
        }
    }

    public void autoFocus(@Nullable AutoFocusCallback cb) {
        synchronized (cameraLock) {
            if (camera != null) {
                CameraAutoFocusCallback autoFocusCallback = null;
                if (cb != null) {
                    autoFocusCallback = new CameraAutoFocusCallback();
                    autoFocusCallback.mDelegate = cb;
                }
                camera.autoFocus(autoFocusCallback);
            }
        }
    }

    public void cancelAutoFocus() {
        synchronized (cameraLock) {
            if (camera != null) {
                camera.cancelAutoFocus();
            }
        }
    }

 //Устанавливает автофокус камеры
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public boolean setAutoFocusMoveCallback(@Nullable AutoFocusMoveCallback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return false;
        }

        synchronized (cameraLock) {
            if (camera != null) {
                CameraAutoFocusMoveCallback autoFocusMoveCallback = null;
                if (cb != null) {
                    autoFocusMoveCallback = new CameraAutoFocusMoveCallback();
                    autoFocusMoveCallback.mDelegate = cb;
                }
                camera.setAutoFocusMoveCallback(autoFocusMoveCallback);
            }
        }

        return true;
    }


    private CameraSource() {
    }

    private class PictureStartCallback implements Camera.ShutterCallback {
        private ShutterCallback mDelegate;

        @Override
        public void onShutter() {
            if (mDelegate != null) {
                mDelegate.onShutter();
            }
        }
    }

    private class PictureDoneCallback implements Camera.PictureCallback {
        private PictureCallback mDelegate;

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            if (mDelegate != null) {
                mDelegate.onPictureTaken(data);
            }
            synchronized (cameraLock) {
                if (CameraSource.this.camera != null) {
                    CameraSource.this.camera.startPreview();
                }
            }
        }
    }

    private class CameraAutoFocusCallback implements Camera.AutoFocusCallback {
        private AutoFocusCallback mDelegate;

        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            if (mDelegate != null) {
                mDelegate.onAutoFocus(success);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private class CameraAutoFocusMoveCallback implements Camera.AutoFocusMoveCallback {
        private AutoFocusMoveCallback mDelegate;

        @Override
        public void onAutoFocusMoving(boolean start, Camera camera) {
            if (mDelegate != null) {
                mDelegate.onAutoFocusMoving(start);
            }
        }
    }

 //пользовательские настройки
    @SuppressLint("InlinedApi")
    private Camera createCamera() {
        int requestedCameraId = getIdForRequestedCamera(mFacing);
        if (requestedCameraId == -1) {
            throw new RuntimeException("Не удалось найти запрошенную камеру.");
        }
        Camera camera = Camera.open(requestedCameraId);

        SizePair sizePair = selectSizePair(camera, requestedPreviewWidth, requestedPreviewHeight);
        if (sizePair == null) {
            throw new RuntimeException("Не удалось найти подходящий размер предварительного просмотра.");
        }
        Size pictureSize = sizePair.pictureSize();
        previewSize = sizePair.previewSize();

        int[] previewFpsRange = selectPreviewFpsRange(camera, requestedFps);
        if (previewFpsRange == null) {
            throw new RuntimeException("Не удалось найти подходящие кадры предварительного просмотра в секунду.");
        }

        Camera.Parameters parameters = camera.getParameters();

        if (pictureSize != null) {
            parameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
        }

        parameters.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
        parameters.setPreviewFpsRange(
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
        parameters.setPreviewFormat(ImageFormat.NV21);

        setRotation(camera, parameters, requestedCameraId);

        if (focusMode != null) {
            if (parameters.getSupportedFocusModes().contains(
                    focusMode)) {
                parameters.setFocusMode(focusMode);
            } else {
                Log.i(TAG, "Camera focus mode: " + focusMode +
                        " не поддерживается на этом устройстве.");
            }
        }

        focusMode = parameters.getFocusMode();

        if (flashMode != null) {
            if (parameters.getSupportedFlashModes().contains(
                    flashMode)) {
                parameters.setFlashMode(flashMode);
            } else {
                Log.i(TAG, "Camera flash mode: " + flashMode +
                        " не поддерживается на этом устройстве..");
            }
        }

        flashMode = parameters.getFlashMode();

        camera.setParameters(parameters);

        // Для работы необходимы четыре кадра буфера:
        // один для кадра, который в данный момент выполняется при обнаружении
        //один для следующего ожидающего кадра
        //два для кадров, которые камера использует для заполнения будущих изображений предварительного просмотра
        camera.setPreviewCallbackWithBuffer(new CameraPreviewCallback());
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));
        camera.addCallbackBuffer(createPreviewBuffer(previewSize));

        return camera;
    }

    private static int getIdForRequestedCamera(int facing) {
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); ++i) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == facing) {
                return i;
            }
        }
        return -1;
    }
    private static SizePair selectSizePair(Camera camera, int desiredWidth, int desiredHeight) {
        List<SizePair> validPreviewSizes = generateValidPreviewSizeList(camera);
        SizePair selectedPair = null;
        int minDiff = Integer.MAX_VALUE;
        for (SizePair sizePair : validPreviewSizes) {
            Size size = sizePair.previewSize();
            int diff = Math.abs(size.getWidth() - desiredWidth) +
                    Math.abs(size.getHeight() - desiredHeight);
            if (diff < minDiff) {
                selectedPair = sizePair;
                minDiff = diff;
            }
        }

        return selectedPair;
    }

    private static class SizePair {
        private Size mPreview;
        private Size mPicture;

        public SizePair(android.hardware.Camera.Size previewSize,
                        android.hardware.Camera.Size pictureSize) {
            mPreview = new Size(previewSize.width, previewSize.height);
            if (pictureSize != null) {
                mPicture = new Size(pictureSize.width, pictureSize.height);
            }
        }

        public Size previewSize() {
            return mPreview;
        }

        @SuppressWarnings("unused")
        public Size pictureSize() {
            return mPicture;
        }
    }

    private static List<SizePair> generateValidPreviewSizeList(Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        List<android.hardware.Camera.Size> supportedPreviewSizes =
                parameters.getSupportedPreviewSizes();
        List<android.hardware.Camera.Size> supportedPictureSizes =
                parameters.getSupportedPictureSizes();
        List<SizePair> validPreviewSizes = new ArrayList<>();
        for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
            float previewAspectRatio = (float) previewSize.width / (float) previewSize.height;

            for (android.hardware.Camera.Size pictureSize : supportedPictureSizes) {
                float pictureAspectRatio = (float) pictureSize.width / (float) pictureSize.height;
                if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
                    validPreviewSizes.add(new SizePair(previewSize, pictureSize));
                    break;
                }
            }
        }

        if (validPreviewSizes.size() == 0) {
            Log.w(TAG, "Размеры предварительного просмотра не имеют соответствующего размера изображения с  соотношением сторон");
            for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
                validPreviewSizes.add(new SizePair(previewSize, null));
            }
        }

        return validPreviewSizes;
    }

 //наиболее подходящие кадры предварительного просмотра в секунду
    private int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {

        int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);
        int[] selectedFpsRange = null;
        int minDiff = Integer.MAX_VALUE;
        List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
        for (int[] range : previewFpsRangeList) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }

    private void setRotation(Camera camera, Camera.Parameters parameters, int cameraId) {
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int degrees = 0;
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                Log.e(TAG, "Bad rotation value: " + rotation);
        }

        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);

        int angle;
        int displayAngle;
        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            angle = (cameraInfo.orientation + degrees) % 360;
            displayAngle = (360 - angle);
        } else {
            angle = (cameraInfo.orientation - degrees + 360) % 360;
            displayAngle = angle;
        }

        this.rotation = angle / 90;

        camera.setDisplayOrientation(displayAngle);
        parameters.setRotation(angle);
    }

    private byte[] createPreviewBuffer(Size previewSize) {
        int bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
        long sizeInBits = previewSize.getHeight() * previewSize.getWidth() * bitsPerPixel;
        int bufferSize = (int) Math.ceil(sizeInBits / 8.0d) + 1;
        byte[] byteArray = new byte[bufferSize];
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        if (!buffer.hasArray() || (buffer.array() != byteArray)) {

            throw new IllegalStateException("Не удалось создать буфер для  камеры.");
        }

        bytesToByteBuffer.put(byteArray, buffer);
        return byteArray;
    }

    //Обработка кадров


     //Вызывается, когда камера имеет новый кадр предварительного просмотра

    private class CameraPreviewCallback implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            frameProcessor.setNextFrame(data, camera);
        }
    }

    private class FrameProcessingRunnable implements Runnable {
        private Detector<?> mDetector;
        private long mStartTimeMillis = SystemClock.elapsedRealtime();


        private final Object mLock = new Object();
        private boolean mActive = true;
        private long mPendingTimeMillis;
        private int mPendingFrameId = 0;
        private ByteBuffer mPendingFrameData;

        FrameProcessingRunnable(Detector<?> detector) {
            mDetector = detector;
        }

        @SuppressLint("утведить")
        void release() {
            assert (processingThread.getState() == State.TERMINATED);
            mDetector.release();
            mDetector = null;
        }

        void setActive(boolean active) {
            synchronized (mLock) {
                mActive = active;
                mLock.notifyAll();
            }
        }
        void setNextFrame(byte[] data, Camera camera) {
            synchronized (mLock) {
                if (mPendingFrameData != null) {
                    camera.addCallbackBuffer(mPendingFrameData.array());
                    mPendingFrameData = null;
                }

                if (!bytesToByteBuffer.containsKey(data)) {
                    Log.d(TAG,
                            "Skipping frame.  Could not find ByteBuffer associated with the image " +
                                    "data from the camera.");
                    return;
                }


                mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
                mPendingFrameId++;
                mPendingFrameData = bytesToByteBuffer.get(data);

                // Уведомление процессорного потока,если он ожидает следующего кадра
                mLock.notifyAll();
            }
        }

        @Override
        public void run() {
            Frame outputFrame;
            ByteBuffer data;

            while (true) {
                synchronized (mLock) {
                    while (mActive && (mPendingFrameData == null)) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Цикл обработки кадра завершен.", e);
                            return;
                        }
                    }

                    if (!mActive) {
                        return;
                    }

                    outputFrame = new Frame.Builder()
                            .setImageData(mPendingFrameData, previewSize.getWidth(),
                                    previewSize.getHeight(), ImageFormat.NV21)
                            .setId(mPendingFrameId)
                            .setTimestampMillis(mPendingTimeMillis)
                            .setRotation(rotation)
                            .build();

                    data = mPendingFrameData;
                    mPendingFrameData = null;
                }
                try {
                    mDetector.receiveFrame(outputFrame);
                } catch (Throwable t) {
                    Log.e(TAG, "лалалалала.", t);
                } finally {
                    camera.addCallbackBuffer(data.array());
                }
            }
        }
    }
}

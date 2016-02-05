package com.google.vrtoolkit.cardboard.samples.treasurehunt;

/**
 * Created by Amr on 4/15/15.
 */

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Queue;

/*
 * The intent of this class is to package away all the
 * nuisances of camera management on Android. We really only
 * need basic camera functionality, so this class rolls down
 * the essential pipeline.
 */
public class CameraPipeline {

    private int maxImages;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private ImageReader reader;
    private MainActivity activity;
    private Surface camSurface;

    private CameraManager manager;
    private String mCameraId;
    private CameraDevice mDevice;
    private CameraCaptureSession mSession;
    private CaptureRequest.Builder mBuilder;
    private CaptureRequest mRequest;

    private File file;
    private File file2;

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;

    private int mState = STATE_PREVIEW;

    private Bitmap template;
    private static final float sizey = 0.3f;
    private static final float sizex = 0.15f;
    private SignalDetector.Range currentRange;
    public boolean needUpdateRight = false;
    public boolean needUpdateLeft = false;
    public int currentX;
    public int currentY;
    public int originalX;
    public int originalY;


    public CameraPipeline(CameraManager manager, int maxImages, MainActivity activity, Surface surface){
        this.manager   = manager;
        this.maxImages = maxImages;
        this.activity  = activity;
        this.camSurface = surface;
        file = new File(activity.getExternalFilesDir(null),"pic.jpg");
        file2 = new File(activity.getExternalFilesDir(null),"pic2.jpg");
    }


    private CameraDevice.StateCallback mCamDevStateCb = new CameraDevice.StateCallback(){

        @Override
        public void onOpened(CameraDevice camera) {

            mDevice = camera;
            try {
                mBuilder = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                //mBuilder.addTarget(reader.getSurface());
                mBuilder.addTarget(camSurface);
                mDevice.createCaptureSession(Arrays.asList(reader.getSurface(), camSurface),mCamCapSeshStateCb,null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {

            mDevice.close();
            mDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {

            mDevice.close();
            mDevice = null;
        }
    };

    private CameraCaptureSession.StateCallback mCamCapSeshStateCb = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            if(mDevice == null)
                return;

            mSession = session;
            mBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            //mBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            mRequest = mBuilder.build();
            try {
                mSession.setRepeatingRequest(mRequest,mCamCapSeshCapCb,mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            // show Toast. Needs activity instance
        }
    };

    private CameraCaptureSession.CaptureCallback mCamCapSeshCapCb
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    int afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_WAITING_NON_PRECAPTURE;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            process(result);
        }

    };


    private ImageReader.OnImageAvailableListener imReaderListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
                    mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage()));
        }
    };

    private ImageReader.OnImageAvailableListener procReaderListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            int[] newPos = SignalDetector.process(template, reader.acquireNextImage(),currentRange,currentX,currentY);
            currentY = newPos[0];
            currentX = newPos[1];
            Log.e("Tracked Finger", "diffX: " + (originalX-currentX) + " , diffY: " + (originalY-currentY));
        }
    };

    private void startBackgroundThread(){
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void setupImageReader() throws CameraAccessException {
        CameraCharacteristics stats = manager.getCameraCharacteristics(mCameraId);
        StreamConfigurationMap map = stats.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size smallSize = new Size(640,480);
        Size[] sizesOfCameraImages = map.getOutputSizes(ImageFormat.YUV_420_888);
        int smallRes = 480*640;
        for(int i = 0; i < sizesOfCameraImages.length-1 && smallSize.getHeight()*smallSize.getWidth() > smallRes; i++)
                smallSize = sizesOfCameraImages[i];
        reader = ImageReader.newInstance(smallSize.getWidth(), smallSize.getHeight(),ImageFormat.YUV_420_888, maxImages);
        reader.setOnImageAvailableListener(imReaderListener, mBackgroundHandler);
    }

    private void findFrontCamera() {
        try {

            for (String cameraId : manager.getCameraIdList())
            {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK)
                    mCameraId = cameraId;

            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void takePicture() {
        Log.e("CameraPipeline", "takePicture");
        unlockFocus();
    }


    private void lockFocus() {
        try {
            Log.e("CameraPipeline", "lockFocus");
            // This is how to tell the camera to lock focus.
            mBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mSession.setRepeatingRequest(mBuilder.build(), mCamCapSeshCapCb,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void runPrecaptureSequence() {
        try {
            Log.e("CameraPipeline", "preCapture");
            // This is how to tell the camera to trigger.
            mBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mSession.capture(mBuilder.build(), mCamCapSeshCapCb,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void captureStillPicture() {
        try {
            if (null == activity || null == mDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            //captureBuilder.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH);


            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    //showToast("Saved: " + mFile);
                    Log.e("CameraPipeline", "saved image");
                    unlockFocus();
                }
            };
            Log.e("CameraPipeline", "taking image");
            mSession.stopRepeating();
            mSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * Unlock the focus. This method should be called when still image capture sequence is finished.
     */
    private void unlockFocus() {
        try {
            // Reset the autofocus trigger
            mBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mSession.capture(mBuilder.build(), mCamCapSeshCapCb,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mBuilder.addTarget(reader.getSurface());
            mBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            mRequest = mBuilder.build();
            mSession.setRepeatingRequest(mRequest, mCamCapSeshCapCb,
                    mBackgroundHandler);
            activity.pictureTaken = true;
            Log.e("CameraPipeline","unlockFocus");
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private class ImageSaver implements Runnable {


        private final Image mImage;

        public ImageSaver(Image image){
            mImage = image;
        }

        @Override
        public void run() {
            int height = mImage.getHeight();
            int width = mImage.getWidth();
            template = SignalDetector.image2Bitmap(mImage,sizey,sizex);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(file);
                template.compress(Bitmap.CompressFormat.JPEG, 100, output);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            SignalDetector detector = new SignalDetector();
            SignalDetector.Range range = detector.findRange(template, file2);
            Log.e("Returned from find Range","!!!!");
            currentRange = range;
            currentX = width/2;
            currentY = height/2;
            originalX = width/2;
            originalY = height/2;
            reader.setOnImageAvailableListener(procReaderListener, mBackgroundHandler);

        }
    }


    public void onPause(){
        try {
            mSession.stopRepeating();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        mSession.close();
        mDevice.close();
        reader.close();
        stopBackgroundThread();
    }

    public void onResume(){
        this.findFrontCamera();
        this.startBackgroundThread();
        try {
            this.setupImageReader();
            manager.openCamera(mCameraId,mCamDevStateCb,mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

   }
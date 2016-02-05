package com.google.vrtoolkit.cardboard.samples.treasurehunt;

/*
 * Created by Amr 4/15/15
 */

import com.google.vrtoolkit.cardboard.CardboardActivity;
import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.Eye;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraManager;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Stack;

import javax.microedition.khronos.egl.EGLConfig;

/**
 * Simple OpenGL ES text renderer with input through camera video processing
 */
public class MainActivity extends CardboardActivity implements CardboardView.StereoRenderer {

  private static final String TAG = "MainActivity";

  private static final int FLOAT_SIZE_BYTES = 4;
  private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100.0f;
  private static final int CHARS_PER_LINE = 50;
  private static final int NUMBER_OF_LINES = 25;
  private static final float CAMERA_Z = 0.01f;
  private static final float TEXT_SIZE = 2.0f;
  private static float TEXT_DISTANCE = 60.0f;
  private static final float LINE_SPACING = 3;
  private static final float ASP_RATIO = 1.4f;
  private static final float SCREEN_SIZE = 20.0f;
  private static final float SCREEN_DISTANCE = 40.0f;

  private int textProgram;
  private int textPositionParam;
  private int textUVParam;
  private int MVPid;
  private int uniformSampler;
  private int camProgram;
  private int dotProgram;
  private int maPositionHandle;
  private int maTextureHandle;
  private int muMVPMatrixHandle;
  private int muSTMatrixHandle;
  private int mTextureID;
  private int mDotMVPHandle;
  private int mDotPositionHandle;

  private FloatBuffer mTriangleVertices;
  private FloatBuffer mTriangleUVs;
  private FloatBuffer textVertices;
  private FloatBuffer textUVs;
  private FloatBuffer mDotVertices;

  private float[] modelText;
  private int[]   texId;
  private float[] camera;
  private float[] view;
  private float[] headView;
  private float[] modelViewProjection;
  private float[] modelView;
  private float[] mSTMatrix;

  private String theText;

  private final float coord1 = SCREEN_SIZE*ASP_RATIO;
  private final float coord2 = SCREEN_SIZE;
  private final float coord3 = 0.15f*coord1;
  private final float coord4 = 0.3f*coord2;

  private final float[] mTriangleVerticesData = {
            // X, Y, Z, U, V
            -coord1, coord2, -SCREEN_DISTANCE,
           -coord1, -coord2, -SCREEN_DISTANCE,
          coord1,  coord2, -SCREEN_DISTANCE,

          coord1, -coord2, -SCREEN_DISTANCE,
          coord1, coord2, -SCREEN_DISTANCE,
            -coord1, -coord2, -SCREEN_DISTANCE
  };
  private final float[] mDotVerticesData = {
            // X, Y, Z, U, V
            -coord3, coord4, -SCREEN_DISTANCE+5,
            -coord3, -coord4, -SCREEN_DISTANCE+5,
            coord3,  coord4, -SCREEN_DISTANCE+5,

            coord3, -coord4, -SCREEN_DISTANCE+5,
            coord3, coord4, -SCREEN_DISTANCE+5,
            -coord3, -coord4, -SCREEN_DISTANCE+5
  };
  private final float[] mTriangleUVData = {
          0.0f, 0.0f,
          0.0f, 1.0f,
          1.0f, 0.0f,

          1.0f, 1.0f,
          1.0f, 0.0f,
          0.0f, 1.0f
  };

  private char[] theTextChars;
  private ArrayList<String> page;
  private boolean cameraActive = false;
  private int START = 0;
  private int END;
  private Stack<Integer> numchars;

  private Vibrator vibrator;
  private CardboardView cbView;
  private CameraPipeline cam;

  private SurfaceTexture surfaceTexture;
  private Surface surface;
  private boolean updateSurface = false;
  public boolean pictureTaken = false;
  private CardboardOverlayView overlayView;

    /**
   * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
   *
   * @param type The type of shader we will be creating.
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The shader object handler.
   */
  private int loadGLShader(int type, int resId) {
    String code = readRawTextFile(resId);
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, code);
    GLES20.glCompileShader(shader);

    // Get the compilation status.
    final int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

    // If the compilation failed, delete the shader.
    if (compileStatus[0] == 0) {
      Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
      GLES20.glDeleteShader(shader);
      shader = 0;
    }

    if (shader == 0) {
      throw new RuntimeException("Error creating shader.");
    }

    return shader;
  }

  /**
   * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
   *
   * @param label Label to report in case of error.
   */
  private static void checkGLError(String label) {
    int error;
    while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      Log.e(TAG, label + ": glError " + error);
      throw new RuntimeException(label + ": glError " + error);
    }
  }

  /**
   * Sets the view to our CardboardView and initializes the transformation matrices we will use
   * to render our scene.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.common_ui);
    cbView = (CardboardView) findViewById(R.id.cardboard_view);
    cbView.setRenderer(this);
    setCardboardView(cbView);

    theText = readRawTextFile(R.raw.carrollthroughl01);
    modelText = new float[16];
    camera = new float[16];
    view = new float[16];
    modelViewProjection = new float[16];
    modelView = new float[16];
    headView = new float[16];
    mSTMatrix = new float[16];
    page = new ArrayList<String>();
    numchars = new Stack<Integer>();
    vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    theTextChars = theText.toCharArray();
    Matrix.setIdentityM(mSTMatrix, 0);

    overlayView = (CardboardOverlayView) findViewById(R.id.overlay);

    //Bitmap test = BitmapFactory.decodeResource(this.getResources(), R.drawable.test);
    //overlayView.setImageView(test);
  }

    private SurfaceTexture.OnFrameAvailableListener frameAvailableListener = new SurfaceTexture.OnFrameAvailableListener() {
        @Override
        synchronized public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                updateSurface = true;
        }
    };

  @Override
  public void onPause(){
      if(cameraActive && cam != null) {
          cam.onPause();
          cameraActive = false;
      }
      super.onPause();
  }


  @Override
  public void onRendererShutdown () {
    Log.i(TAG, "onRendererShutdown");
    if(cameraActive && cam != null) {
        cam.onPause();
        cameraActive = false;
    }
  }

  @Override
  public void onSurfaceChanged(int width, int height) {
    Log.i(TAG, "onSurfaceChanged");
  }

  /**
   * Creates the buffers we use to store information about the 3D world.
   *
   * <p>OpenGL doesn't use Java arrays, but rather needs data in a format it can understand.
   * Hence we use ByteBuffers.
   *
   * @param config The EGL configuration used when creating the surface.
   */
  @Override
  public void onSurfaceCreated(EGLConfig config) {
      Log.i(TAG, "onSurfaceCreated");

      // Dark background so text shows up well.
      GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f);

      // initialize first page of text
      format();

      // generate and bind bitmap font texture
      final BitmapFactory.Options options = new BitmapFactory.Options();
      options.inScaled = false;   // No pre-scaling

      Bitmap img = BitmapFactory.decodeResource(this.getResources(), R.drawable.holstein, options);
      texId = new int[1];
      GLES20.glGenTextures(1,texId,0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texId[0]);
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, img, 0);
      GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);

      int[] textures = new int[1];
      GLES20.glGenTextures(1, textures, 0);
      mTextureID = textures[0];
      GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);
      checkGLError("glBindTexture mTextureID");
      GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
      GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);

      surfaceTexture = new SurfaceTexture(mTextureID);
      surfaceTexture.setDefaultBufferSize(640,480);
      surfaceTexture.setOnFrameAvailableListener(frameAvailableListener);
      surface = new Surface(surfaceTexture);
      Log.e("MainActivity", ""+surface.isValid());
      cam = new CameraPipeline((CameraManager) getSystemService(Context.CAMERA_SERVICE), 2, this, surface);

      synchronized(this) {
          updateSurface = false;
      }

      // generate 3D text vertices. Create FloatBuffer to pass to OpenGL
      float[]  floatArrayTextVertices = genVerts(0, 0, page.get(0));
      ByteBuffer bbTextVertices = ByteBuffer.allocateDirect(floatArrayTextVertices.length*4);
      bbTextVertices.order(ByteOrder.nativeOrder());
      textVertices = bbTextVertices.asFloatBuffer();
      textVertices.put(floatArrayTextVertices);
      textVertices.position(0);

      // generate UV vertices to be passed as attributes to fragment shader
      float[]  floatArrayTextUVs = genUVs(page.get(0));
      ByteBuffer bbTextUVs = ByteBuffer.allocateDirect(floatArrayTextUVs.length*4);
      bbTextUVs.order(ByteOrder.nativeOrder());
      textUVs = bbTextUVs.asFloatBuffer();
      textUVs.put(floatArrayTextUVs);
      textUVs.position(0);

      // generate 3D vertices and UV coordinates to be passed as attributes to shaders
      mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
      mTriangleVertices.put(mTriangleVerticesData).position(0);

      mTriangleUVs = ByteBuffer.allocateDirect(mTriangleUVData.length * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
      mTriangleUVs.put(mTriangleUVData).position(0);

      // generate dot vertices
      mDotVertices = ByteBuffer.allocateDirect(mDotVerticesData.length * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
      mDotVertices.put(mDotVerticesData).position(0);

      // Compiling my custom shaders through the loadGLShader function defined above
      int myVertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.vertex);
      int myFragmentShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.text_fragment);

      int myCamVertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.cam_vertex);
      int myCamFragmentShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.cam_fragment);

      int myDotVertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.dot_vertex);
      int myDotFragmentShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.dot_fragment);

      // I am making my own shaders. I first compiled them; now I am setting them to a GL defined
      // program type by attaching them to "textProgram", and then I link them because they are
      // object files. I glUseProgram so that I can EnableVertexAttribArray
      textProgram = GLES20.glCreateProgram();
      GLES20.glAttachShader(textProgram,myVertexShader);
      GLES20.glAttachShader(textProgram,myFragmentShader);
      GLES20.glLinkProgram(textProgram);
      GLES20.glUseProgram(textProgram);

      // check if anything went wrong with the attaching and linking
      checkGLError("Text Program");

      // need java integer id's to shader attributes so that I can assign values to them
      textPositionParam = GLES20.glGetAttribLocation(textProgram, "a_vertex");
      textUVParam = GLES20.glGetAttribLocation(textProgram, "a_UV");
      MVPid = GLES20.glGetUniformLocation(textProgram, "MVP");
      uniformSampler = GLES20.glGetUniformLocation(textProgram, "myTextureSampler");

      // check if anything went wrong with enabling the shader parameters
      checkGLError("Text Program params");

      GLES20.glEnable(GLES20.GL_DEPTH_TEST);

      // create program for camera surface
      camProgram = GLES20.glCreateProgram();
      GLES20.glAttachShader(camProgram,myCamVertexShader);
      GLES20.glAttachShader(camProgram,myCamFragmentShader);
      GLES20.glLinkProgram(camProgram);
      GLES20.glUseProgram(camProgram);

      if (camProgram == 0) {
          return;
      }
      maPositionHandle = GLES20.glGetAttribLocation(camProgram, "aPosition");
      checkGLError("glGetAttribLocation aPosition");
      if (maPositionHandle == -1) {
          throw new RuntimeException("Could not get attrib location for aPosition");
      }
      maTextureHandle = GLES20.glGetAttribLocation(camProgram, "aTextureCoord");
      checkGLError("glGetAttribLocation aTextureCoord");
      if (maTextureHandle == -1) {
          throw new RuntimeException("Could not get attrib location for aTextureCoord");
      }

      muMVPMatrixHandle = GLES20.glGetUniformLocation(camProgram, "uMVPMatrix");
      checkGLError("glGetUniformLocation uMVPMatrix");
      if (muMVPMatrixHandle == -1) {
          throw new RuntimeException("Could not get attrib location for uMVPMatrix");
      }

      muSTMatrixHandle = GLES20.glGetUniformLocation(camProgram, "uSTMatrix");
      checkGLError("glGetUniformLocation uSTMatrix");
      if (muSTMatrixHandle == -1) {
          throw new RuntimeException("Could not get attrib location for uSTMatrix");
      }

      dotProgram = GLES20.glCreateProgram();
      GLES20.glAttachShader(dotProgram,myDotVertexShader);
      GLES20.glAttachShader(dotProgram,myDotFragmentShader);
      GLES20.glLinkProgram(dotProgram);
      GLES20.glUseProgram(dotProgram);

      mDotMVPHandle = GLES20.glGetUniformLocation(dotProgram, "uMVPMatrix");
      mDotPositionHandle = GLES20.glGetAttribLocation(dotProgram, "aPosition");
      //MAKE SURE TO INCLUDE 1 MORE UNIFORM FOR MOTION
      checkGLError("onSurfaceCreated");
  }

  /**
   * Converts a raw text file into a string.
   *
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The context of the text file, or null in case of error.
   */
  private String readRawTextFile(int resId) {
    InputStream inputStream = getResources().openRawResource(resId);
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      reader.close();
      return sb.toString();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Prepares OpenGL ES before we draw a frame.
   *
   * @param headTransform The head transformation in the new frame.
   */
  @Override
  public void onNewFrame(HeadTransform headTransform) {

    synchronized(this) {
          if (updateSurface) {
              surfaceTexture.updateTexImage();
              //surfaceTexture.getTransformMatrix(mSTMatrix);
              updateSurface = false;
          }
    }

    if(cameraActive && pictureTaken && cam.originalX-cam.currentX < -150) {
        changeTextRight();
        cam.onPause();
        pictureTaken = false;
        cameraActive = false;
        cam.currentX = cam.originalX;
    }
    else if(cameraActive && pictureTaken && cam.originalX-cam.currentX > 150) {
        changeTextLeft();
        cam.onPause();
        pictureTaken = false;
        cameraActive = false;
        cam.currentX = cam.originalX;
    }
    else if(cameraActive && pictureTaken && cam.originalY-cam.currentY < -140) {
          TEXT_DISTANCE += 10;
          cam.onPause();
          pictureTaken = false;
          cameraActive = false;
          cam.currentY = cam.originalY;
      }
      else if(cameraActive && pictureTaken && cam.originalY-cam.currentY > 140) {
        TEXT_DISTANCE -= 10;
        changeTextLeft();
        cam.onPause();
        pictureTaken = false;
        cameraActive = false;
        cam.currentY = cam.originalY;
    }
    // Build the camera matrix and apply it to the ModelView.
    Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

    headTransform.getHeadView(headView, 0);

    checkGLError("onReadyToDraw");
  }

  /**
   * Draws a frame for an eye.
   *
   * @param eye The eye to render. Includes all required transformations.
   */
  @Override
  public void onDrawEye(Eye eye) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    checkGLError("colorParam");

    // Apply the eye transformation to the camera.
    Matrix.multiplyMM(view, 0, eye.getEyeView(), 0, camera, 0);

    // Build the ModelView and ModelViewProjection matrices
    // for calculating text position.
    float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);


    for(int lineNumber = 0; lineNumber < page.size(); lineNumber++)
    {
        drawText(page.get(lineNumber),perspective, lineNumber);
    }

    if(cameraActive) {
        drawStream(perspective);
        int diffX;
        int diffY;
        if(pictureTaken){
            diffX = cam.originalX-cam.currentX;
            diffY = cam.originalY-cam.currentY;

            diffX = -(int) (SCREEN_SIZE*ASP_RATIO*diffX/(cam.originalX));
            diffY = (int) (SCREEN_SIZE*diffY/(cam.originalY));
        }
        else{
            diffX = 0;
            diffY = 0;
        }
        drawDot(perspective, diffX, diffY);
    }

  }

  @Override
  public void onFinishFrame(Viewport viewport) {
  }

   public void drawDot(float[] P, int diffX, int diffY){
       GLES20.glUseProgram(dotProgram);
       GLES20.glEnableVertexAttribArray(mDotPositionHandle);

       GLES20.glVertexAttribPointer(mDotPositionHandle, 3, GLES20.GL_FLOAT, false,0, mDotVertices);
       Matrix.setIdentityM(modelText,0);
       Matrix.translateM(modelText, 0, diffX, diffY, 0);
       Matrix.multiplyMM(modelViewProjection, 0, P, 0, modelText, 0);
       GLES20.glUniformMatrix4fv(mDotMVPHandle, 1, false, modelViewProjection, 0);

       // MAKE SURE TO INCLUDE 1 MORE UNIFORM FOR MOTION

       GLES20.glEnable(GLES20.GL_BLEND);
       GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_DST_ALPHA);
       GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);
       checkGLError("Draw dot");

   }
   public void drawStream(float[] P){
       GLES20.glUseProgram(camProgram);
       checkGLError("glUseProgram");

       GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
       GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);

       GLES20.glEnableVertexAttribArray(maTextureHandle);
       GLES20.glEnableVertexAttribArray(maPositionHandle);

       GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,0, mTriangleVertices);

       GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,0, mTriangleUVs);


       Matrix.setIdentityM(modelView,0);
       //Matrix.multiplyMM(modelView,0,view,0,modelText,0);
       Matrix.multiplyMM(modelViewProjection, 0, P, 0, modelView, 0);
       GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, modelViewProjection, 0);
       GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

       GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
       checkGLError("glDrawArrays");
   }

   public void drawText(String line, float[] P, int lineNumber) {
      GLES20.glUseProgram(textProgram);

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0]);
      GLES20.glUniform1i(uniformSampler, 0);

      // GLSL needs the attributes to be enabled before they can be assigned
      GLES20.glEnableVertexAttribArray(textPositionParam);
      GLES20.glEnableVertexAttribArray(textUVParam);

      float horizontalLineShift = resetBuffers(line);

      // Final output is gl_Position = Perspective*View*Model*(a_vertex)
      Matrix.setIdentityM(modelText,0);
      Matrix.translateM(modelText,0,horizontalLineShift,(page.size()/2 - lineNumber)*LINE_SPACING,0);
      Matrix.multiplyMM(modelView,0,view,0,modelText,0);
      Matrix.multiplyMM(modelViewProjection, 0, P, 0, modelView, 0);

      GLES20.glUniformMatrix4fv(MVPid, 1, false, modelViewProjection, 0);

      GLES20.glVertexAttribPointer(textPositionParam,3,GLES20.GL_FLOAT,false,0,textVertices);

      GLES20.glVertexAttribPointer(textUVParam,2,GLES20.GL_FLOAT,false,0,textUVs);

      GLES20.glEnable(GLES20.GL_BLEND);
      GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_SRC_ALPHA);

      GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,line.length()*2*3);

      checkGLError("Drawing text");
   }


  /**
   * Called when the Cardboard trigger is pulled.
   */
  @Override
  public void onCardboardTrigger() {
    Log.i(TAG, "onCardboardTrigger");
    cbView.resetHeadTracker();
    if(cameraActive && cam != null)
    {
        if(pictureTaken) {
            cam.onPause();
            cameraActive = false;
            pictureTaken = false;
        }
        else{
            cam.takePicture();
            pictureTaken = true;
        }
    }
    else if(!cameraActive && cam != null)
    {
        cameraActive = true;
        pictureTaken = false;
        cam.onResume();
    }
    // Always give user feedback.
    vibrator.vibrate(50);
  }

  @Override
  public boolean onTouchEvent(MotionEvent motionevent){
      if(motionevent.getAction() == MotionEvent.ACTION_DOWN && motionevent.getX() < 0.9*cbView.getWidth()) {

          cbView.resetHeadTracker();
          if(cameraActive && cam != null)
          {
              if(pictureTaken) {
                  cam.onPause();
                  cameraActive = false;
                  pictureTaken = false;
              }
              else{
                  cam.takePicture();
                  pictureTaken = true;
              }
          }
          else if(!cameraActive && cam != null)
          {
              cameraActive = true;
              pictureTaken = false;
              cam.onResume();
          }
          // Always give user feedback.
          vibrator.vibrate(50);
      }
      return true;
  }


  private float[] genVerts(float x, float y, String line){

        float z = -TEXT_DISTANCE;
        float size = TEXT_SIZE;
        int length = line.length();

        float[] vertices = new float[length*6*3];

        for ( int i=0 ; i<length ; i++ ){

            vertices[i*18 + 0*3 + 0] = x+i*size;
            vertices[i*18 + 0*3 + 1] = y+size;
            vertices[i*18 + 0*3 + 2] = z;

            vertices[i*18 + 1*3 + 0] = x+i*size;
            vertices[i*18 + 1*3 + 1] = y;
            vertices[i*18 + 1*3 + 2] = z;

            vertices[i*18 + 2*3 + 0] = x+i*size+size;
            vertices[i*18 + 2*3 + 1] = y+size;
            vertices[i*18 + 2*3 + 2] = z;


            vertices[i*18 + 3*3 + 0] = x+i*size+size;
            vertices[i*18 + 3*3 + 1] = y;
            vertices[i*18 + 3*3 + 2] = z;

            vertices[i*18 + 4*3 + 0] = x+i*size+size;
            vertices[i*18 + 4*3 + 1] = y+size;
            vertices[i*18 + 4*3 + 2] = z;

            vertices[i*18 + 5*3 + 0] = x+i*size;
            vertices[i*18 + 5*3 + 1] = y;
            vertices[i*18 + 5*3 + 2] = z;

        }

        return vertices;
    }

  private float[] genUVs(String line){

        int length = line.length();

        float[] UVs = new float[length*6*2];

        for ( int i=0 ; i<length ; i++ ) {

            char character = line.charAt(i);

            float uv_x = (character % 16) / 16.0f;
            float uv_y = (character / 16) / 16.0f;

            UVs[i * 12 + 0] = uv_x;
            UVs[i * 12 + 1] = uv_y;

            UVs[i * 12 + 2] = uv_x;
            UVs[i * 12 + 3] = uv_y + 1.0f / 16.0f;

            UVs[i * 12 + 4] = uv_x + 1.0f / 16.0f;
            UVs[i * 12 + 5] = uv_y;


            UVs[i * 12 + 6] = uv_x + 1.0f / 16.0f;
            UVs[i * 12 + 7] = uv_y + 1.0f / 16.0f;

            UVs[i * 12 + 8] = uv_x + 1.0f / 16.0f;
            UVs[i * 12 + 9] = uv_y;

            UVs[i * 12 + 10] = uv_x;
            UVs[i * 12 + 11] = uv_y + 1.0f / 16.0f;
        }

        return UVs;
    }

  private void format(){
      page.clear();
      END = START + CHARS_PER_LINE * NUMBER_OF_LINES;
      if(END >= theText.length())
          END = theText.length();
      else {
          while (theTextChars[END] != ' ' && theTextChars[END] != '\n' && END < theTextChars.length)
              END++;
      }
      int start = START;
      int end = start + CHARS_PER_LINE - 1;
      boolean breakOut = false;
      for (int i = 0; i < NUMBER_OF_LINES && !breakOut; i++) {
          while (end < theTextChars.length-1 && theTextChars[end] != ' ' && theTextChars[end] != '\n')
              end++;
          String line = theText.substring(start, end);
          page.add(line);
          start = end + 1;
          end = start + CHARS_PER_LINE - 1;
          if(start >= END)
              breakOut = true;
          if(end >= END)
              end = END-1;
      }
    }

  private int changeText(int numLines, boolean right){
      // this function only moves the text by a line.
      if(END < theText.length()-1) {
          int numChars = 0;
          for(int i = 0; i < numLines; i++)
            numChars +=  page.get(i).length();
          if(START + numChars >= theText.length() && right)
              return 0;
          else if(START - numChars < 0 && !right)
              return 0;
          else
            return numChars;
      }
      else
          return 0;
  }

  public void changeTextLeft(){
      if(!numchars.empty()) {
          START -= numchars.pop().intValue();
          format();
      }
      Log.e("MainActivity", "called left");
  }

  public void changeTextRight(){

        numchars.push(changeText(NUMBER_OF_LINES/2, true));
        START += numchars.peek();
        format();
        Log.e("MainActivity", "called right");
  }

  private float resetBuffers(String line){
        textVertices.clear();
        textUVs.clear();

        float[]  floatArrayTextVertices = genVerts(0, 0, line);
        ByteBuffer bbTextVertices = ByteBuffer.allocateDirect(floatArrayTextVertices.length*4);
        bbTextVertices.order(ByteOrder.nativeOrder());
        textVertices = bbTextVertices.asFloatBuffer();
        textVertices.put(floatArrayTextVertices);
        textVertices.position(0);

        float[]  floatArrayTextUVs = genUVs(line);
        ByteBuffer bbTextUVs = ByteBuffer.allocateDirect(floatArrayTextUVs.length*4);
        bbTextUVs.order(ByteOrder.nativeOrder());
        textUVs = bbTextUVs.asFloatBuffer();
        textUVs.put(floatArrayTextUVs);
        textUVs.position(0);

        return -floatArrayTextVertices[floatArrayTextVertices.length-6]/2;
    }

}

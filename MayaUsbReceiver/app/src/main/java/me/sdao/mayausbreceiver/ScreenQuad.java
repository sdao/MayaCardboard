package me.sdao.mayausbreceiver;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES10;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class ScreenQuad {

    private static final float mVertices[] = {
            -1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
    };

    private static final float mTexCoords[] = {
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f,
    };

    private boolean mReady;
    private Context mContext;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;
    private int mProgram;
    private int mProgramPositionParam;
    private int mProgramTexCoordParam;
    private int mProgramBitmapUniform;
    private int mTextures[] = {-1, -1};

    public ScreenQuad(Context context) {
        ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(mVertices.length * 4); // 32 bits.
        vertexByteBuffer.order(ByteOrder.nativeOrder());
        mVertexBuffer = vertexByteBuffer.asFloatBuffer();
        mVertexBuffer.put(mVertices);
        mVertexBuffer.position(0);

        ByteBuffer texByteBuffer = ByteBuffer.allocateDirect(mTexCoords.length * 4); // 32 bits.
        texByteBuffer.order(ByteOrder.nativeOrder());
        mTexCoordBuffer = texByteBuffer.asFloatBuffer();
        mTexCoordBuffer.put(mTexCoords);
        mTexCoordBuffer.position(0);

        mContext = context;
    }

    public void setup() {
        int passthroughVertex = GLShaderUtils.loadGLShader(mContext, GLES20.GL_VERTEX_SHADER,
                R.raw.quad_vert);
        int passthroughFrag = GLShaderUtils.loadGLShader(mContext, GLES20.GL_FRAGMENT_SHADER,
                R.raw.quad_frag);

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, passthroughVertex);
        GLES20.glAttachShader(mProgram, passthroughFrag);
        GLES20.glLinkProgram(mProgram);
        GLES20.glUseProgram(mProgram);

        mProgramPositionParam = GLES20.glGetAttribLocation(mProgram, "a_Position");
        mProgramTexCoordParam = GLES20.glGetAttribLocation(mProgram, "a_TexCoord");
        mProgramBitmapUniform = GLES20.glGetUniformLocation(mProgram, "u_Bitmap");

        GLES20.glGenTextures(1, mTextures, 0);

        mReady = true;
    }

    public void bindBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[0]);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
    }

    public void draw() {
        if (!mReady) {
            return;
        }

        GLES20.glUseProgram(mProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[0]);
        GLES20.glUniform1i(mProgramBitmapUniform, 0);

        GLES20.glVertexAttribPointer(
                mProgramPositionParam,
                3 /* coordinates per vertex */,
                GLES20.GL_FLOAT,
                false /* normalized */,
                0 /* stride */,
                mVertexBuffer);
        GLES20.glVertexAttribPointer(
                mProgramTexCoordParam,
                2 /* coordinates per vertex */,
                GLES20.GL_FLOAT,
                false /* normalized */,
                0 /* stride */,
                mTexCoordBuffer);

        GLES10.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, mVertices.length / 3);
    }

}

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

    private static final float mLeftData[] = {
            /* x, y, z, s, t */
            -1.0f, -1.0f, 0.0f, 0.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 0.0f, 0.5f, 1.0f,
            1.0f, 1.0f, 0.0f, 0.5f, 0.0f
    };

    private static final float mRightData[] = {
            /* x, y, z, s, t */
            -1.0f, -1.0f, 0.0f, 0.5f, 1.0f,
            -1.0f, 1.0f, 0.0f, 0.5f, 0.0f,
            1.0f, -1.0f, 0.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f, 0.0f
    };

    private static final int DATA_LENGTH = 20;

    private boolean mReady;
    private Context mContext;
    private int mProgram;
    private int mProgramPositionParam;
    private int mProgramTexCoordParam;
    private int mProgramBitmapUniform;
    private int[] mTextures = new int[1];
    private int[] mBuffers = new int[2];

    public ScreenQuad(Context context) {
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
        GLES20.glGenBuffers(2, mBuffers, 0);
        bufferData(true);
        bufferData(false);

        mReady = true;
    }

    public void shutdown() {
        GLES20.glDeleteTextures(1, mTextures, 0);
        GLES20.glDeleteBuffers(2, mBuffers, 0);

        mReady = false;
    }

    private void bufferData(boolean left) {
        ByteBuffer dataByteBuffer = ByteBuffer.allocateDirect(DATA_LENGTH * 4); // 32 bits.
        dataByteBuffer.order(ByteOrder.nativeOrder());
        FloatBuffer dataFloatBuffer = dataByteBuffer.asFloatBuffer();
        dataFloatBuffer.put(left ? mLeftData : mRightData);
        dataFloatBuffer.position(0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, left ? mBuffers[0] : mBuffers[1]);

        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                dataFloatBuffer.capacity() * 4 /* bytes per float */,
                dataFloatBuffer,
                GLES20.GL_STATIC_DRAW);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    public void bindBitmap(Bitmap bitmap) {
        if (bitmap == null || !mReady) {
            return;
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[0]);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
    }

    public void draw(boolean left) {
        if (!mReady) {
            return;
        }

        GLES20.glUseProgram(mProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[0]);
        GLES20.glUniform1i(mProgramBitmapUniform, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, left ? mBuffers[0] : mBuffers[1]);

        GLES20.glEnableVertexAttribArray(mProgramPositionParam);
        GLES20.glVertexAttribPointer(
                mProgramPositionParam,
                3 /* coordinates per vertex */,
                GLES20.GL_FLOAT,
                false /* normalized */,
                5 * 4 /* stride */,
                0 /* offset */);

        GLES20.glEnableVertexAttribArray(mProgramTexCoordParam);
        GLES20.glVertexAttribPointer(
                mProgramTexCoordParam,
                2 /* coordinates per vertex */,
                GLES20.GL_FLOAT,
                false /* normalized */,
                5 * 4 /* stride */,
                3 * 4 /* offset */);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES10.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, DATA_LENGTH / 5);
    }

}

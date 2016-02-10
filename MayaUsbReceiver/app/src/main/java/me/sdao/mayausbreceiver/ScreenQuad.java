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

    private static final float mData[] = {
            /* x, y, z, s, t */
            -1.0f, -1.0f, 0.0f, 0.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 0.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f, 0.0f
    };

    private boolean mReady;
    private Context mContext;
    private int mProgram;
    private int mProgramPositionParam;
    private int mProgramTexCoordParam;
    private int mProgramBitmapUniform;
    private int mTexture;
    private int mBuffer;

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

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTexture = textures[0];

        int[] buffers = new int[1];
        GLES20.glGenBuffers(1, buffers, 0);
        mBuffer = buffers[0];
        bufferData();

        mReady = true;
    }

    public void shutdown() {
        int[] textures = { mTexture };
        GLES20.glDeleteTextures(1, textures, 0);

        int[] buffers = { mBuffer };
        GLES20.glDeleteBuffers(1, buffers, 0);

        mReady = false;
    }

    private void bufferData() {
        ByteBuffer dataByteBuffer = ByteBuffer.allocateDirect(mData.length * 4); // 32 bits.
        dataByteBuffer.order(ByteOrder.nativeOrder());
        FloatBuffer dataFloatBuffer = dataByteBuffer.asFloatBuffer();
        dataFloatBuffer.put(mData);
        dataFloatBuffer.position(0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mBuffer);

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

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexture);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
    }

    public void draw() {
        if (!mReady) {
            return;
        }

        GLES20.glUseProgram(mProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexture);
        GLES20.glUniform1i(mProgramBitmapUniform, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mBuffer);

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

        GLES10.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, mData.length / 5);
    }

}

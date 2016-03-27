package me.sdao.mayausbreceiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.opengl.GLES20;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.Eye;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    final private Object mBitmapLock = new Object();
    private Bitmap mBitmap = null;
    private boolean mBitmapNew = false;

    final private Object mRotationLock = new Object();
    private float[] mRotation = new float[4];

    private AtomicBoolean mCancel = new AtomicBoolean();
    private PendingIntent mPermissionIntent;

    private ParcelFileDescriptor mParcelFileDescriptor;

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (accessory != null) {
                            connectAccessory(accessory);
                        }
                    }
                    else {
                        toast("USB permission denied");
                    }
                }
            }
        }
    };
    private boolean mReceiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CardboardView cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
        cardboardView.setRenderer(new CardboardView.StereoRenderer() {
            ScreenQuad screenQuad = new ScreenQuad(MainActivity.this);

            @Override
            public void onNewFrame(HeadTransform headTransform) {
                if (!mCancel.get()) {
                    synchronized (mRotationLock) {
                        headTransform.getQuaternion(mRotation, 0);
                    }
                }
            }

            @Override
            public void onDrawEye(Eye eye) {
                synchronized (mBitmapLock) {
                    if (mBitmapNew) {
                        screenQuad.bindBitmap(mBitmap);
                        mBitmapNew = false;
                    }
                }

                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                screenQuad.draw(eye.getType() == Eye.Type.LEFT);
            }

            @Override
            public void onFinishFrame(Viewport viewport) {
            }

            @Override
            public void onSurfaceChanged(int width, int height) {
                // TODO: send dimensions to Maya host.
                // Idea: send through same channel as head tracking but use a NaN.
            }

            @Override
            public void onSurfaceCreated(EGLConfig eglConfig) {
                screenQuad.setup();
            }

            @Override
            public void onRendererShutdown() {
                screenQuad.shutdown();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.send_handshake:
                UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);

                UsbAccessory[] accessories = manager.getAccessoryList();
                if (accessories == null || accessories.length == 0) {
                    toast("No accessories connected");
                    return true;
                }

                mPermissionIntent = PendingIntent.getBroadcast(MainActivity.this,
                        0, new Intent(ACTION_USB_PERMISSION), 0);
                registerReceiver(mUsbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
                mReceiverRegistered = true;

                UsbAccessory accessory;
                if (getIntent().hasExtra(UsbManager.EXTRA_ACCESSORY)) {
                    accessory = getIntent().getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                } else {
                    accessory = accessories[0];
                }

                manager.requestPermission(accessory, mPermissionIntent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mReceiverRegistered) {
            unregisterReceiver(mUsbReceiver);
            mReceiverRegistered = false;
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                        | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void toast(String string) {
        Toast.makeText(MainActivity.this, string, Toast.LENGTH_LONG).show();
    }

    private void connectAccessory(UsbAccessory accessory) {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);

        mParcelFileDescriptor = manager.openAccessory(accessory);
        if (mParcelFileDescriptor == null) {
            toast("Error connecting");
            return;
        }

        if (sendHandshake(mParcelFileDescriptor)) {
            toast("Handshake successful!");
        } else {
            toast("Error in handshake");
            try {
                mParcelFileDescriptor.close();
                mParcelFileDescriptor = null;
            } catch (IOException e) {}
            return;
        }

        ThreadCallback callback = new ThreadCallback() {
            @Override
            public void onCompleted(boolean success, Exception e) {
                try {
                    if (mParcelFileDescriptor != null) {
                        mParcelFileDescriptor.close();
                        mParcelFileDescriptor = null;
                    }
                } catch (IOException f) {}

                if (success) {
                    toast("Connection ended successfully");
                } else {
                    toast("Connection ended due to IO error");
                    Log.d("THREADS", "IO error", e);
                }
                showSystemUi();
            }
        };

        hideSystemUi();
        mCancel.set(false);
        runReadThread(mParcelFileDescriptor, callback);
        runWriteThread(mParcelFileDescriptor, callback);
    }

    private boolean sendHandshake(@NonNull ParcelFileDescriptor parcelFileDescriptor) {
        FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();
        try (OutputStream os = new FileOutputStream(fd)) {
            byte[] handshake = new byte[16384];
            for (int i = 0; i < 16384; ++i) {
                handshake[i] = (byte) i;
            }
            os.write(handshake);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void runReadThread(@NonNull ParcelFileDescriptor parcelFileDescriptor,
                               final ThreadCallback callback) {
        final FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();
        new Thread(null, new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[1024 * 1024]; // Initialize 1 MB at first.
                Bitmap backBitmap = null;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inMutable = true;

                try (InputStream is = new FileInputStream(fd)) {
                    DataInputStream dis = new DataInputStream(is);

                    boolean cancelled;
                    while (!(cancelled = mCancel.get())) {
                        int size = dis.readInt();
                        Log.i("SIZE", "size=" + size);

                        if (size < 0 || size > 1024 * 1024 * 4 /* 4 MB */) {
                            throw new IndexOutOfBoundsException();
                        } else if (size == 0) {
                            break;
                        }

                        if (buffer.length < size) {
                            buffer = new byte[size];
                        }

                        dis.readFully(buffer, 0, size);

                        backBitmap = BitmapFactory.decodeByteArray(buffer, 0, size, options);

                        synchronized (mBitmapLock) {
                            Bitmap temp = mBitmap;
                            mBitmap = backBitmap;
                            mBitmapNew = true;
                            backBitmap = temp;
                            options.inBitmap = temp;
                        }
                    }

                    if (!cancelled) {
                        // Could break from loop due to size == 0.
                        MainActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                callback.onCompleted(true, null);
                            }
                        });
                    }
                } catch (final Exception e) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onCompleted(false, e);
                        }
                    });
                } finally {
                    if (backBitmap != null) {
                        backBitmap.recycle();
                    }
                }

                mCancel.set(true);
                Log.d("THREADS", "End read thread");
            }
        }).start();
    }

    private void runWriteThread(@NonNull ParcelFileDescriptor parcelFileDescriptor,
                                final ThreadCallback callback) {
        final FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();
        new Thread(null, new Runnable() {
            @Override
            public void run() {
                ByteBuffer bytes = ByteBuffer.allocate(Float.SIZE / Byte.SIZE * 4)
                        .order(ByteOrder.BIG_ENDIAN);
                bytes.putFloat(0.04f)
                    .putFloat(0.08f)
                    .putFloat(0.15f)
                    .putFloat(0.16f);

                try (OutputStream os = new FileOutputStream(fd)) {
                    DataOutputStream dos = new DataOutputStream(os);

                    while (!mCancel.get()) {
                        dos.write(bytes.array());

                        synchronized (mRotationLock) {
                            bytes.position(0);
                            for (int i = 0; i < 4; ++i) {
                                bytes.putFloat(mRotation[i]);
                            }
                        }

                        Thread.sleep(10); // Throttle to 100 fps.
                    }
                } catch (final Exception e) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onCompleted(false, e);
                        }
                    });
                }

                mCancel.set(true);
                Log.d("THREADS", "End write thread");
            }
        }).start();
    }

    private interface ThreadCallback {
        void onCompleted(boolean success, Exception e);
    }
}

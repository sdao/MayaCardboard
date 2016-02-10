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
import android.opengl.GLES30;
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
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    private static final int RIGHT_EYE = 0x00000000;
    private static final int LEFT_EYE  = 0x80000000;
    private static final int SIZE_MASK = 0x0FFFFFFF;
    private static final int EYE_MASK  = 0xF0000000;

    final private Object mRightBitmapLock = new Object();
    private Bitmap mRightBitmap = null;

    final private Object mLeftBitmapLock = new Object();
    private Bitmap mLeftBitmap = null;

    private AtomicBoolean mCancel = new AtomicBoolean();
    private PendingIntent mPermissionIntent;

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory =
                            (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CardboardView cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
        cardboardView.setRenderer(new CardboardView.StereoRenderer() {
            ScreenQuad screenQuad = new ScreenQuad(MainActivity.this);

            @Override
            public void onNewFrame(HeadTransform headTransform) {
                // TODO: send head position to Maya host.
            }

            @Override
            public void onDrawEye(Eye eye) {
                if (eye.getType() == Eye.Type.LEFT) {
                    synchronized (mLeftBitmapLock) {
                        screenQuad.bindBitmap(mLeftBitmap);
                    }
                } else {
                    synchronized (mRightBitmapLock) {
                        screenQuad.bindBitmap(mRightBitmap);
                    }
                }

                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                screenQuad.draw();
            }

            @Override
            public void onFinishFrame(Viewport viewport) {
            }

            @Override
            public void onSurfaceChanged(int width, int height) {
                // TODO: send dimensions to Maya host.
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
                if (getIntent().hasExtra(UsbManager.EXTRA_ACCESSORY)) {
                    UsbAccessory accessory = getIntent()
                            .getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                    connectAccessory(accessory);
                } else {
                    UsbManager manager = getSystemService(UsbManager.class);

                    UsbAccessory[] accessories = manager.getAccessoryList();
                    if (accessories.length == 0) {
                        toast("No accessories connected");
                        return true;
                    }

                    mPermissionIntent = PendingIntent.getBroadcast(MainActivity.this,
                            0, new Intent(ACTION_USB_PERMISSION), 0);
                    registerReceiver(mUsbReceiver, new IntentFilter(ACTION_USB_PERMISSION));

                    UsbAccessory accessory = accessories[0];
                    manager.requestPermission(accessory, mPermissionIntent);
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbReceiver);
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
        UsbManager manager = getSystemService(UsbManager.class);

        final ParcelFileDescriptor parcelFileDescriptor = manager.openAccessory(accessory);
        if (parcelFileDescriptor == null) {
            toast("Error connecting");
            return;
        }

        if (sendHandshake(parcelFileDescriptor)) {
            toast("Handshake successful!");
        } else {
            toast("Error in handshake");
            try {
                parcelFileDescriptor.close();
            } catch (IOException e) {}
            return;
        }

        ThreadCallback callback = new ThreadCallback() {
            @Override
            public void onCompleted(boolean success) {
                try {
                    parcelFileDescriptor.close();
                } catch (IOException e) {}

                if (success) {
                    toast("Connection ended successfully");
                } else {
                    toast("Connection ended due to IO error");
                }
                showSystemUi();
            }
        };

        hideSystemUi();
        mCancel.set(false);
        runReadThread(parcelFileDescriptor, callback);
        runWriteThread(parcelFileDescriptor, callback);
    }

    private boolean sendHandshake(@NonNull ParcelFileDescriptor parcelFileDescriptor) {
        FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();
        try (OutputStream os = new FileOutputStream(fd)) {
            byte[] handshake = new byte[16384];
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
                        int header = dis.readInt();

                        int size = header & SIZE_MASK;
                        Log.i("SIZE", "size=" + size);

                        if (size < 0 || size > 1024 * 1024 * 4 /* 4 MB */) {
                            throw new IndexOutOfBoundsException();
                        } else if (size == 0) {
                            break;
                        }

                        int eye = header & EYE_MASK;
                        Log.i("EYE", "eye=" + eye);

                        if (eye != LEFT_EYE && eye != RIGHT_EYE) {
                            throw new IndexOutOfBoundsException();
                        }

                        if (buffer.length < size) {
                            buffer = new byte[size];
                        }

                        dis.readFully(buffer, 0, size);

                        backBitmap = BitmapFactory.decodeByteArray(buffer, 0, size, options);

                        if (eye == LEFT_EYE) {
                            synchronized (mLeftBitmapLock) {
                                Bitmap temp = mLeftBitmap;
                                mLeftBitmap = backBitmap;
                                backBitmap = temp;
                                options.inBitmap = temp;
                            }
                        } else {
                            synchronized (mRightBitmapLock) {
                                Bitmap temp = mRightBitmap;
                                mRightBitmap = backBitmap;
                                backBitmap = temp;
                                options.inBitmap = temp;
                            }
                        }
                    }

                    if (!cancelled) {
                        // Could break from loop due to size == 0.
                        MainActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                callback.onCompleted(true);
                            }
                        });
                    }
                } catch (Exception e) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onCompleted(false);
                        }
                    });
                } finally {
                    if (backBitmap != null) {
                        backBitmap.recycle();
                    }
                }

                mCancel.set(true);
            }
        }).start();
    }

    private void runWriteThread(@NonNull ParcelFileDescriptor parcelFileDescriptor,
                                final ThreadCallback callback) {
        final FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();
        new Thread(null, new Runnable() {
            @Override
            public void run() {
                byte[] ack = new byte[] {
                        4, 8, 15, 16,
                        23, 42, 4, 8,
                        15, 16, 23, 42,
                        4, 8, 15, 16,
                };

                try (OutputStream os = new FileOutputStream(fd)) {
                    DataOutputStream dos = new DataOutputStream(os);

                    while (!mCancel.get()) {
                        dos.write(ack);
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onCompleted(false);
                        }
                    });
                }

                mCancel.set(true);
            }
        }).start();
    }

    private interface ThreadCallback {
        void onCompleted(boolean success);
    }
}

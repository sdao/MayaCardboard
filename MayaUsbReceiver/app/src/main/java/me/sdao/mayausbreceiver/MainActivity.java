package me.sdao.mayausbreceiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    final private Object mSurfaceLock = new Object();
    private SurfaceHolder mSurfaceHolder = null;
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

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                synchronized (mSurfaceLock) {
                    mSurfaceHolder = holder;
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                synchronized (mSurfaceLock) {
                    mSurfaceHolder = null;
                }
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

        runReadThread(parcelFileDescriptor, new ReadThreadCallback() {
            @Override
            public void onCompleted(boolean success) {
                if (success) {
                    toast("Connection ended successfully");
                } else {
                    toast("Connection ended due to IO error");
                }
                try {
                    parcelFileDescriptor.close();
                } catch (IOException e) {}
            }
        });
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
                              final ReadThreadCallback callback) {
        final FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();
        new Thread(null, new Runnable() {
            @Override
            public void run() {
                byte[] ack = new byte[] { 4, 8, 15, 16 };
                byte[] buffer = new byte[1024 * 1024]; // Initialize 1 MB at first.
                Bitmap bitmap;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inMutable = true;

                try (
                        InputStream is = new FileInputStream(fd);
                        OutputStream os = new FileOutputStream(fd);
                ) {
                    DataInputStream dis = new DataInputStream(is);
                    DataOutputStream dos = new DataOutputStream(os);

                    int i = 0;
                    while (true) {
                        ++i;
                        if (i % 100 == 0) {
                            dos.write(ack);
                        }

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

                        bitmap = BitmapFactory.decodeByteArray(buffer, 0, size, options);
                        options.inBitmap = bitmap;

                        synchronized (mSurfaceLock) {
                            if (mSurfaceHolder != null) {
                                Canvas canvas = mSurfaceHolder.lockCanvas();
                                if (canvas != null) {
                                    canvas.drawARGB(1, 0, 0, 0);
                                    canvas.drawBitmap(bitmap, 0, 0, null);
                                    mSurfaceHolder.unlockCanvasAndPost(canvas);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onCompleted(false);
                        }
                    });
                }

                MainActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        callback.onCompleted(true);
                    }
                });
            }
        }).start();
    }

    private interface ReadThreadCallback {
        void onCompleted(boolean success);
    }
}

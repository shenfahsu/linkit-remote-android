package com.mediatek.labs.linkitremote;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;


public class RemoteView extends AppCompatActivity {
    static private final String TAG = "RemoteView";

    private BluetoothDevice mDevice;
    private BluetoothGattCallback mCallback;
    private BluetoothGatt mGatt;
    private BluetoothGattService mService;
    private BluetoothGattCharacteristic mDeviceNameCharacteristic;
    private BluetoothGattCharacteristic mEventCharacteristic;
    private HashSet<BluetoothGattCharacteristic> mQuery;
    private HashMap<UUID, BluetoothGattCharacteristic> mValues;
    private ProgressBar mActivityIndicator;
    private Handler mHandler;
    private UIEventListener mEventListener;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_view);

        // Get the Intent that started this activity and extract the string
        Intent intent = getIntent();
        mDevice = intent.getExtras().getParcelable(DeviceList.CONNECT_DEVICE_MESSAGE);

        // for posting UI methods from GATT callbacks
        mHandler = new Handler(RemoteView.this.getApplicationContext().getMainLooper());

        // Set device name
        final String name = mDevice.getName();
        final ActionBar bar = getSupportActionBar();
        if(bar != null) {
            if(name != null) {
                bar.setTitle(name);
            } else {
                bar.setTitle(R.string.no_name);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        connectDevice();
    }

    @Override
    protected void onPause() {
        clearConnection();
        super.onPause();
    }

    private void connectDevice() {
        if (null == mDevice) {
            Log.d(TAG, "no device assigned to activity");
            return;
        }

        if (null != mCallback) {
            // already connected
            Log.d(TAG, "already connected to device");
            return;
        }


        mCallback = new GattCallback();

        mGatt = mDevice.connectGatt(getApplicationContext(),
                false, // direct connect
                mCallback);

        mActivityIndicator = (ProgressBar) findViewById(R.id.progressBar);
        mActivityIndicator.setVisibility(View.VISIBLE);

    }

    // cleanup resources used for BLE connection & UI views
    private void clearConnection() {

        // clear UI elements
        final RelativeLayout viewGroup = (RelativeLayout) findViewById(R.id.remote_layout);
        if(viewGroup != null) {
            viewGroup.removeAllViews();
        }

        if(mGatt != null) {
            mGatt.disconnect();
        }

        // release resources
        mCallback = null;
        mGatt = null;
        mService = null;
        mDeviceNameCharacteristic = null;
        mEventCharacteristic = null;
        mQuery = null;
        mValues = null;
        mEventListener = null;
    }

    // Note that these callbacks are invoked in a separate context,
    // and MUST NOT call Android UI methods directly. Use mHandler.post() instead
    private class GattCallback extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    Log.d(TAG, "connected");
                    mGatt.discoverServices();
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    Log.d(TAG, "device disconnected");

                    // Post to main thread to clean up
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            RemoteView.this.clearConnection();
                        }
                    });
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Service discovery failed");
                return;
            }

            // Try to get device name
            BluetoothGattService gapService = gatt.getService(UUID.fromString("00001800-0000-1000-8000-00805F9B34FB"));
            if (null != gapService) {
                mDeviceNameCharacteristic = gapService.getCharacteristic(UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB"));
                gatt.readCharacteristic(mDeviceNameCharacteristic);
            }

            // read our own "Remote Control" service info
            mService = gatt.getService(Constants.rcService.getUuid());

            if(mService != null) {
                mValues = new HashMap<>();
                mQuery = new HashSet<>(mService.getCharacteristics());
                mEventCharacteristic = mService.getCharacteristic(Constants.rcEventArray);

                for (BluetoothGattCharacteristic c : mQuery) {
                    final boolean result = gatt.readCharacteristic(c);
                    if (!result) {
                        // we failed to continuous read - read later
                        break;
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);

            if (mDeviceNameCharacteristic != null && characteristic == mDeviceNameCharacteristic) {
                // Update device name if needed
                final String fullDeviceName = mDeviceNameCharacteristic.getStringValue(0);
                if (!fullDeviceName.isEmpty()) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            RemoteView.this.setTitle(fullDeviceName);
                            ActionBar bar = getSupportActionBar();
                            if(null != bar) {
                                 bar.setTitle(fullDeviceName);
                            }
                        }
                    });
                }
            } else {
                // these are Remote Controll service characteristics
                mQuery.remove(characteristic);
                mValues.put(characteristic.getUuid(), characteristic);
            }

            if (mQuery.isEmpty()) {
                Log.d(TAG, "all characteristic read");
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        createDeviceLayout();
                    }
                });
            } else {
                // keep reading
                for (BluetoothGattCharacteristic c : mQuery) {
                    final boolean result = gatt.readCharacteristic(c);
                    if (!result) {
                        // we failed to continuous read - read later
                        break;
                    }
                }
            }
        }

        private Drawable loadDrawable(int id){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return getResources().getDrawable(id, null);
            } else {
                return getResources().getDrawable(id);
            }
        }

        private void createDeviceLayout() {
            final DeviceInfo d = readDeviceInfo();
            if (null == d) {
                Log.d(TAG, "cannot load device info!");
                return;
            }

            // Setup UI resources
            mEventListener = new UIEventListener();

            final RelativeLayout v = (RelativeLayout) findViewById(R.id.remote_layout);
            final int padding = Constants.VIEW_PADDING;
            final int cw = v.getWidth() / d.col;
            final int ch = v.getHeight() / d.row;

            for (ControlInfo c : d.controls) {
                final int controlWidthInCell = c.cell.width();
                final int controlHeightInCell = c.cell.height();
                c.cell.left *= cw;
                c.cell.top *= ch;
                c.cell.right = c.cell.left + controlWidthInCell * cw;
                c.cell.bottom = c.cell.top + controlHeightInCell * ch;
                c.cell.inset(padding, padding);

                final int fontColor = Brandcolor.font.primary;

                View component = null;
                switch (c.type) {
                    case label: {
                        // labels are disabled buttons
                        TextView b = new TextView(RemoteView.this);
                        b.setBackground(loadDrawable(R.drawable.rectangle_label));
                        b.getBackground().setColorFilter(c.color, PorterDuff.Mode.MULTIPLY);
                        b.setText(c.text);
                        b.setTextColor(fontColor);
                        b.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
                        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
                        component = b;
                        break;
                    }
                    case circleButton: {
                        Button b = new Button(RemoteView.this);
                        b.setBackground(loadDrawable(R.drawable.round_button));
                        b.getBackground().setColorFilter(c.color, PorterDuff.Mode.MULTIPLY);

                        b.setText(c.text);
                        b.setTextColor(fontColor);
                        b.setTypeface(b.getTypeface(), Typeface.BOLD);

                        // we want to make a "square" circle, not an oval - adjust cell rect.
                        final int cX = c.cell.centerX();
                        final int cY = c.cell.centerY();
                        final int radius = Math.min(c.cell.width(), c.cell.height()) / 2;
                        c.cell.left = cX - radius;
                        c.cell.right = cX + radius;
                        c.cell.top = cY - radius;
                        c.cell.bottom = cY + radius;

                        b.setTag(c);
                        b.setOnTouchListener(mEventListener);
                        component = b;
                        break;
                    }
                    case pushButton: {
                        Button b = new Button(RemoteView.this);
                        b.setBackground(loadDrawable(R.drawable.rectangle_button));
                        b.getBackground().setColorFilter(c.color, PorterDuff.Mode.MULTIPLY);
                        b.setText(c.text);
                        b.setTextColor(fontColor);
                        b.setTypeface(b.getTypeface(), Typeface.BOLD);

                        b.setOnTouchListener(mEventListener);
                        b.setTag(c);
                        component = b;
                        break;
                    }
                    case switchButton: {
                        // load the slider view component
                        View switchPanel = LayoutInflater.from(getApplicationContext())
                                .inflate(R.layout.switch_panel, null);
                        switchPanel.setBackground(loadDrawable(R.drawable.rectangle_button));
                        switchPanel.getBackground().setColorFilter(Brandcolor.grey.secondary, PorterDuff.Mode.MULTIPLY);

                        ToggleButton btn = (ToggleButton)switchPanel.findViewById(R.id.switch_button);
                        btn.getBackground().setColorFilter(c.color, PorterDuff.Mode.MULTIPLY);

                        TextView title = (TextView)switchPanel.findViewById(R.id.switch_title);
                        title.setText(c.text);
                        title.setTextColor(fontColor);
                        title.setTypeface(title.getTypeface(), Typeface.BOLD);

                        btn.setTag(c);
                        btn.setOnClickListener(mEventListener);
                        component = switchPanel;
                        break;
                    }
                    case slider: {
                        // load the slider view component
                        View sliderPanel = LayoutInflater.from(getApplicationContext())
                                .inflate(R.layout.slider_panel, null);
                        sliderPanel.setBackground(loadDrawable(R.drawable.rectangle_button));
                        sliderPanel.getBackground().setColorFilter(Brandcolor.grey.secondary, PorterDuff.Mode.MULTIPLY);

                        SeekBar bar = (SeekBar)sliderPanel.findViewById(R.id.slider_bar);
                        TextView title = (TextView)sliderPanel.findViewById(R.id.slider_title);
                        TextView value = (TextView)sliderPanel.findViewById(R.id.slider_value);
                        bar.getThumb().setColorFilter(c.color, PorterDuff.Mode.SRC_ATOP);
                        bar.getProgressDrawable().setColorFilter(c.color, PorterDuff.Mode.SRC_ATOP);
                        title.setText(c.text);
                        title.setTypeface(title.getTypeface(), Typeface.BOLD);
                        value.setTypeface(value.getTypeface(), Typeface.BOLD);
                        value.setText(String.valueOf(c.config.data3));
                        // bar.setMin((int)c.config.data1);

                        bar.setTag(c);
                        bar.setMax((int) c.config.data2);
                        bar.setProgress((int) c.config.data3);
                        bar.setOnSeekBarChangeListener(mEventListener);

                        component = sliderPanel;

                        break;
                    }
                    default:
                        break;
                }

                // layout the control
                if (component != null) {
                    RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(c.cell.width(), c.cell.height());
                    params.leftMargin = c.cell.left;
                    params.topMargin = c.cell.top;
                    v.addView(component, params);
                    c.view = component;
                }
            }

            mActivityIndicator.setVisibility(View.INVISIBLE);
        }

        private DeviceInfo readDeviceInfo() {
            if (mValues.isEmpty()) {
                return null;
            }

            DeviceInfo d = new DeviceInfo();

            d.row = mValues.get(Constants.rcRow).
                    getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0);
            d.col = mValues.get(Constants.rcCol).
                    getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0);
            d.isLandscape = mValues.get(Constants.rcOrientation).
                    getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0) > 0;

            final int controlCount = mValues.get(Constants.rcControlCount).
                    getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0);
            final byte[] typeArray = mValues.get(Constants.rcControlTypes).getValue();
            final byte[] colorArray = mValues.get(Constants.rcColors).getValue();
            final byte[] rectArray = mValues.get(Constants.rcFrames).getValue();
            final byte[] configArray = mValues.get(Constants.rcConfigDataArray).getValue();
            final String nameString = mValues.get(Constants.rcNames).getStringValue(0);
            final String[] names = nameString.split("\n");

            d.controls = new ControlInfo[controlCount];

            // Note that config data are uint16_t stored in little endian byte order.
            final ByteBuffer configDataBuffer = ByteBuffer.wrap(configArray);
            configDataBuffer.order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < controlCount; ++i) {
                ControlInfo c = new ControlInfo();
                c.index = i;
                c.type = ControlInfo.ControlType.getEnum(typeArray[i]);
                c.color = Brandcolor.fromBLE(colorArray[i]).primary;
                c.cell = new Rect();
                c.cell.left = rectArray[i * 4];
                c.cell.top = rectArray[i * 4 + 1];
                c.cell.right = c.cell.left + rectArray[i * 4 + 2];
                c.cell.bottom = c.cell.top + rectArray[i * 4 + 3];
                try {
                    c.text = names[i];
                } catch (Exception e) {
                    c.text = getResources().getString(R.string.no_name);
                }

                c.config = new ControlConfig();
                c.config.data1 = configDataBuffer.getShort();
                c.config.data2 = configDataBuffer.getShort();
                c.config.data3 = configDataBuffer.getShort();
                c.config.data4 = configDataBuffer.getShort();
                d.controls[i] = c;
            }

            return d;
        }

    }

    private void sendRemoteEvent(ControlInfo c, int event, int data) {
        if(null == mGatt || null == mEventCharacteristic) {
            return;
        }

        byte[] eventData = mEventCharacteristic.getValue();
        final int index = c.index;

        eventData[index * 4] += 1;                          // sequence number - increment it
        eventData[index * 4 + 1] = (byte)event;                       // Event
        eventData[index * 4 + 2] = (byte)(data & 0xFF);         // Event data, high byte
        eventData[index * 4 + 3] = (byte)((data >> 8) & 0xFF);  // Event data, low byte

        mEventCharacteristic.setValue(eventData);
        mGatt.writeCharacteristic(mEventCharacteristic);

    }

    private class UIEventListener implements View.OnTouchListener, SeekBar.OnSeekBarChangeListener, ToggleButton.OnClickListener {
        public boolean onTouch(View var1, MotionEvent var2) {
            ControlInfo c = (ControlInfo)var1.getTag();
            Button b = (Button)var1;

            Log.d(TAG, "btn state=" + String.valueOf(var2));

            switch(var2.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sendRemoteEvent(c, ControlEvent.valueChange, 1);
                    break;
                case MotionEvent.ACTION_UP:
                    sendRemoteEvent(c, ControlEvent.valueChange, 0);
                    break;
                default:
                    // unrecognized action
                    break;
            }


            return false;
        }

        public void onProgressChanged(SeekBar var1, int var2, boolean var3){
            ControlInfo c = (ControlInfo)var1.getTag();

            // update label
            TextView valueLabel = (TextView)c.view.findViewById(R.id.slider_value);
            valueLabel.setText(String.valueOf(var1.getProgress()));
        }

        public void onStartTrackingTouch(SeekBar var1) {
            // do nothing, but mandatory to implement.
        }

        public void onStopTrackingTouch(SeekBar var1) {
            ControlInfo c = (ControlInfo)var1.getTag();
            Log.d(TAG, "seek end = " + String.valueOf(var1.getProgress()));
            sendRemoteEvent(c, ControlEvent.valueChange, var1.getProgress());
        }

        public void onClick(View var1) {
            ControlInfo c = (ControlInfo)var1.getTag();
            if(c.type == ControlInfo.ControlType.switchButton) {
                ToggleButton btn = (ToggleButton)var1;
                Log.d(TAG, "toggle = " + String.valueOf(btn.isChecked()));
                sendRemoteEvent(c, ControlEvent.valueChange, btn.isChecked() ? 1 : 0);
            }
        }

    }
}
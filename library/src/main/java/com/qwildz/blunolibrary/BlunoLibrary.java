package com.qwildz.blunolibrary;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

public class BlunoLibrary {

    private final static String TAG = BlunoLibrary.class.getSimpleName();

    private static final String SerialPortUUID = "0000dfb1-0000-1000-8000-00805f9b34fb";
    private static final String CommandUUID = "0000dfb2-0000-1000-8000-00805f9b34fb";
    private static final String ModelNumberStringUUID = "00002a24-0000-1000-8000-00805f9b34fb";

    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final String DEFAULT_PASSWORD = "DFRobot";

    private Context mainContext;
    private String mPassword;
    private String mBaudrateBuffer;
    private boolean mInitialized, mReceiverRegistered = false;

    private static BluetoothGattCharacteristic mSCharacteristic, mModelNumberCharacteristic, mSerialPortCharacteristic, mCommandCharacteristic;
    private BluetoothLeService mBluetoothLeService;
    private BluetoothAdapter mBluetoothAdapter;

    private BlunoListener blunoListener;

//    private boolean mScanning = false;
//    private String mDeviceName;
//    private String mDeviceAddress;

    public enum ConnectionStateEnum {isNull, isScanning, isToScan, isConnecting, isConnected, isDisconnecting}

    private ConnectionStateEnum mConnectionState = ConnectionStateEnum.isNull;

    private Handler mHandler = new Handler();

    // Connecting Timeout Handler
    private Runnable mConnectingOverTimeRunnable = new Runnable() {

        @Override
        public void run() {
            if (mConnectionState == ConnectionStateEnum.isConnecting)
                changeState(ConnectionStateEnum.isToScan);
            if (mBluetoothLeService != null)
                mBluetoothLeService.close();
        }
    };

    // Disconnecting Timeout Handler
    private Runnable mDisonnectingOverTimeRunnable = new Runnable() {

        @Override
        public void run() {
            if (mConnectionState == ConnectionStateEnum.isDisconnecting)
                changeState(ConnectionStateEnum.isToScan);
            if (mBluetoothLeService != null)
                mBluetoothLeService.close();
        }
    };

    // Code to manage Service lifecycle.
    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            System.out.println("mServiceConnection onServiceConnected");
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                //((Activity) mainContext).finish();
            } else {
                changeState(ConnectionStateEnum.isToScan);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            System.out.println("mServiceConnection onServiceDisconnected");
            mBluetoothLeService = null;
            mInitialized = false;
        }
    };

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            blunoListener.onDeviceDetected(device, rssi, scanRecord);
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            System.out.println("mGattUpdateReceiver->onReceive->action=" + action);

            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mHandler.removeCallbacks(mConnectingOverTimeRunnable);
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                changeState(ConnectionStateEnum.isToScan);
                mHandler.removeCallbacks(mDisonnectingOverTimeRunnable);
                if (mBluetoothLeService != null)
                    mBluetoothLeService.close();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                List<BluetoothGattService> services = mBluetoothLeService.getSupportedGattServices();
                for (BluetoothGattService service : services) {
                    System.out.println("ACTION_GATT_SERVICES_DISCOVERED  " + service.getUuid().toString());
                }
                getGattServices(services);
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                if (mSCharacteristic == mModelNumberCharacteristic) {
                    if (intent.getStringExtra(BluetoothLeService.EXTRA_DATA).toUpperCase().startsWith("DF BLUNO")) {
                        mBluetoothLeService.setCharacteristicNotification(mSCharacteristic, false);

                        mSCharacteristic = mCommandCharacteristic;
                        mSCharacteristic.setValue(mPassword);
                        mBluetoothLeService.writeCharacteristic(mSCharacteristic);
                        mSCharacteristic.setValue(mBaudrateBuffer);
                        mBluetoothLeService.writeCharacteristic(mSCharacteristic);

                        mSCharacteristic = mSerialPortCharacteristic;
                        mBluetoothLeService.setCharacteristicNotification(mSCharacteristic, true);

                        changeState(ConnectionStateEnum.isConnected);
                    } else {
                        Log.e(TAG, "Please select DFRobot devices");
                        changeState(ConnectionStateEnum.isToScan);
                    }
                } else if (mSCharacteristic == mSerialPortCharacteristic) {
                    if (blunoListener != null)
                        blunoListener.onSerialReceived(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                }

                System.out.println("displayData " + intent.getStringExtra(BluetoothLeService.EXTRA_DATA));

//            	mPlainProtocol.mReceivedframe.append(intent.getStringExtra(BluetoothLeService.EXTRA_DATA)) ;
//            	System.out.print("mPlainProtocol.mReceivedframe:");
//            	System.out.println(mPlainProtocol.mReceivedframe.toString());
            }
        }
    };

    public interface BlunoListener {
        void onDeviceDetected(final BluetoothDevice device, int rssi, byte[] scanRecord);

        void onConectionStateChange(ConnectionStateEnum state);

        void onSerialReceived(String data);
    }

    public BlunoLibrary(Context theContext) {
        mainContext = theContext;

        if (!prepareBluetoothFeature()) {
            throw new ExceptionInInitializerError("No BLE Feature");
        }
    }

    public void setBlunoListener(BlunoListener listener) {
        blunoListener = listener;
    }

    public void serialSend(String data) {
        if (mConnectionState == ConnectionStateEnum.isConnected) {
            mSCharacteristic.setValue(data);
            mBluetoothLeService.writeCharacteristic(mSCharacteristic);
        }
    }

    public void serialSend(byte[] data) {
        if (mConnectionState == ConnectionStateEnum.isConnected) {
            mSCharacteristic.setValue(data);
            mBluetoothLeService.writeCharacteristic(mSCharacteristic);
        }
    }

    public boolean initialize() {
        if (!mBluetoothAdapter.isEnabled()) {
            return false;
        }

        if (!mInitialized) {
            Intent gattServiceIntent = new Intent(mainContext, BluetoothLeService.class);
            mainContext.bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

            registerReceiver();

            mInitialized = true;
        }

        return true;

        // Initializes list view adapter.
//        mLeDeviceListAdapter = new LeDeviceListAdapter();
        // Initializes and show the scan Device Dialog
//        mScanDeviceDialog = new AlertDialog.Builder(mainContext)
//                .setTitle("BLE Device Scan...").setAdapter(mLeDeviceListAdapter, new DialogInterface.OnClickListener() {
//
//                    @Override
//                    public void onClick(DialogInterface dialog, int which) {
//                        final BluetoothDevice device = mLeDeviceListAdapter.getDevice(which);
//                        if (device == null)
//                            return;
//                        scanLeDevice(false);
//
//                        if (device.getName() == null || device.getAddress() == null) {
//                            mConnectionState = ConnectionStateEnum.isToScan;
//                            blunoListener.onConectionStateChange(mConnectionState);
//                        } else {
//
//                            System.out.println("onListItemClick " + device.getName());
//
//                            System.out.println("Device Name:" + device.getName() + "   " + "Device Name:" + device.getAddress());
//
//                            mDeviceName = device.getName();
//                            mDeviceAddress = device.getAddress();
//
//                            if (mBluetoothLeService.connect(mDeviceAddress)) {
//                                Log.d(TAG, "Connect request success");
//                                mConnectionState = ConnectionStateEnum.isConnecting;
//                                blunoListener.onConectionStateChange(mConnectionState);
//                                mHandler.postDelayed(mConnectingOverTimeRunnable, 10000);
//                            } else {
//                                Log.d(TAG, "Connect request fail");
//                                mConnectionState = ConnectionStateEnum.isToScan;
//                                blunoListener.onConectionStateChange(mConnectionState);
//                            }
//                        }
//                    }
//                })
//                .setOnCancelListener(new DialogInterface.OnCancelListener() {
//
//                    @Override
//                    public void onCancel(DialogInterface arg0) {
//                        System.out.println("mBluetoothAdapter.stopLeScan");
//
//                        mConnectionState = ConnectionStateEnum.isToScan;
//                        blunoListener.onConectionStateChange(mConnectionState);
//                        mScanDeviceDialog.dismiss();
//
//                        scanLeDevice(false);
//                    }
//                }).create();
    }

    @SuppressWarnings("deprecation")
    public void startScan() {
        if (mConnectionState == ConnectionStateEnum.isToScan) {
            mBluetoothAdapter.startLeScan(mLeScanCallback);
            changeState(ConnectionStateEnum.isScanning);
        }
    }

    @SuppressWarnings("deprecation")
    public void stopScan() {
        if (mConnectionState == ConnectionStateEnum.isScanning) {
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
            changeState(ConnectionStateEnum.isToScan);
        }
    }

    public void connect(String address) {
        connect(address, DEFAULT_BAUD_RATE, DEFAULT_PASSWORD);
    }

    public void connect(String address, int baudrate) {
        connect(address, baudrate, DEFAULT_PASSWORD);
    }

    public void connect(String address, int baudrate, String password) {
        stopScan();

        setBaudRate(baudrate);
        setPassword(password);

        Log.e(TAG, "A");
        if (mBluetoothLeService != null) {
            Log.e(TAG, "B");
            if (mBluetoothLeService.connect(address)) {
                Log.e(TAG, "C");
                changeState(ConnectionStateEnum.isConnecting);
                mHandler.postDelayed(mConnectingOverTimeRunnable, 10000);
            } else {
                Log.e(TAG, "Connect request fail");
                changeState(ConnectionStateEnum.isToScan);

            }
        }
    }

    public void disconnect() {
        changeState(ConnectionStateEnum.isDisconnecting);

        if (mBluetoothLeService != null) {
            mBluetoothLeService.disconnect();
            mHandler.postDelayed(mDisonnectingOverTimeRunnable, 10000);
        }

        mSCharacteristic = null;
    }

    public void resume() {
        if (mInitialized) {
            registerReceiver();
        }
    }

    public void pause() {
        stopScan();
        if (mReceiverRegistered) {
            mainContext.unregisterReceiver(mGattUpdateReceiver);
            mReceiverRegistered = false;
        }
    }

    public void destroy() {
        mainContext.unbindService(mServiceConnection);

        if (mBluetoothLeService != null) {
            mHandler.removeCallbacks(mDisonnectingOverTimeRunnable);
            mBluetoothLeService.close();
        }

        mBluetoothLeService = null;
        mSCharacteristic = null;

        mInitialized = false;

        changeState(ConnectionStateEnum.isNull);
    }

    public boolean isBluetoothEnabled() {
        return mBluetoothAdapter.isEnabled();
    }

//    public void onPauseProcess() {
////        scanLeDevice(false);
//        mainContext.unregisterReceiver(mGattUpdateReceiver);
//        mConnectionState = ConnectionStateEnum.isToScan;
//        blunoListener.onConectionStateChange(mConnectionState);
//        if (mBluetoothLeService != null) {
//            mBluetoothLeService.disconnect();
//            mHandler.postDelayed(mDisonnectingOverTimeRunnable, 10000);
//        }
//        mSCharacteristic = null;
//
//    }


//    public void onStopProcess() {
//        if (mBluetoothLeService != null) {
//            mHandler.removeCallbacks(mDisonnectingOverTimeRunnable);
//            mBluetoothLeService.close();
//        }
//        mSCharacteristic = null;
//    }

//    public void onDestroyProcess() {
//        mainContext.unbindService(mServiceConnection);
//        mBluetoothLeService = null;
//    }

//    public void onActivityResultProcess(int requestCode, int resultCode, Intent data) {
//        // User chose not to enable Bluetooth.
//        if (requestCode == REQUEST_ENABLE_BT
//                && resultCode == Activity.RESULT_CANCELED) {
////            ((Activity) mainContext).finish();
//        }
//    }

    private boolean prepareBluetoothFeature() {
        // Use this check to determine whether BLE is supported on the device.
        // Then you can
        // selectively disable BLE-related features.
        if (!mainContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE)) {
            return false;
        }

        // Initializes a Bluetooth adapter. For API level 18 and above, get a
        // reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager = (BluetoothManager) mainContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        return mBluetoothAdapter != null;
    }

    private void registerReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);

        mainContext.registerReceiver(mGattUpdateReceiver, intentFilter);
        mReceiverRegistered = true;
    }

    private void setBaudRate(int baud) {
        mBaudrateBuffer = "AT+CURRUART=" + baud + "\r\n";
    }

    private void setPassword(String pass) {
        mPassword = "AT+PASSWOR=" + pass + "\r\n";
    }

    private void getGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;

        String uuid;
        mModelNumberCharacteristic = null;
        mSerialPortCharacteristic = null;
        mCommandCharacteristic = null;

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            uuid = gattService.getUuid().toString();
            System.out.println("displayGattServices + uuid=" + uuid);

            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                uuid = gattCharacteristic.getUuid().toString();
                switch (uuid) {
                    case ModelNumberStringUUID:
                        mModelNumberCharacteristic = gattCharacteristic;
                        System.out.println("mModelNumberCharacteristic  " + mModelNumberCharacteristic.getUuid().toString());
                        break;
                    case SerialPortUUID:
                        mSerialPortCharacteristic = gattCharacteristic;
                        System.out.println("mSerialPortCharacteristic  " + mSerialPortCharacteristic.getUuid().toString());
                        break;
                    case CommandUUID:
                        mCommandCharacteristic = gattCharacteristic;
                        System.out.println("mCommandCharacteristic  " + mCommandCharacteristic.getUuid().toString());
                        break;
                }
            }
        }

        if (mModelNumberCharacteristic == null || mSerialPortCharacteristic == null || mCommandCharacteristic == null) {
            Toast.makeText(mainContext, "Please select DFRobot devices", Toast.LENGTH_SHORT).show();
            changeState(ConnectionStateEnum.isToScan);
        } else {
            mSCharacteristic = mModelNumberCharacteristic;
            mBluetoothLeService.setCharacteristicNotification(mSCharacteristic, true);
            mBluetoothLeService.readCharacteristic(mSCharacteristic);
        }
    }

    private void changeState(ConnectionStateEnum state) {
        mConnectionState = state;
        if (blunoListener != null)
            blunoListener.onConectionStateChange(mConnectionState);
    }

}

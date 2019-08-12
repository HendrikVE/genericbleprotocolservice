package de.vanappsteer.genericbleprotocolservice;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;

import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleDeviceServices;
import com.polidea.rxandroidble2.scan.ScanResult;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.plugins.RxJavaPlugins;

public class GenericBleProtocolService extends Service {

    public final static int READY = 0;
    public final static int BLUETOOTH_NOT_AVAILABLE = 1;
    public final static int LOCATION_PERMISSION_NOT_GRANTED = 2;
    public final static int BLUETOOTH_NOT_ENABLED = 3;
    public final static int LOCATION_SERVICES_NOT_ENABLED = 4;

    public final static int STATE_OFF = 0;
    public final static int STATE_TURNING_ON = 1;
    public final static int STATE_ON = 2;
    public final static int STATE_TURNING_OFF = 3;

    public static final int DEVICE_DISCONNECTED = 0;
    public static final int DEVICE_CONNECTION_ERROR_GENERIC = 1;
    public static final int DEVICE_CONNECTION_ERROR_UNSUPPORTED = 2;
    public static final int DEVICE_CONNECTION_ERROR_READ = 3;
    public static final int DEVICE_CONNECTION_ERROR_WRITE = 4;

    private final IBinder mBinder = new LocalBinder();

    private List<ScanListener> mScanListenerList = new ArrayList<>();
    private List<BluetoothPreconditionStateListener> mBluetoothPreconditionStateListenerList = new ArrayList<>();
    private List<BluetoothAdapterStateListener> mBluetoothAdapterStateListenerList = new ArrayList<>();

    private Set<DeviceConnectionListener> mDeviceConnectionListenerSet = new HashSet<>();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    private RxBleClient mRxBleClient;
    private RxBleConnection mRxBleConnection;

    private Disposable mFlowDisposable;
    private Disposable mScanSubscription;
    private Disposable mConnectionSubscription;
    private Disposable mDisposable;

    public GenericBleProtocolService() { }

    @Override
    public void onCreate() {

        super.onCreate();

        RxJavaPlugins.setErrorHandler(e -> {
            LoggingUtil.error(e.getMessage());
        });

        mRxBleClient = RxBleClient.create(this);

        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        if (mBluetoothManager != null) {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
        }

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBroadcastReceiver, filter);

        mFlowDisposable = mRxBleClient.observeStateChanges()
            .subscribe(
                state -> {
                    switch (state) {
                        case READY:
                            mBluetoothPreconditionStateListenerList.forEach(
                                l -> l.onStateChange(READY)
                            );
                            break;

                        case BLUETOOTH_NOT_AVAILABLE:
                            mBluetoothPreconditionStateListenerList.forEach(
                                l -> l.onStateChange(BLUETOOTH_NOT_AVAILABLE)
                            );
                            break;

                        case LOCATION_PERMISSION_NOT_GRANTED:
                            mBluetoothPreconditionStateListenerList.forEach(
                                l -> l.onStateChange(LOCATION_PERMISSION_NOT_GRANTED)
                            );
                            break;

                        case BLUETOOTH_NOT_ENABLED:
                            mBluetoothPreconditionStateListenerList.forEach(
                                l -> l.onStateChange(BLUETOOTH_NOT_ENABLED)
                            );
                            break;

                        case LOCATION_SERVICES_NOT_ENABLED:
                            mBluetoothPreconditionStateListenerList.forEach(
                                l -> l.onStateChange(LOCATION_SERVICES_NOT_ENABLED)
                            );
                            break;

                        default:
                            LoggingUtil.warning("unhandled state: " + state);
                    }
                },
                throwable -> {
                    LoggingUtil.error(throwable.getMessage());
                }
            );
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {

        unregisterReceiver(mBroadcastReceiver);

        disconnectDevice();
    }

    public UUID getServiceUuid() {
        return null;
    }

    protected boolean checkSupportedService(RxBleDeviceServices deviceServices) {
        return true;
    }

    public void startDeviceScan() {
        mScanSubscription = mRxBleClient.scanBleDevices(
            new ScanSettings.Builder().build()
        ).subscribe(
            scanResult -> {
                mScanListenerList.forEach(l -> l.onScanResult(scanResult));
            },
            throwable -> {
                // TODO
            }
        );
    }

    public void stopDeviceScan() {
        mScanSubscription.dispose();
    }

    @SuppressLint("CheckResult")
    public void connectDevice(RxBleDevice device) {

        if (mConnectionSubscription != null && !mConnectionSubscription.isDisposed()) {
            //allow only one connection at the same time
            disconnectDevice();
        }

        mConnectionSubscription = device.establishConnection(false)
                .subscribe(rxBleConnection -> {
                    mRxBleConnection = rxBleConnection;
                    mRxBleConnection.discoverServices().subscribe(
                        deviceServices -> {
                            if (checkSupportedService(deviceServices)) {
                                mDeviceConnectionListenerSet.forEach(
                                    l -> l.onDeviceConnected()
                                );
                            }
                            else {
                                mDeviceConnectionListenerSet.forEach(
                                    l -> l.onDeviceConnectionError(DEVICE_CONNECTION_ERROR_UNSUPPORTED)
                                );
                                disconnectDevice();
                            }
                        },
                        throwable -> {
                            mDeviceConnectionListenerSet.forEach(
                                l -> l.onDeviceConnectionError(DEVICE_CONNECTION_ERROR_GENERIC)
                            );
                        }
                    );
                },
                throwable -> {
                    mDeviceConnectionListenerSet.forEach(
                        l -> l.onDeviceConnectionError(DEVICE_CONNECTION_ERROR_GENERIC)
                    );
                }
            );
    }

    public int getBluetoothState() {

        RxBleClient.State state = mRxBleClient.getState();

        switch (state) {

            case READY:
                return READY;

            case BLUETOOTH_NOT_AVAILABLE:
                return BLUETOOTH_NOT_AVAILABLE;

            case LOCATION_PERMISSION_NOT_GRANTED:
                return LOCATION_PERMISSION_NOT_GRANTED;

            case BLUETOOTH_NOT_ENABLED:
                return BLUETOOTH_NOT_ENABLED;

            case LOCATION_SERVICES_NOT_ENABLED:
                return LOCATION_SERVICES_NOT_ENABLED;

            default:
                LoggingUtil.warning("unhandled state: " + state);
                return -1;
        }
    }

    public int getBluetoothAdapterState() {

        if (mBluetoothAdapter == null) {
            return BluetoothAdapter.STATE_OFF;
        }

        int state = mBluetoothAdapter.getState();

        switch (state) {

            case BluetoothAdapter.STATE_OFF:
                return STATE_OFF;

            case BluetoothAdapter.STATE_TURNING_ON:
                return STATE_TURNING_ON;

            case BluetoothAdapter.STATE_ON:
                return STATE_ON;

            case BluetoothAdapter.STATE_TURNING_OFF:
                return STATE_TURNING_OFF;

            default:
                LoggingUtil.warning("unhandled state: " + state);
                return STATE_OFF;
        }
    }

    public void disconnectDevice() {

        if (mConnectionSubscription != null) {
            mConnectionSubscription.dispose();
        }
    }

    private boolean isDisconnected() {

        return mConnectionSubscription.isDisposed();
    }

    @SuppressLint("CheckResult")
    public void readCharacteristic(UUID uuid) {

        mRxBleConnection.readCharacteristic(uuid)
            .subscribe(
                characteristicValue -> {
                    mDeviceConnectionListenerSet.forEach(
                        l -> l.onCharacteristicRead(uuid, new String(characteristicValue))
                    );
                },
                throwable -> {
                    mDeviceConnectionListenerSet.forEach(
                        l -> l.onDeviceConnectionError(DEVICE_CONNECTION_ERROR_READ)
                    );
                }
            );
    }

    @SuppressLint("CheckResult")
    public void writeCharacteristic(UUID uuid, byte[] value) {

        mRxBleConnection.writeCharacteristic(uuid, value)
            .subscribe(
                characteristicValue -> {
                    mDeviceConnectionListenerSet.forEach(
                        l -> l.onCharacteristicWrote(uuid, new String(characteristicValue))
                    );
                },
                throwable -> {
                    mDeviceConnectionListenerSet.forEach(
                        l -> l.onDeviceConnectionError(DEVICE_CONNECTION_ERROR_READ)
                    );
                }
            );
    }

    @SuppressLint("CheckResult")
    public void subscribeIndication(UUID uuid) {

        mDisposable = mRxBleConnection.setupIndication(uuid)
                .flatMap(notificationObservable -> notificationObservable)
                .subscribe(
                        bytes -> {
                            mDeviceConnectionListenerSet.forEach(
                                    l -> l.onCharacteristicRead(uuid, new String(bytes))
                            );
                        },
                        throwable -> {
                            LoggingUtil.error(throwable.getMessage());
                        }
                );
    }

    public void addDeviceConnectionListener(DeviceConnectionListener listener) {

        mDeviceConnectionListenerSet.add(listener);
    }

    public void removeDeviceConnectionListener(DeviceConnectionListener listener) {

        mDeviceConnectionListenerSet.remove(listener);
    }

    public void addScanListener(ScanListener listener) {

        mScanListenerList.add(listener);
    }

    public void removeScanListener(ScanListener listener) {

        mScanListenerList.remove(listener);
    }

    public void addBluetoothStateListener(BluetoothPreconditionStateListener listener) {

        mBluetoothPreconditionStateListenerList.add(listener);
    }

    public void removeBluetoothStateListener(BluetoothPreconditionStateListener listener) {

        mBluetoothPreconditionStateListenerList.remove(listener);
    }

    public void addBluetoothAdapterStateListener(BluetoothAdapterStateListener listener) {

        mBluetoothAdapterStateListenerList.add(listener);
    }

    public void removeBluetoothAdapterStateListener(BluetoothAdapterStateListener listener) {

        mBluetoothAdapterStateListenerList.remove(listener);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {

                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {

                    case BluetoothAdapter.STATE_OFF:
                        mBluetoothAdapterStateListenerList.forEach(
                                l -> l.onStateChange(STATE_OFF)
                        );
                        break;

                    case BluetoothAdapter.STATE_TURNING_OFF:
                        mBluetoothAdapterStateListenerList.forEach(
                                l -> l.onStateChange(STATE_TURNING_OFF)
                        );
                        break;

                    case BluetoothAdapter.STATE_ON:
                        mBluetoothAdapterStateListenerList.forEach(
                                l -> l.onStateChange(STATE_ON)
                        );
                        break;

                    case BluetoothAdapter.STATE_TURNING_ON:
                        mBluetoothAdapterStateListenerList.forEach(
                                l -> l.onStateChange(STATE_TURNING_ON)
                        );
                        break;

                    default:
                        LoggingUtil.warning("unhandled state: " + state);
                }
            }
        }
    };

    public class LocalBinder extends Binder {

        public GenericBleProtocolService getService() {
            return GenericBleProtocolService.this;
        }
    }

    public static class DeviceConnectionListener {

        public void onDeviceConnected() {}
        public void onDeviceDisconnected() {}

        public void onCharacteristicRead(UUID uuid, String value) {}
        public void onCharacteristicWrote(UUID uuid, String value) {}
        public void onAllCharacteristicsRead(Map<UUID, String> characteristicMap) {}
        public void onAllCharacteristicsWrote() {}

        public void onDeviceConnectionError(int errorCode) {}
    }

    public static abstract class ScanListener {
        public abstract void onScanResult(ScanResult scanResult);
    }

    public static abstract class BluetoothPreconditionStateListener {
        public abstract void onStateChange(int state);
    }

    public static abstract class BluetoothAdapterStateListener {
        public abstract void onStateChange(int state);
    }
}

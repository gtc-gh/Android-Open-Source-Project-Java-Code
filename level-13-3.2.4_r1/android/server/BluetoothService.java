/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * TODO: Move this to
 * java/services/com/android/server/BluetoothService.java
 * and make the contructor package private again.
 *
 * @hide
 */

package android.server;

import com.android.internal.app.IBatteryStats;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDeviceProfileState;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothInputDevice;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfileState;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BluetoothService extends IBluetooth.Stub {
    private static final String TAG = "BluetoothService";
    private static final boolean DBG = true;

    private int mNativeData;
    private BluetoothEventLoop mEventLoop;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothInputDevice mInputDevice;
    private BluetoothPan mPan;
    private boolean mIsAirplaneSensitive;
    private boolean mIsAirplaneToggleable;
    private int mBluetoothState;
    private boolean mRestart = false;  // need to call enable() after disable()
    private boolean mIsDiscovering;
    private int[] mAdapterSdpHandles;
    private ParcelUuid[] mAdapterUuids;

    private BluetoothAdapter mAdapter;  // constant after init()
    private final BluetoothBondState mBondState;  // local cache of bondings
    private final IBatteryStats mBatteryStats;
    private final Context mContext;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String DOCK_ADDRESS_PATH = "/sys/class/switch/dock/bt_addr";
    private static final String DOCK_PIN_PATH = "/sys/class/switch/dock/bt_pin";

    private static final String SHARED_PREFERENCE_DOCK_ADDRESS = "dock_bluetooth_address";
    private static final String SHARED_PREFERENCES_NAME = "bluetooth_service_settings";

    private static final int MESSAGE_FINISH_DISABLE = 1;
    private static final int MESSAGE_UUID_INTENT = 2;
    private static final int MESSAGE_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 3;

    // The time (in millisecs) to delay the pairing attempt after the first
    // auto pairing attempt fails. We use an exponential delay with
    // INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY as the initial value and
    // MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY as the max value.
    private static final long INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 3000;
    private static final long MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 12000;

    // The timeout used to sent the UUIDs Intent
    // This timeout should be greater than the page timeout
    private static final int UUID_INTENT_DELAY = 6000;

    /** Always retrieve RFCOMM channel for these SDP UUIDs */
    private static final ParcelUuid[] RFCOMM_UUIDS = {
            BluetoothUuid.Handsfree,
            BluetoothUuid.HSP,
            BluetoothUuid.ObexObjectPush };

    private final BluetoothAdapterProperties mAdapterProperties;
    private final BluetoothDeviceProperties mDeviceProperties;

    private final HashMap<String, Map<ParcelUuid, Integer>> mDeviceServiceChannelCache;
    private final ArrayList<String> mUuidIntentTracker;
    private final HashMap<RemoteService, IBluetoothCallback> mUuidCallbackTracker;

    private final HashMap<Integer, Integer> mServiceRecordToPid;

    private final HashMap<String, BluetoothDeviceProfileState> mDeviceProfileState;
    private final BluetoothProfileState mA2dpProfileState;
    private final BluetoothProfileState mHfpProfileState;

    private BluetoothA2dpService mA2dpService;
    private final HashMap<String, Pair<byte[], byte[]>> mDeviceOobData;

    private int mProfilesConnected = 0, mProfilesConnecting = 0, mProfilesDisconnecting = 0;

    private static String mDockAddress;
    private String mDockPin;

    private int mAdapterConnectionState = BluetoothAdapter.STATE_DISCONNECTED;
    private BluetoothPanProfileHandler mBluetoothPanProfileHandler;
    private BluetoothInputProfileHandler mBluetoothInputProfileHandler;

    private static class RemoteService {
        public String address;
        public ParcelUuid uuid;
        public RemoteService(String address, ParcelUuid uuid) {
            this.address = address;
            this.uuid = uuid;
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof RemoteService) {
                RemoteService service = (RemoteService)o;
                return address.equals(service.address) && uuid.equals(service.uuid);
            }
            return false;
        }

        @Override
        public int hashCode() {
            int hash = 1;
            hash = hash * 31 + (address == null ? 0 : address.hashCode());
            hash = hash * 31 + (uuid == null ? 0 : uuid.hashCode());
            return hash;
        }
    }

    static {
        classInitNative();
    }

    public BluetoothService(Context context) {
        mContext = context;

        // Need to do this in place of:
        // mBatteryStats = BatteryStatsService.getService();
        // Since we can not import BatteryStatsService from here. This class really needs to be
        // moved to java/services/com/android/server/
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batteryinfo"));

        initializeNativeDataNative();

        if (isEnabledNative() == 1) {
            Log.w(TAG, "Bluetooth daemons already running - runtime restart? ");
            disableNative();
        }

        mBluetoothState = BluetoothAdapter.STATE_OFF;
        mIsDiscovering = false;

        mBondState = new BluetoothBondState(context, this);
        mAdapterProperties = new BluetoothAdapterProperties(context, this);
        mDeviceProperties = new BluetoothDeviceProperties(this);

        mDeviceServiceChannelCache = new HashMap<String, Map<ParcelUuid, Integer>>();
        mDeviceOobData = new HashMap<String, Pair<byte[], byte[]>>();
        mUuidIntentTracker = new ArrayList<String>();
        mUuidCallbackTracker = new HashMap<RemoteService, IBluetoothCallback>();
        mServiceRecordToPid = new HashMap<Integer, Integer>();
        mDeviceProfileState = new HashMap<String, BluetoothDeviceProfileState>();
        mA2dpProfileState = new BluetoothProfileState(mContext, BluetoothProfileState.A2DP);
        mHfpProfileState = new BluetoothProfileState(mContext, BluetoothProfileState.HFP);

        mHfpProfileState.start();
        mA2dpProfileState.start();

        IntentFilter filter = new IntentFilter();
        registerForAirplaneMode(filter);

        filter.addAction(Intent.ACTION_DOCK_EVENT);
        mContext.registerReceiver(mReceiver, filter);
        mBluetoothInputProfileHandler = BluetoothInputProfileHandler.getInstance(mContext, this);
        mBluetoothPanProfileHandler = BluetoothPanProfileHandler.getInstance(mContext, this);
    }

    public static synchronized String readDockBluetoothAddress() {
        if (mDockAddress != null) return mDockAddress;

        BufferedInputStream file = null;
        String dockAddress;
        try {
            file = new BufferedInputStream(new FileInputStream(DOCK_ADDRESS_PATH));
            byte[] address = new byte[17];
            file.read(address);
            dockAddress = new String(address);
            dockAddress = dockAddress.toUpperCase();
            if (BluetoothAdapter.checkBluetoothAddress(dockAddress)) {
                mDockAddress = dockAddress;
                return mDockAddress;
            } else {
                Log.e(TAG, "CheckBluetoothAddress failed for car dock address: "
                        + dockAddress);
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException while trying to read dock address");
        } catch (IOException e) {
            Log.e(TAG, "IOException while trying to read dock address");
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        mDockAddress = null;
        return null;
    }

    private synchronized boolean writeDockPin() {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(DOCK_PIN_PATH));

            // Generate a random 4 digit pin between 0000 and 9999
            // This is not truly random but good enough for our purposes.
            int pin = (int) Math.floor(Math.random() * 10000);

            mDockPin = String.format("%04d", pin);
            out.write(mDockPin);
            return true;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException while trying to write dock pairing pin");
        } catch (IOException e) {
            Log.e(TAG, "IOException while while trying to write dock pairing pin");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        mDockPin = null;
        return false;
    }

    /*package*/ synchronized String getDockPin() {
        return mDockPin;
    }

    public synchronized void initAfterRegistration() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mEventLoop = new BluetoothEventLoop(mContext, mAdapter, this);
    }

    public synchronized void initAfterA2dpRegistration() {
        mEventLoop.getProfileProxy();
    }

    @Override
    protected void finalize() throws Throwable {
        mContext.unregisterReceiver(mReceiver);
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }

    public boolean isEnabled() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return isEnabledInternal();
    }

    private boolean isEnabledInternal() {
        return mBluetoothState == BluetoothAdapter.STATE_ON;
    }

    public int getBluetoothState() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothState;
    }

    int getBluetoothStateInternal() {
        return mBluetoothState;
    }

    /**
     * Bring down bluetooth and disable BT in settings. Returns true on success.
     */
    public boolean disable() {
        return disable(true);
    }

    /**
     * Bring down bluetooth. Returns true on success.
     *
     * @param saveSetting If true, persist the new setting
     */
    public synchronized boolean disable(boolean saveSetting) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");

        switch (mBluetoothState) {
        case BluetoothAdapter.STATE_OFF:
            return true;
        case BluetoothAdapter.STATE_ON:
            break;
        default:
            return false;
        }
        if (mEnableThread != null && mEnableThread.isAlive()) {
            return false;
        }

        setBluetoothState(BluetoothAdapter.STATE_TURNING_OFF);

        if (mAdapterSdpHandles != null) removeReservedServiceRecordsNative(mAdapterSdpHandles);
        setBluetoothTetheringNative(false, BluetoothPanProfileHandler.NAP_ROLE,
                BluetoothPanProfileHandler.NAP_BRIDGE);

        // Allow 3 seconds for profiles to gracefully disconnect
        // TODO: Introduce a callback mechanism so that each profile can notify
        // BluetoothService when it is done shutting down
        disconnectDevices();

        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MESSAGE_FINISH_DISABLE, saveSetting ? 1 : 0, 0), 3000);
        return true;
    }

    private synchronized void disconnectDevices() {
        // Disconnect devices handled by BluetoothService.
        for (BluetoothDevice device: getConnectedInputDevices()) {
            disconnectInputDevice(device);
        }

        for (BluetoothDevice device: getConnectedPanDevices()) {
            disconnectPanDevice(device);
        }
    }

    private synchronized void finishDisable(boolean saveSetting) {
        if (mBluetoothState != BluetoothAdapter.STATE_TURNING_OFF) {
            return;
        }
        mEventLoop.stop();
        tearDownNativeDataNative();
        disableNative();

        // mark in progress bondings as cancelled
        for (String address : mBondState.listInState(BluetoothDevice.BOND_BONDING)) {
            mBondState.setBondState(address, BluetoothDevice.BOND_NONE,
                                    BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        }

        // Stop the profile state machine for bonded devices.
        for (String address : mBondState.listInState(BluetoothDevice.BOND_BONDED)) {
            removeProfileState(address);
        }

        // update mode
        Intent intent = new Intent(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_NONE);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);

        mIsDiscovering = false;
        mAdapterProperties.clear();
        mServiceRecordToPid.clear();

        mProfilesConnected = 0;
        mProfilesConnecting = 0;
        mProfilesDisconnecting = 0;
        mAdapterConnectionState = BluetoothAdapter.STATE_DISCONNECTED;
        mAdapterUuids = null;
        mAdapterSdpHandles = null;

        if (saveSetting) {
            persistBluetoothOnSetting(false);
        }

        setBluetoothState(BluetoothAdapter.STATE_OFF);

        // Log bluetooth off to battery stats.
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteBluetoothOff();
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (mRestart) {
            mRestart = false;
            enable();
        }
    }

    /** Bring up BT and persist BT on in settings */
    public boolean enable() {
        return enable(true);
    }

    /**
     * Enable this Bluetooth device, asynchronously.
     * This turns on/off the underlying hardware.
     *
     * @param saveSetting If true, persist the new state of BT in settings
     * @return True on success (so far)
     */
    public synchronized boolean enable(boolean saveSetting) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");

        // Airplane mode can prevent Bluetooth radio from being turned on.
        if (mIsAirplaneSensitive && isAirplaneModeOn() && !mIsAirplaneToggleable) {
            return false;
        }
        if (mBluetoothState != BluetoothAdapter.STATE_OFF) {
            return false;
        }
        if (mEnableThread != null && mEnableThread.isAlive()) {
            return false;
        }
        setBluetoothState(BluetoothAdapter.STATE_TURNING_ON);
        mEnableThread = new EnableThread(saveSetting);
        mEnableThread.start();
        return true;
    }

    /** Forcibly restart Bluetooth if it is on */
    /* package */ synchronized void restart() {
        if (mBluetoothState != BluetoothAdapter.STATE_ON) {
            return;
        }
        mRestart = true;
        if (!disable(false)) {
            mRestart = false;
        }
    }

    private synchronized void setBluetoothState(int state) {
        if (state == mBluetoothState) {
            return;
        }

        if (DBG) Log.d(TAG, "Bluetooth state " + mBluetoothState + " -> " + state);

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, mBluetoothState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, state);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        mBluetoothState = state;

        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_FINISH_DISABLE:
                finishDisable(msg.arg1 != 0);
                break;
            case MESSAGE_UUID_INTENT:
                String address = (String)msg.obj;
                if (address != null) {
                    sendUuidIntent(address);
                    makeServiceChannelCallbacks(address);
                }
                break;
            case MESSAGE_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY:
                address = (String)msg.obj;
                if (address != null) {
                    createBond(address);
                    return;
                }
                break;
            }
        }
    };

    private EnableThread mEnableThread;

    private class EnableThread extends Thread {
        private final boolean mSaveSetting;
        public EnableThread(boolean saveSetting) {
            mSaveSetting = saveSetting;
        }
        public void run() {
            boolean res = (enableNative() == 0);
            if (res) {
                int retryCount = 2;
                boolean running = false;
                while ((retryCount-- > 0) && !running) {
                    mEventLoop.start();
                    // it may take a moment for the other thread to do its
                    // thing.  Check periodically for a while.
                    int pollCount = 5;
                    while ((pollCount-- > 0) && !running) {
                        if (mEventLoop.isEventLoopRunning()) {
                            running = true;
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {}
                    }
                }
                if (!running) {
                    Log.e(TAG, "bt EnableThread giving up");
                    res = false;
                    disableNative();
                }
            }

            if (res) {
                if (!setupNativeDataNative()) {
                    return;
                }
                if (mSaveSetting) {
                    persistBluetoothOnSetting(true);
                }

                mIsDiscovering = false;
                mBondState.readAutoPairingData();
                mBondState.loadBondState();
                initProfileState();

                // This should be the last step of the the enable thread.
                // Because this adds SDP records which asynchronously
                // broadcasts the Bluetooth On State in updateBluetoothState.
                // So we want all internal state setup before this.
                updateSdpRecords();
            } else {
                setBluetoothState(BluetoothAdapter.STATE_OFF);
            }
            mEnableThread = null;
        }
    }

    private synchronized void addReservedSdpRecords(final ArrayList<ParcelUuid> uuids) {
        //Register SDP records.
        int[] svcIdentifiers = new int[uuids.size()];
        for (int i = 0; i < uuids.size(); i++) {
            svcIdentifiers[i] = BluetoothUuid.getServiceIdentifierFromParcelUuid(uuids.get(i));
        }
        mAdapterSdpHandles = addReservedServiceRecordsNative(svcIdentifiers);
    }

    private synchronized void updateSdpRecords() {
        ArrayList<ParcelUuid> uuids = new ArrayList<ParcelUuid>();

        // Add the default records
        uuids.add(BluetoothUuid.HSP_AG);
        uuids.add(BluetoothUuid.ObexObjectPush);

        if (mContext.getResources().
                getBoolean(com.android.internal.R.bool.config_voice_capable)) {
            uuids.add(BluetoothUuid.Handsfree_AG);
            uuids.add(BluetoothUuid.PBAP_PSE);
        }

        // Add SDP records for profiles maintained by Android userspace
        addReservedSdpRecords(uuids);

        // Enable profiles maintained by Bluez userspace.
        setBluetoothTetheringNative(true, BluetoothPanProfileHandler.NAP_ROLE,
                BluetoothPanProfileHandler.NAP_BRIDGE);

        // Add SDP records for profiles maintained by Bluez userspace
        uuids.add(BluetoothUuid.AudioSource);
        uuids.add(BluetoothUuid.AvrcpTarget);
        uuids.add(BluetoothUuid.NAP);

        // Cannot cast uuids.toArray directly since ParcelUuid is parcelable
        mAdapterUuids = new ParcelUuid[uuids.size()];
        for (int i = 0; i < uuids.size(); i++) {
            mAdapterUuids[i] = uuids.get(i);
        }
    }

    /**
     * This function is called from Bluetooth Event Loop when onPropertyChanged
     * for adapter comes in with UUID property.
     * @param uuidsThe uuids of adapter as reported by Bluez.
     */
    synchronized void updateBluetoothState(String uuids) {
        if (mBluetoothState == BluetoothAdapter.STATE_TURNING_ON) {
            ParcelUuid[] adapterUuids = convertStringToParcelUuid(uuids);

            if (mAdapterUuids != null &&
                    BluetoothUuid.containsAllUuids(adapterUuids, mAdapterUuids)) {
                setBluetoothState(BluetoothAdapter.STATE_ON);
                autoConnect();
                String[] propVal = {"Pairable", getProperty("Pairable")};
                mEventLoop.onPropertyChanged(propVal);

                // Log bluetooth on to battery stats.
                long ident = Binder.clearCallingIdentity();
                try {
                    mBatteryStats.noteBluetoothOn();
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }

                if (mIsAirplaneSensitive && isAirplaneModeOn() && !mIsAirplaneToggleable) {
                    disable(false);
                }
            }
        }
    }

    private void persistBluetoothOnSetting(boolean bluetoothOn) {
        long origCallerIdentityToken = Binder.clearCallingIdentity();
        Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.BLUETOOTH_ON,
                bluetoothOn ? 1 : 0);
        Binder.restoreCallingIdentity(origCallerIdentityToken);
    }

    /*package*/ synchronized boolean attemptAutoPair(String address) {
        if (!mBondState.hasAutoPairingFailed(address) &&
                !mBondState.isAutoPairingBlacklisted(address)) {
            mBondState.attempt(address);
            setPin(address, BluetoothDevice.convertPinToBytes("0000"));
            return true;
        }
        return false;
    }

    /*package*/ synchronized boolean isFixedPinZerosAutoPairKeyboard(String address) {
        // Check for keyboards which have fixed PIN 0000 as the pairing pin
        return mBondState.isFixedPinZerosAutoPairKeyboard(address);
    }

    /*package*/ synchronized void onCreatePairedDeviceResult(String address, int result) {
        if (result == BluetoothDevice.BOND_SUCCESS) {
            setBondState(address, BluetoothDevice.BOND_BONDED);
            if (mBondState.isAutoPairingAttemptsInProgress(address)) {
                mBondState.clearPinAttempts(address);
            }
        } else if (result == BluetoothDevice.UNBOND_REASON_AUTH_FAILED &&
                mBondState.getAttempt(address) == 1) {
            mBondState.addAutoPairingFailure(address);
            pairingAttempt(address, result);
        } else if (result == BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN &&
              mBondState.isAutoPairingAttemptsInProgress(address)) {
            pairingAttempt(address, result);
        } else {
            setBondState(address, BluetoothDevice.BOND_NONE, result);
            if (mBondState.isAutoPairingAttemptsInProgress(address)) {
                mBondState.clearPinAttempts(address);
            }
        }
    }

    /*package*/ synchronized String getPendingOutgoingBonding() {
        return mBondState.getPendingOutgoingBonding();
    }

    private void pairingAttempt(String address, int result) {
        // This happens when our initial guess of "0000" as the pass key
        // fails. Try to create the bond again and display the pin dialog
        // to the user. Use back-off while posting the delayed
        // message. The initial value is
        // INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY and the max value is
        // MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY. If the max value is
        // reached, display an error to the user.
        int attempt = mBondState.getAttempt(address);
        if (attempt * INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY >
                    MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY) {
            mBondState.clearPinAttempts(address);
            setBondState(address, BluetoothDevice.BOND_NONE, result);
            return;
        }

        Message message = mHandler.obtainMessage(MESSAGE_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY);
        message.obj = address;
        boolean postResult =  mHandler.sendMessageDelayed(message,
                                        attempt * INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY);
        if (!postResult) {
            mBondState.clearPinAttempts(address);
            setBondState(address,
                    BluetoothDevice.BOND_NONE, result);
            return;
        }
        mBondState.attempt(address);
    }

    /*package*/ BluetoothDevice getRemoteDevice(String address) {
        return mAdapter.getRemoteDevice(address);
    }

    private static String toBondStateString(int bondState) {
        switch (bondState) {
        case BluetoothDevice.BOND_NONE:
            return "not bonded";
        case BluetoothDevice.BOND_BONDING:
            return "bonding";
        case BluetoothDevice.BOND_BONDED:
            return "bonded";
        default:
            return "??????";
        }
    }

    public synchronized boolean setName(String name) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (name == null) {
            return false;
        }
        return setPropertyString("Name", name);
    }

    //TODO(): setPropertyString, setPropertyInteger, setPropertyBoolean
    // Either have a single property function with Object as the parameter
    // or have a function for each property and then obfuscate in the JNI layer.
    // The following looks dirty.
    private boolean setPropertyString(String key, String value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return false;
        return setAdapterPropertyStringNative(key, value);
    }

    private boolean setPropertyInteger(String key, int value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return false;
        return setAdapterPropertyIntegerNative(key, value);
    }

    private boolean setPropertyBoolean(String key, boolean value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return false;
        return setAdapterPropertyBooleanNative(key, value ? 1 : 0);
    }

    /**
     * Set the discoverability window for the device.  A timeout of zero
     * makes the device permanently discoverable (if the device is
     * discoverable).  Setting the timeout to a nonzero value does not make
     * a device discoverable; you need to call setMode() to make the device
     * explicitly discoverable.
     *
     * @param timeout The discoverable timeout in seconds.
     */
    public synchronized boolean setDiscoverableTimeout(int timeout) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return setPropertyInteger("DiscoverableTimeout", timeout);
    }

    public synchronized boolean setScanMode(int mode, int duration) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                                                "Need WRITE_SECURE_SETTINGS permission");
        boolean pairable;
        boolean discoverable;

        switch (mode) {
        case BluetoothAdapter.SCAN_MODE_NONE:
            pairable = false;
            discoverable = false;
            break;
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
            pairable = true;
            discoverable = false;
            break;
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
            pairable = true;
            discoverable = true;
            if (DBG) Log.d(TAG, "BT Discoverable for " + duration + " seconds");
            break;
        default:
            Log.w(TAG, "Requested invalid scan mode " + mode);
            return false;
        }
        setPropertyBoolean("Pairable", pairable);
        setPropertyBoolean("Discoverable", discoverable);

        return true;
    }

    /*package*/ synchronized String getProperty(String name) {
        if (!isEnabledInternal()) return null;
        return mAdapterProperties.getProperty(name);
    }

    BluetoothAdapterProperties getAdapterProperties() {
        return mAdapterProperties;
    }

    BluetoothDeviceProperties getDeviceProperties() {
        return mDeviceProperties;
    }

    boolean isRemoteDeviceInCache(String address) {
        return mDeviceProperties.isInCache(address);
    }

    void setRemoteDeviceProperty(String address, String name, String value) {
        mDeviceProperties.setProperty(address, name, value);
    }

    void updateRemoteDevicePropertiesCache(String address) {
        mDeviceProperties.updateCache(address);
    }

    public synchronized String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getProperty("Address");
    }

    public synchronized String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getProperty("Name");
    }

    public synchronized ParcelUuid[] getUuids() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        String value =  getProperty("UUIDs");
        if (value == null) return null;
        return convertStringToParcelUuid(value);
    }

    private synchronized ParcelUuid[] convertStringToParcelUuid(String value) {
        String[] uuidStrings = null;
        // The UUIDs are stored as a "," separated string.
        uuidStrings = value.split(",");
        ParcelUuid[] uuids = new ParcelUuid[uuidStrings.length];

        for (int i = 0; i < uuidStrings.length; i++) {
            uuids[i] = ParcelUuid.fromString(uuidStrings[i]);
        }
        return uuids;
    }


    /**
     * Returns the user-friendly name of a remote device.  This value is
     * returned from our local cache, which is updated when onPropertyChange
     * event is received.
     * Do not expect to retrieve the updated remote name immediately after
     * changing the name on the remote device.
     *
     * @param address Bluetooth address of remote device.
     *
     * @return The user-friendly name of the specified remote device.
     */
    public synchronized String getRemoteName(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return null;
        }
        return mDeviceProperties.getProperty(address, "Name");
    }

    /**
     * Get the discoverability window for the device.  A timeout of zero
     * means that the device is permanently discoverable (if the device is
     * in the discoverable mode).
     *
     * @return The discoverability window of the device, in seconds.  A negative
     *         value indicates an error.
     */
    public synchronized int getDiscoverableTimeout() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        String timeout = getProperty("DiscoverableTimeout");
        if (timeout != null)
           return Integer.valueOf(timeout);
        else
            return -1;
    }

    public synchronized int getScanMode() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal())
            return BluetoothAdapter.SCAN_MODE_NONE;

        boolean pairable = getProperty("Pairable").equals("true");
        boolean discoverable = getProperty("Discoverable").equals("true");
        return bluezStringToScanMode (pairable, discoverable);
    }

    public synchronized boolean startDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        return startDiscoveryNative();
    }

    public synchronized boolean cancelDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        return stopDiscoveryNative();
    }

    public synchronized boolean isDiscovering() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mIsDiscovering;
    }

    /* package */ void setIsDiscovering(boolean isDiscovering) {
        mIsDiscovering = isDiscovering;
    }

    private boolean isBondingFeasible(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();

        if (mBondState.getPendingOutgoingBonding() != null) {
            Log.d(TAG, "Ignoring createBond(): another device is bonding");
            // a different device is currently bonding, fail
            return false;
        }

        // Check for bond state only if we are not performing auto
        // pairing exponential back-off attempts.
        if (!mBondState.isAutoPairingAttemptsInProgress(address) &&
                mBondState.getBondState(address) != BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "Ignoring createBond(): this device is already bonding or bonded");
            return false;
        }

        if (address.equals(mDockAddress)) {
            if (!writeDockPin()) {
                Log.e(TAG, "Error while writing Pin for the dock");
                return false;
            }
        }
        return true;
    }

    public synchronized boolean createBond(String address) {
        if (!isBondingFeasible(address)) return false;

        if (!createPairedDeviceNative(address, 60000  /*1 minute*/ )) {
            return false;
        }

        mBondState.setPendingOutgoingBonding(address);
        mBondState.setBondState(address, BluetoothDevice.BOND_BONDING);

        return true;
    }

    public synchronized boolean createBondOutOfBand(String address, byte[] hash,
                                                    byte[] randomizer) {
        if (!isBondingFeasible(address)) return false;

        if (!createPairedDeviceOutOfBandNative(address, 60000 /* 1 minute */)) {
            return false;
        }

        setDeviceOutOfBandData(address, hash, randomizer);
        mBondState.setPendingOutgoingBonding(address);
        mBondState.setBondState(address, BluetoothDevice.BOND_BONDING);

        return true;
    }

    public synchronized boolean setDeviceOutOfBandData(String address, byte[] hash,
            byte[] randomizer) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        Pair <byte[], byte[]> value = new Pair<byte[], byte[]>(hash, randomizer);

        if (DBG) {
            Log.d(TAG, "Setting out of band data for: " + address + ":" +
                  Arrays.toString(hash) + ":" + Arrays.toString(randomizer));
        }

        mDeviceOobData.put(address, value);
        return true;
    }

    Pair<byte[], byte[]> getDeviceOutOfBandData(BluetoothDevice device) {
        return mDeviceOobData.get(device.getAddress());
    }


    public synchronized byte[] readOutOfBandData() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return null;

        return readAdapterOutOfBandDataNative();
    }

    public synchronized boolean cancelBondProcess(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();
        if (mBondState.getBondState(address) != BluetoothDevice.BOND_BONDING) {
            return false;
        }

        mBondState.setBondState(address, BluetoothDevice.BOND_NONE,
                                BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        cancelDeviceCreationNative(address);
        return true;
    }

    public synchronized boolean removeBond(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            state.sendMessage(BluetoothDeviceProfileState.UNPAIR);
            return true;
        } else {
            return false;
        }
    }

    public synchronized boolean removeBondInternal(String address) {
        // Unset the trusted device state and then unpair
        setTrust(address, false);
        return removeDeviceNative(getObjectPathFromAddress(address));
    }

    public synchronized String[] listBonds() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBondState.listInState(BluetoothDevice.BOND_BONDED);
    }

    /*package*/ synchronized String[] listInState(int state) {
      return mBondState.listInState(state);
    }

    public synchronized int getBondState(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return BluetoothDevice.ERROR;
        }
        return mBondState.getBondState(address.toUpperCase());
    }

    /*package*/ synchronized boolean setBondState(String address, int state) {
        return setBondState(address, state, 0);
    }

    /*package*/ synchronized boolean setBondState(String address, int state, int reason) {
        mBondState.setBondState(address.toUpperCase(), state);
        return true;
    }

    public synchronized boolean isBluetoothDock(String address) {
        SharedPreferences sp = mContext.getSharedPreferences(SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);

        return sp.contains(SHARED_PREFERENCE_DOCK_ADDRESS + address);
    }

    /*package*/ String[] getRemoteDeviceProperties(String address) {
        if (!isEnabledInternal()) return null;

        String objectPath = getObjectPathFromAddress(address);
        return (String [])getDevicePropertiesNative(objectPath);
    }

    /**
     * Sets the remote device trust state.
     *
     * @return boolean to indicate operation success or fail
     */
    public synchronized boolean setTrust(String address, boolean value) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH_ADMIN permission");
            return false;
        }

        if (!isEnabledInternal()) return false;

        return setDevicePropertyBooleanNative(
                getObjectPathFromAddress(address), "Trusted", value ? 1 : 0);
    }

    /**
     * Gets the remote device trust state as boolean.
     * Note: this value may be
     * retrieved from cache if we retrieved the data before *
     *
     * @return boolean to indicate trusted or untrusted state
     */
    public synchronized boolean getTrustState(String address) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return false;
        }

        String val = mDeviceProperties.getProperty(address, "Trusted");
        if (val == null) {
            return false;
        } else {
            return val.equals("true");
        }
    }

    /**
     * Gets the remote major, minor classes encoded as a 32-bit
     * integer.
     *
     * Note: this value is retrieved from cache, because we get it during
     *       remote-device discovery.
     *
     * @return 32-bit integer encoding the remote major, minor, and service
     *         classes.
     */
    public synchronized int getRemoteClass(String address) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return BluetoothClass.ERROR;
        }
        String val = mDeviceProperties.getProperty(address, "Class");
        if (val == null)
            return BluetoothClass.ERROR;
        else {
            return Integer.valueOf(val);
        }
    }


    /**
     * Gets the UUIDs supported by the remote device
     *
     * @return array of 128bit ParcelUuids
     */
    public synchronized ParcelUuid[] getRemoteUuids(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return null;
        }
        return getUuidFromCache(address);
    }

    ParcelUuid[] getUuidFromCache(String address) {
        String value = mDeviceProperties.getProperty(address, "UUIDs");
        if (value == null) return null;

        String[] uuidStrings = null;
        // The UUIDs are stored as a "," separated string.
        uuidStrings = value.split(",");
        ParcelUuid[] uuids = new ParcelUuid[uuidStrings.length];

        for (int i = 0; i < uuidStrings.length; i++) {
            uuids[i] = ParcelUuid.fromString(uuidStrings[i]);
        }
        return uuids;
    }

    /**
     * Connect and fetch new UUID's using SDP.
     * The UUID's found are broadcast as intents.
     * Optionally takes a uuid and callback to fetch the RFCOMM channel for the
     * a given uuid.
     * TODO: Don't wait UUID_INTENT_DELAY to broadcast UUID intents on success
     * TODO: Don't wait UUID_INTENT_DELAY to handle the failure case for
     * callback and broadcast intents.
     */
    public synchronized boolean fetchRemoteUuids(String address, ParcelUuid uuid,
            IBluetoothCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }

        RemoteService service = new RemoteService(address, uuid);
        if (uuid != null && mUuidCallbackTracker.get(service) != null) {
            // An SDP query for this address & uuid is already in progress
            // Do not add this callback for the uuid
            return false;
        }

        if (mUuidIntentTracker.contains(address)) {
            // An SDP query for this address is already in progress
            // Add this uuid onto the in-progress SDP query
            if (uuid != null) {
                mUuidCallbackTracker.put(new RemoteService(address, uuid), callback);
            }
            return true;
        }

        boolean ret;
        // Just do the SDP if the device is already  created and UUIDs are not
        // NULL, else create the device and then do SDP.
        if (mDeviceProperties.isInCache(address) && getRemoteUuids(address) != null) {
            String path = getObjectPathFromAddress(address);
            if (path == null) return false;

            // Use an empty string for the UUID pattern
            ret = discoverServicesNative(path, "");
        } else {
            ret = createDeviceNative(address);
        }

        mUuidIntentTracker.add(address);
        if (uuid != null) {
            mUuidCallbackTracker.put(new RemoteService(address, uuid), callback);
        }

        Message message = mHandler.obtainMessage(MESSAGE_UUID_INTENT);
        message.obj = address;
        mHandler.sendMessageDelayed(message, UUID_INTENT_DELAY);
        return ret;
    }

    /**
     * Gets the rfcomm channel associated with the UUID.
     * Pulls records from the cache only.
     *
     * @param address Address of the remote device
     * @param uuid ParcelUuid of the service attribute
     *
     * @return rfcomm channel associated with the service attribute
     *         -1 on error
     */
    public int getRemoteServiceChannel(String address, ParcelUuid uuid) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return -1;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return BluetoothDevice.ERROR;
        }
        // Check if we are recovering from a crash.
        if (mDeviceProperties.isEmpty()) {
            if (mDeviceProperties.updateCache(address) == null)
                return -1;
        }

        Map<ParcelUuid, Integer> value = mDeviceServiceChannelCache.get(address);
        if (value != null && value.containsKey(uuid))
            return value.get(uuid);
        return -1;
    }

    public synchronized boolean setPin(String address, byte[] pin) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (pin == null || pin.length <= 0 || pin.length > 16 ||
            !BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setPin(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }
        // bluez API wants pin as a string
        String pinString;
        try {
            pinString = new String(pin, "UTF8");
        } catch (UnsupportedEncodingException uee) {
            Log.e(TAG, "UTF8 not supported?!?");
            return false;
        }
        return setPinNative(address, pinString, data.intValue());
    }

    public synchronized boolean setPasskey(String address, int passkey) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (passkey < 0 || passkey > 999999 || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setPasskey(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }
        return setPasskeyNative(address, passkey, data.intValue());
    }

    public synchronized boolean setPairingConfirmation(String address, boolean confirm) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setPasskey(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }
        return setPairingConfirmationNative(address, confirm, data.intValue());
    }

    public synchronized boolean setRemoteOutOfBandData(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setRemoteOobData(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }

        Pair<byte[], byte[]> val = mDeviceOobData.get(address);
        byte[] hash, randomizer;
        if (val == null) {
            // TODO: check what should be passed in this case.
            hash = new byte[16];
            randomizer = new byte[16];
        } else {
            hash = val.first;
            randomizer = val.second;
        }
        return setRemoteOutOfBandDataNative(address, hash, randomizer, data.intValue());
    }

    public synchronized boolean cancelPairingUserInput(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        mBondState.setBondState(address, BluetoothDevice.BOND_NONE,
                BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "cancelUserInputNative(" + address + ") called but no native data " +
                "available, ignoring. Maybe the PasskeyAgent Request was already cancelled " +
                "by the remote or by bluez.\n");
            return false;
        }
        return cancelPairingUserInputNative(address, data.intValue());
    }

    /*package*/ void updateDeviceServiceChannelCache(String address) {
        if (DBG) Log.d(TAG, "updateDeviceServiceChannelCache(" + address + ")");

        // We are storing the rfcomm channel numbers only for the uuids
        // we are interested in.
        ParcelUuid[] deviceUuids = getRemoteUuids(address);

        ArrayList<ParcelUuid> applicationUuids = new ArrayList<ParcelUuid>();

        synchronized (this) {
            for (RemoteService service : mUuidCallbackTracker.keySet()) {
                if (service.address.equals(address)) {
                    applicationUuids.add(service.uuid);
                }
            }
        }

        Map <ParcelUuid, Integer> uuidToChannelMap = new HashMap<ParcelUuid, Integer>();

        // Retrieve RFCOMM channel for default uuids
        for (ParcelUuid uuid : RFCOMM_UUIDS) {
            if (BluetoothUuid.isUuidPresent(deviceUuids, uuid)) {
                int channel = getDeviceServiceChannelForUuid(address, uuid);
                uuidToChannelMap.put(uuid, channel);
                if (DBG) Log.d(TAG, "\tuuid(system): " + uuid + " " + channel);
            }
        }
        // Retrieve RFCOMM channel for application requested uuids
        for (ParcelUuid uuid : applicationUuids) {
            if (BluetoothUuid.isUuidPresent(deviceUuids, uuid)) {
                int channel = getDeviceServiceChannelForUuid(address, uuid);
                uuidToChannelMap.put(uuid, channel);
                if (DBG) Log.d(TAG, "\tuuid(application): " + uuid + " " + channel);
            }
        }

        synchronized (this) {
            // Make application callbacks
            for (Iterator<RemoteService> iter = mUuidCallbackTracker.keySet().iterator();
                    iter.hasNext();) {
                RemoteService service = iter.next();
                if (service.address.equals(address)) {
                    if (uuidToChannelMap.containsKey(service.uuid)) {
                        int channel = uuidToChannelMap.get(service.uuid);

                        if (DBG) Log.d(TAG, "Making callback for " + service.uuid +
                                    " with result " + channel);
                        IBluetoothCallback callback = mUuidCallbackTracker.get(service);
                        if (callback != null) {
                            try {
                                callback.onRfcommChannelFound(channel);
                            } catch (RemoteException e) {Log.e(TAG, "", e);}
                        }

                        iter.remove();
                    }
                }
            }

            // Update cache
            mDeviceServiceChannelCache.put(address, uuidToChannelMap);
        }
    }

    private int getDeviceServiceChannelForUuid(String address,
            ParcelUuid uuid) {
        return getDeviceServiceChannelNative(getObjectPathFromAddress(address),
                uuid.toString(), 0x0004);
    }

    /**
     * b is a handle to a Binder instance, so that this service can be notified
     * for Applications that terminate unexpectedly, to clean there service
     * records
     */
    public synchronized int addRfcommServiceRecord(String serviceName, ParcelUuid uuid,
            int channel, IBinder b) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return -1;

        if (serviceName == null || uuid == null || channel < 1 ||
                channel > BluetoothSocket.MAX_RFCOMM_CHANNEL) {
            return -1;
        }
        if (BluetoothUuid.isUuidPresent(BluetoothUuid.RESERVED_UUIDS, uuid)) {
            Log.w(TAG, "Attempted to register a reserved UUID: " + uuid);
            return -1;
        }
        int handle = addRfcommServiceRecordNative(serviceName,
                uuid.getUuid().getMostSignificantBits(), uuid.getUuid().getLeastSignificantBits(),
                (short)channel);
        if (DBG) Log.d(TAG, "new handle " + Integer.toHexString(handle));
        if (handle == -1) {
            return -1;
        }

        int pid = Binder.getCallingPid();
        mServiceRecordToPid.put(new Integer(handle), new Integer(pid));
        try {
            b.linkToDeath(new Reaper(handle, pid), 0);
        } catch (RemoteException e) {}
        return handle;
    }

    public void removeServiceRecord(int handle) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        checkAndRemoveRecord(handle, Binder.getCallingPid());
    }

    private synchronized void checkAndRemoveRecord(int handle, int pid) {
        Integer handleInt = new Integer(handle);
        Integer owner = mServiceRecordToPid.get(handleInt);
        if (owner != null && pid == owner.intValue()) {
            if (DBG) Log.d(TAG, "Removing service record " +
                Integer.toHexString(handle) + " for pid " + pid);
            mServiceRecordToPid.remove(handleInt);
            removeServiceRecordNative(handle);
        }
    }

    private class Reaper implements IBinder.DeathRecipient {
        int pid;
        int handle;
        Reaper(int handle, int pid) {
            this.pid = pid;
            this.handle = handle;
        }
        public void binderDied() {
            synchronized (BluetoothService.this) {
                if (DBG) Log.d(TAG, "Tracked app " + pid + " died");
                checkAndRemoveRecord(handle, pid);
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                ContentResolver resolver = context.getContentResolver();
                // Query the airplane mode from Settings.System just to make sure that
                // some random app is not sending this intent and disabling bluetooth
                boolean enabled = !isAirplaneModeOn();
                // If bluetooth is currently expected to be on, then enable or disable bluetooth
                if (Settings.Secure.getInt(resolver, Settings.Secure.BLUETOOTH_ON, 0) > 0) {
                    if (enabled) {
                        enable(false);
                    } else {
                        disable(false);
                    }
                }
            } else if (Intent.ACTION_DOCK_EVENT.equals(action)) {
                int state = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (DBG) Log.v(TAG, "Received ACTION_DOCK_EVENT with State:" + state);
                if (state == Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                    mDockAddress = null;
                    mDockPin = null;
                } else {
                    SharedPreferences.Editor editor =
                        mContext.getSharedPreferences(SHARED_PREFERENCES_NAME,
                                mContext.MODE_PRIVATE).edit();
                    editor.putBoolean(SHARED_PREFERENCE_DOCK_ADDRESS + mDockAddress, true);
                    editor.apply();
                }
            }
        }
    };

    private void registerForAirplaneMode(IntentFilter filter) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String airplaneModeRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_RADIOS);
        final String toggleableRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS);

        mIsAirplaneSensitive = airplaneModeRadios == null ? true :
                airplaneModeRadios.contains(Settings.System.RADIO_BLUETOOTH);
        mIsAirplaneToggleable = toggleableRadios == null ? false :
                toggleableRadios.contains(Settings.System.RADIO_BLUETOOTH);

        if (mIsAirplaneSensitive) {
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        }
    }

    /* Returns true if airplane mode is currently on */
    private final boolean isAirplaneModeOn() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    /* Broadcast the Uuid intent */
    /*package*/ synchronized void sendUuidIntent(String address) {
        ParcelUuid[] uuid = getUuidFromCache(address);
        Intent intent = new Intent(BluetoothDevice.ACTION_UUID);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mAdapter.getRemoteDevice(address));
        intent.putExtra(BluetoothDevice.EXTRA_UUID, uuid);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
        mUuidIntentTracker.remove(address);
    }

    /*package*/ synchronized void makeServiceChannelCallbacks(String address) {
        for (Iterator<RemoteService> iter = mUuidCallbackTracker.keySet().iterator();
                iter.hasNext();) {
            RemoteService service = iter.next();
            if (service.address.equals(address)) {
                if (DBG) Log.d(TAG, "Cleaning up failed UUID channel lookup: "
                    + service.address + " " + service.uuid);
                IBluetoothCallback callback = mUuidCallbackTracker.get(service);
                if (callback != null) {
                    try {
                        callback.onRfcommChannelFound(-1);
                    } catch (RemoteException e) {Log.e(TAG, "", e);}
                }

                iter.remove();
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        dumpBluetoothState(pw);
        if (mBluetoothState != BluetoothAdapter.STATE_ON) {
            return;
        }

        pw.println("mIsAirplaneSensitive = " + mIsAirplaneSensitive);
        pw.println("mIsAirplaneToggleable = " + mIsAirplaneToggleable);

        pw.println("Local address = " + getAddress());
        pw.println("Local name = " + getName());
        pw.println("isDiscovering() = " + isDiscovering());

        mAdapter.getProfileProxy(mContext,
                                 mBluetoothProfileServiceListener, BluetoothProfile.HEADSET);
        mAdapter.getProfileProxy(mContext,
                mBluetoothProfileServiceListener, BluetoothProfile.INPUT_DEVICE);
        mAdapter.getProfileProxy(mContext,
                mBluetoothProfileServiceListener, BluetoothProfile.PAN);

        dumpKnownDevices(pw);
        dumpAclConnectedDevices(pw);
        dumpHeadsetService(pw);
        dumpInputDeviceProfile(pw);
        dumpPanProfile(pw);
        dumpApplicationServiceRecords(pw);
    }

    private void dumpHeadsetService(PrintWriter pw) {
        pw.println("\n--Headset Service--");
        if (mBluetoothHeadset != null) {
            List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();
            if (deviceList.size() == 0) {
                pw.println("No headsets connected");
            } else {
                BluetoothDevice device = deviceList.get(0);
                pw.println("\ngetConnectedDevices[0] = " + device);
                dumpHeadsetConnectionState(pw, device);
                pw.println("getBatteryUsageHint() = " +
                             mBluetoothHeadset.getBatteryUsageHint(device));
            }

            deviceList.clear();
            deviceList = mBluetoothHeadset.getDevicesMatchingConnectionStates(new int[] {
                     BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED});
            pw.println("--Connected and Disconnected Headsets");
            for (BluetoothDevice device: deviceList) {
                pw.println(device);
                if (mBluetoothHeadset.isAudioConnected(device)) {
                    pw.println("SCO audio connected to device:" + device);
                }
            }
        }
        mAdapter.closeProfileProxy(BluetoothProfile.HEADSET, mBluetoothHeadset);
    }

    private void dumpInputDeviceProfile(PrintWriter pw) {
        pw.println("\n--Bluetooth Service- Input Device Profile");
        if (mInputDevice != null) {
            List<BluetoothDevice> deviceList = mInputDevice.getConnectedDevices();
            if (deviceList.size() == 0) {
                pw.println("No input devices connected");
            } else {
                pw.println("Number of connected devices:" + deviceList.size());
                BluetoothDevice device = deviceList.get(0);
                pw.println("getConnectedDevices[0] = " + device);
                pw.println("Priority of Connected device = " + mInputDevice.getPriority(device));

                switch (mInputDevice.getConnectionState(device)) {
                    case BluetoothInputDevice.STATE_CONNECTING:
                        pw.println("getConnectionState() = STATE_CONNECTING");
                        break;
                    case BluetoothInputDevice.STATE_CONNECTED:
                        pw.println("getConnectionState() = STATE_CONNECTED");
                        break;
                    case BluetoothInputDevice.STATE_DISCONNECTING:
                        pw.println("getConnectionState() = STATE_DISCONNECTING");
                        break;
                }
            }
            deviceList.clear();
            deviceList = mInputDevice.getDevicesMatchingConnectionStates(new int[] {
                     BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED});
            pw.println("--Connected and Disconnected input devices");
            for (BluetoothDevice device: deviceList) {
                pw.println(device);
            }
        }
        mAdapter.closeProfileProxy(BluetoothProfile.INPUT_DEVICE, mBluetoothHeadset);
    }

    private void dumpPanProfile(PrintWriter pw) {
        pw.println("\n--Bluetooth Service- Pan Profile");
        if (mPan != null) {
            List<BluetoothDevice> deviceList = mPan.getConnectedDevices();
            if (deviceList.size() == 0) {
                pw.println("No Pan devices connected");
            } else {
                pw.println("Number of connected devices:" + deviceList.size());
                BluetoothDevice device = deviceList.get(0);
                pw.println("getConnectedDevices[0] = " + device);
                pw.println("Priority of Connected device = " + mPan.getPriority(device));

                switch (mPan.getConnectionState(device)) {
                    case BluetoothInputDevice.STATE_CONNECTING:
                        pw.println("getConnectionState() = STATE_CONNECTING");
                        break;
                    case BluetoothInputDevice.STATE_CONNECTED:
                        pw.println("getConnectionState() = STATE_CONNECTED");
                        break;
                    case BluetoothInputDevice.STATE_DISCONNECTING:
                        pw.println("getConnectionState() = STATE_DISCONNECTING");
                        break;
                }
            }
            deviceList.clear();
            deviceList = mPan.getDevicesMatchingConnectionStates(new int[] {
                     BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED});
            pw.println("--Connected and Disconnected Pan devices");
            for (BluetoothDevice device: deviceList) {
                pw.println(device);
            }
        }
    }

    private void dumpHeadsetConnectionState(PrintWriter pw,
            BluetoothDevice device) {
        switch (mBluetoothHeadset.getConnectionState(device)) {
            case BluetoothHeadset.STATE_CONNECTING:
                pw.println("getConnectionState() = STATE_CONNECTING");
                break;
            case BluetoothHeadset.STATE_CONNECTED:
                pw.println("getConnectionState() = STATE_CONNECTED");
                break;
            case BluetoothHeadset.STATE_DISCONNECTING:
                pw.println("getConnectionState() = STATE_DISCONNECTING");
                break;
            case BluetoothHeadset.STATE_AUDIO_CONNECTED:
                pw.println("getConnectionState() = STATE_AUDIO_CONNECTED");
                break;
        }
    }

    private void dumpApplicationServiceRecords(PrintWriter pw) {
        pw.println("\n--Application Service Records--");
        for (Integer handle : mServiceRecordToPid.keySet()) {
            Integer pid = mServiceRecordToPid.get(handle);
            pw.println("\tpid " + pid + " handle " + Integer.toHexString(handle));
        }
        mAdapter.closeProfileProxy(BluetoothProfile.PAN, mBluetoothHeadset);
    }

    private void dumpAclConnectedDevices(PrintWriter pw) {
        String[] devicesObjectPath = getKnownDevices();
        pw.println("\n--ACL connected devices--");
        if (devicesObjectPath != null) {
            for (String device : devicesObjectPath) {
                pw.println(getAddressFromObjectPath(device));
            }
        }
    }

    private void dumpKnownDevices(PrintWriter pw) {
        pw.println("\n--Known devices--");
        for (String address : mDeviceProperties.keySet()) {
            int bondState = mBondState.getBondState(address);
            pw.printf("%s %10s (%d) %s\n", address,
                       toBondStateString(bondState),
                       mBondState.getAttempt(address),
                       getRemoteName(address));

            Map<ParcelUuid, Integer> uuidChannels = mDeviceServiceChannelCache.get(address);
            if (uuidChannels == null) {
                pw.println("\tuuids = null");
            } else {
                for (ParcelUuid uuid : uuidChannels.keySet()) {
                    Integer channel = uuidChannels.get(uuid);
                    if (channel == null) {
                        pw.println("\t" + uuid);
                    } else {
                        pw.println("\t" + uuid + " RFCOMM channel = " + channel);
                    }
                }
            }
            for (RemoteService service : mUuidCallbackTracker.keySet()) {
                if (service.address.equals(address)) {
                    pw.println("\tPENDING CALLBACK: " + service.uuid);
                }
            }
        }
    }

    private void dumpBluetoothState(PrintWriter pw) {
        switch(mBluetoothState) {
        case BluetoothAdapter.STATE_OFF:
            pw.println("Bluetooth OFF\n");
            break;
        case BluetoothAdapter.STATE_TURNING_ON:
            pw.println("Bluetooth TURNING ON\n");
            break;
        case BluetoothAdapter.STATE_TURNING_OFF:
            pw.println("Bluetooth TURNING OFF\n");
            break;
        case BluetoothAdapter.STATE_ON:
            pw.println("Bluetooth ON\n");
            break;
        default:
            pw.println("Bluetooth UNKNOWN STATE " + mBluetoothState);
        }
    }

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) {
                mBluetoothHeadset = (BluetoothHeadset) proxy;
            } else if (profile == BluetoothProfile.INPUT_DEVICE) {
                mInputDevice = (BluetoothInputDevice) proxy;
            } else if (profile == BluetoothProfile.PAN) {
                mPan = (BluetoothPan) proxy;
            }
        }
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) {
                mBluetoothHeadset = null;
            } else if (profile == BluetoothProfile.INPUT_DEVICE) {
                mInputDevice = null;
            } else if (profile == BluetoothProfile.PAN) {
                mPan = null;
            }
        }
    };

    /* package */ static int bluezStringToScanMode(boolean pairable, boolean discoverable) {
        if (pairable && discoverable)
            return BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        else if (pairable && !discoverable)
            return BluetoothAdapter.SCAN_MODE_CONNECTABLE;
        else
            return BluetoothAdapter.SCAN_MODE_NONE;
    }

    /* package */ static String scanModeToBluezString(int mode) {
        switch (mode) {
        case BluetoothAdapter.SCAN_MODE_NONE:
            return "off";
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
            return "connectable";
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
            return "discoverable";
        }
        return null;
    }

    /*package*/ String getAddressFromObjectPath(String objectPath) {
        String adapterObjectPath = mAdapterProperties.getObjectPath();
        if (adapterObjectPath == null || objectPath == null) {
            Log.e(TAG, "getAddressFromObjectPath: AdapterObjectPath:" + adapterObjectPath +
                    "  or deviceObjectPath:" + objectPath + " is null");
            return null;
        }
        if (!objectPath.startsWith(adapterObjectPath)) {
            Log.e(TAG, "getAddressFromObjectPath: AdapterObjectPath:" + adapterObjectPath +
                    "  is not a prefix of deviceObjectPath:" + objectPath +
                    "bluetoothd crashed ?");
            return null;
        }
        String address = objectPath.substring(adapterObjectPath.length());
        if (address != null) return address.replace('_', ':');

        Log.e(TAG, "getAddressFromObjectPath: Address being returned is null");
        return null;
    }

    /*package*/ String getObjectPathFromAddress(String address) {
        String path = mAdapterProperties.getObjectPath();
        if (path == null) {
            Log.e(TAG, "Error: Object Path is null");
            return null;
        }
        path = path + address.replace(":", "_");
        return path;
    }

    /*package */ void setLinkTimeout(String address, int num_slots) {
        String path = getObjectPathFromAddress(address);
        boolean result = setLinkTimeoutNative(path, num_slots);

        if (!result) Log.d(TAG, "Set Link Timeout to " + num_slots + " slots failed");
    }

    /**** Handlers for PAN  Profile ****/

    public synchronized boolean isTetheringOn() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothPanProfileHandler.isTetheringOn();
    }

    /*package*/ synchronized boolean allowIncomingTethering() {
        return mBluetoothPanProfileHandler.allowIncomingTethering();
    }

    public synchronized void setBluetoothTethering(boolean value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        mBluetoothPanProfileHandler.setBluetoothTethering(value);
    }

    public synchronized int getPanDeviceConnectionState(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothPanProfileHandler.getPanDeviceConnectionState(device);
    }

    public synchronized boolean connectPanDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
            "Need BLUETOOTH_ADMIN permission");
        return mBluetoothPanProfileHandler.connectPanDevice(device);
    }

    public synchronized List<BluetoothDevice> getConnectedPanDevices() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothPanProfileHandler.getConnectedPanDevices();
    }

    public synchronized List<BluetoothDevice> getPanDevicesMatchingConnectionStates(
            int[] states) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothPanProfileHandler.getPanDevicesMatchingConnectionStates(states);
    }

    public synchronized boolean disconnectPanDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
            "Need BLUETOOTH_ADMIN permission");
        return mBluetoothPanProfileHandler.disconnectPanDevice(device);
    }

    /*package*/ synchronized void handlePanDeviceStateChange(BluetoothDevice device,
                                                             String iface,
                                                             int state,
                                                             int role) {
        mBluetoothPanProfileHandler.handlePanDeviceStateChange(device, iface, state, role);
    }

    /*package*/ synchronized void handlePanDeviceStateChange(BluetoothDevice device,
                                                             int state, int role) {
        mBluetoothPanProfileHandler.handlePanDeviceStateChange(device, null, state, role);
    }

    /**** Handlers for Input Device Profile ****/

    public synchronized boolean connectInputDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        BluetoothDeviceProfileState state = mDeviceProfileState.get(device.getAddress());
        return mBluetoothInputProfileHandler.connectInputDevice(device, state);
    }

    public synchronized boolean connectInputDeviceInternal(BluetoothDevice device) {
        return mBluetoothInputProfileHandler.connectInputDeviceInternal(device);
    }

    public synchronized boolean disconnectInputDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        BluetoothDeviceProfileState state = mDeviceProfileState.get(device.getAddress());
        return mBluetoothInputProfileHandler.disconnectInputDevice(device, state);
    }

    public synchronized boolean disconnectInputDeviceInternal(BluetoothDevice device) {
        return mBluetoothInputProfileHandler.disconnectInputDeviceInternal(device);
    }

    public synchronized int getInputDeviceConnectionState(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothInputProfileHandler.getInputDeviceConnectionState(device);

    }

    public synchronized List<BluetoothDevice> getConnectedInputDevices() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothInputProfileHandler.getConnectedInputDevices();
    }

    public synchronized List<BluetoothDevice> getInputDevicesMatchingConnectionStates(
            int[] states) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothInputProfileHandler.getInputDevicesMatchingConnectionStates(states);
    }


    public synchronized int getInputDevicePriority(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothInputProfileHandler.getInputDevicePriority(device);
    }

    public synchronized boolean setInputDevicePriority(BluetoothDevice device, int priority) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return mBluetoothInputProfileHandler.setInputDevicePriority(device, priority);
    }

    /*package*/synchronized List<BluetoothDevice> lookupInputDevicesMatchingStates(int[] states) {
        return mBluetoothInputProfileHandler.lookupInputDevicesMatchingStates(states);
    }

    /*package*/ synchronized void handleInputDevicePropertyChange(String address, boolean connected) {
        mBluetoothInputProfileHandler.handleInputDevicePropertyChange(address, connected);
    }

    public boolean connectHeadset(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.CONNECT_HFP_OUTGOING;
            msg.obj = state;
            mHfpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    public boolean disconnectHeadset(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.DISCONNECT_HFP_OUTGOING;
            msg.obj = state;
            mHfpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    public boolean connectSink(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.CONNECT_A2DP_OUTGOING;
            msg.obj = state;
            mA2dpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    public boolean disconnectSink(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.DISCONNECT_A2DP_OUTGOING;
            msg.obj = state;
            mA2dpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    BluetoothDeviceProfileState addProfileState(String address) {
        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) return state;

        state = new BluetoothDeviceProfileState(mContext, address, this, mA2dpService);
        mDeviceProfileState.put(address, state);
        state.start();
        return state;
    }

    void removeProfileState(String address) {
        mDeviceProfileState.remove(address);
    }

    synchronized String[] getKnownDevices() {
        String[] bonds = null;
        String val = getProperty("Devices");
        if (val != null) {
            bonds = val.split(",");
        }
        return bonds;
    }

    private void initProfileState() {
        String[] bonds = null;
        String val = mAdapterProperties.getProperty("Devices");
        if (val != null) {
            bonds = val.split(",");
        }
        if (bonds == null) {
            return;
        }
        for (String path : bonds) {
            String address = getAddressFromObjectPath(path);
            BluetoothDeviceProfileState state = addProfileState(address);
        }
    }

    private void autoConnect() {
        String[] bonds = getKnownDevices();
        if (bonds == null) {
            return;
        }
        for (String path : bonds) {
            String address = getAddressFromObjectPath(path);
            BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
            if (state != null) {
                Message msg = new Message();
                msg.what = BluetoothDeviceProfileState.AUTO_CONNECT_PROFILES;
                state.sendMessage(msg);
            }
        }
    }

    public boolean notifyIncomingConnection(String address) {
        BluetoothDeviceProfileState state =
             mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.what = BluetoothDeviceProfileState.CONNECT_HFP_INCOMING;
            state.sendMessage(msg);
            return true;
        }
        return false;
    }

    /*package*/ boolean notifyIncomingA2dpConnection(String address) {
       BluetoothDeviceProfileState state =
            mDeviceProfileState.get(address);
       if (state != null) {
           Message msg = new Message();
           msg.what = BluetoothDeviceProfileState.CONNECT_A2DP_INCOMING;
           state.sendMessage(msg);
           return true;
       }
       return false;
    }

    /*package*/ void setA2dpService(BluetoothA2dpService a2dpService) {
        mA2dpService = a2dpService;
    }

    public void sendProfileStateMessage(int profile, int cmd) {
        Message msg = new Message();
        msg.what = cmd;
        if (profile == BluetoothProfileState.HFP) {
            mHfpProfileState.sendMessage(msg);
        } else if (profile == BluetoothProfileState.A2DP) {
            mA2dpProfileState.sendMessage(msg);
        }
    }

    public int getAdapterConnectionState() {
        return mAdapterConnectionState;
    }

    public synchronized void sendConnectionStateChange(BluetoothDevice device, int state,
                                                        int prevState) {
        // Since this is a binder call check if Bluetooth is on still
        if (mBluetoothState == BluetoothAdapter.STATE_OFF) return;

        if (updateCountersAndCheckForConnectionStateChange(state, prevState)) {
            if (!validateProfileConnectionState(state) ||
                    !validateProfileConnectionState(prevState)) {
                // Previously, an invalid state was broadcast anyway,
                // with the invalid state converted to -1 in the intent.
                // Better to log an error and not send an intent with
                // invalid contents or set mAdapterConnectionState to -1.
                Log.e(TAG, "Error in sendConnectionStateChange: "
                        + "prevState " + prevState + " state " + state);
                return;
            }

            mAdapterConnectionState = state;

            Intent intent = new Intent(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            intent.putExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    convertToAdapterState(state));
            intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_CONNECTION_STATE,
                    convertToAdapterState(prevState));
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            Log.d(TAG, "CONNECTION_STATE_CHANGE: " + device + ": "
                    + prevState + " -> " + state);
        }
    }

    private boolean validateProfileConnectionState(int state) {
        return (state == BluetoothProfile.STATE_DISCONNECTED ||
                state == BluetoothProfile.STATE_CONNECTING ||
                state == BluetoothProfile.STATE_CONNECTED ||
                state == BluetoothProfile.STATE_DISCONNECTING);
    }

    private int convertToAdapterState(int state) {
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return BluetoothAdapter.STATE_DISCONNECTED;
            case BluetoothProfile.STATE_DISCONNECTING:
                return BluetoothAdapter.STATE_DISCONNECTING;
            case BluetoothProfile.STATE_CONNECTED:
                return BluetoothAdapter.STATE_CONNECTED;
            case BluetoothProfile.STATE_CONNECTING:
                return BluetoothAdapter.STATE_CONNECTING;
        }
        Log.e(TAG, "Error in convertToAdapterState");
        return -1;
    }

    private boolean updateCountersAndCheckForConnectionStateChange(int state, int prevState) {
        switch (prevState) {
            case BluetoothProfile.STATE_CONNECTING:
                mProfilesConnecting--;
                break;

            case BluetoothProfile.STATE_CONNECTED:
                mProfilesConnected--;
                break;

            case BluetoothProfile.STATE_DISCONNECTING:
                mProfilesDisconnecting--;
                break;
        }

        switch (state) {
            case BluetoothProfile.STATE_CONNECTING:
                mProfilesConnecting++;
                return (mProfilesConnected == 0 && mProfilesConnecting == 1);

            case BluetoothProfile.STATE_CONNECTED:
                mProfilesConnected++;
                return (mProfilesConnected == 1);

            case BluetoothProfile.STATE_DISCONNECTING:
                mProfilesDisconnecting++;
                return (mProfilesConnected == 0 && mProfilesDisconnecting == 1);

            case BluetoothProfile.STATE_DISCONNECTED:
                return (mProfilesConnected == 0 && mProfilesConnecting == 0);

            default:
                return true;
        }
    }

    private native static void classInitNative();
    private native void initializeNativeDataNative();
    private native boolean setupNativeDataNative();
    private native boolean tearDownNativeDataNative();
    private native void cleanupNativeDataNative();
    /*package*/ native String getAdapterPathNative();

    private native int isEnabledNative();
    private native int enableNative();
    private native int disableNative();

    /*package*/ native Object[] getAdapterPropertiesNative();
    private native Object[] getDevicePropertiesNative(String objectPath);
    private native boolean setAdapterPropertyStringNative(String key, String value);
    private native boolean setAdapterPropertyIntegerNative(String key, int value);
    private native boolean setAdapterPropertyBooleanNative(String key, int value);

    private native boolean startDiscoveryNative();
    private native boolean stopDiscoveryNative();

    private native boolean createPairedDeviceNative(String address, int timeout_ms);
    private native boolean createPairedDeviceOutOfBandNative(String address, int timeout_ms);
    private native byte[] readAdapterOutOfBandDataNative();

    private native boolean cancelDeviceCreationNative(String address);
    private native boolean removeDeviceNative(String objectPath);
    private native int getDeviceServiceChannelNative(String objectPath, String uuid,
            int attributeId);

    private native boolean cancelPairingUserInputNative(String address, int nativeData);
    private native boolean setPinNative(String address, String pin, int nativeData);
    private native boolean setPasskeyNative(String address, int passkey, int nativeData);
    private native boolean setPairingConfirmationNative(String address, boolean confirm,
            int nativeData);
    private native boolean setRemoteOutOfBandDataNative(String address, byte[] hash,
                                                        byte[] randomizer, int nativeData);

    private native boolean setDevicePropertyBooleanNative(String objectPath, String key,
            int value);
    private native boolean createDeviceNative(String address);
    /*package*/ native boolean discoverServicesNative(String objectPath, String pattern);

    private native int addRfcommServiceRecordNative(String name, long uuidMsb, long uuidLsb,
            short channel);
    private native boolean removeServiceRecordNative(int handle);
    private native boolean setLinkTimeoutNative(String path, int num_slots);
    native boolean connectInputDeviceNative(String path);
    native boolean disconnectInputDeviceNative(String path);

    native boolean setBluetoothTetheringNative(boolean value, String nap, String bridge);
    native boolean connectPanDeviceNative(String path, String dstRole);
    native boolean disconnectPanDeviceNative(String path);
    native boolean disconnectPanServerDeviceNative(String path,
            String address, String iface);

    private native int[] addReservedServiceRecordsNative(int[] uuuids);
    private native boolean removeReservedServiceRecordsNative(int[] handles);
}

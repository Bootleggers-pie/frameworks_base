/*
 * Copyright 2018 The Android Open Source Project
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

package android.bluetooth;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the public APIs to control the Bluetooth Hearing Aid
 * profile.
 *
 * <p>BluetoothHearingAid is a proxy object for controlling the Bluetooth Hearing Aid
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothHearingAid proxy object.
 *
 * <p> Each method is protected with its appropriate permission.
 * @hide
 */
public final class BluetoothHearingAid implements BluetoothProfile {
    private static final String TAG = "BluetoothHearingAid";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    /**
     * Intent used to broadcast the change in connection state of the Hearing Aid
     * profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     * <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.</li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the change in the Playing state of the Hearing Aid
     * profile.
     *
     * <p>This intent will have 3 extras:
     * <ul>
     * <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile. </li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_PLAYING}, {@link #STATE_NOT_PLAYING},
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PLAYING_STATE_CHANGED =
            "android.bluetooth.hearingaid.profile.action.PLAYING_STATE_CHANGED";

    /**
     * Intent used to broadcast the selection of a connected device as active.
     *
     * <p>This intent will have one extra:
     * <ul>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. It can
     * be null if no device is active. </li>
     * </ul>
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.hearingaid.profile.action.ACTIVE_DEVICE_CHANGED";

    /**
     * Hearing Aid device is streaming music. This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_PLAYING_STATE_CHANGED} intent.
     */
    public static final int STATE_PLAYING = 10;

    /**
     * Hearing Aid device is NOT streaming music. This state can be one of
     * {@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} of
     * {@link #ACTION_PLAYING_STATE_CHANGED} intent.
     */
    public static final int STATE_NOT_PLAYING = 11;

    /** This device represents Left Hearing Aid. */
    public static final int SIDE_LEFT = IBluetoothHearingAid.SIDE_LEFT;

    /** This device represents Right Hearing Aid. */
    public static final int SIDE_RIGHT = IBluetoothHearingAid.SIDE_RIGHT;

    /** This device is Monaural. */
    public static final int MODE_MONAURAL = IBluetoothHearingAid.MODE_MONAURAL;

    /** This device is Binaural (should receive only left or right audio). */
    public static final int MODE_BINAURAL = IBluetoothHearingAid.MODE_BINAURAL;

    /** Can't read ClientID for this device */
    public static final long HI_SYNC_ID_INVALID = IBluetoothHearingAid.HI_SYNC_ID_INVALID;

    private BluetoothAdapter mAdapter;
    private final BluetoothProfileConnector<IBluetoothHearingAid> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.HEARING_AID,
                    "BluetoothHearingAid", IBluetoothHearingAid.class.getName()) {
                @Override
                public IBluetoothHearingAid getServiceInterface(IBinder service) {
                    return IBluetoothHearingAid.Stub.asInterface(Binder.allowBlocking(service));
                }
    };

    /**
     * Create a BluetoothHearingAid proxy object for interacting with the local
     * Bluetooth Hearing Aid service.
     */
    /*package*/ BluetoothHearingAid(Context context, ServiceListener listener) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mProfileConnector.connect(context, listener);
    }

    /*package*/ void close() {
        mProfileConnector.disconnect();
    }

    private IBluetoothHearingAid getService() {
        return mProfileConnector.getService();
    }

    @Override
    public void finalize() {
        // The empty finalize needs to be kept or the
        // cts signature tests would fail.
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is already connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that
     * connection state intent for the profile will be broadcasted with
     * the state. Users can get the connection state of the profile
     * from this intent.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled() && isValidDevice(device)) {
                return service.connect(device);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * Initiate disconnection from a profile
     *
     * <p> This API will return false in scenarios like the profile on the
     * Bluetooth device is not in connected state etc. When this API returns,
     * true, it is guaranteed that the connection state change
     * intent will be broadcasted with the state. Users can get the
     * disconnection state of the profile from this intent.
     *
     * <p> If the disconnection is initiated by a remote device, the state
     * will transition from {@link #STATE_CONNECTED} to
     * {@link #STATE_DISCONNECTED}. If the disconnect is initiated by the
     * host (local) device the state will transition from
     * {@link #STATE_CONNECTED} to state {@link #STATE_DISCONNECTING} to
     * state {@link #STATE_DISCONNECTED}. The transition to
     * {@link #STATE_DISCONNECTING} can be used to distinguish between the
     * two scenarios.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled() && isValidDevice(device)) {
                return service.disconnect(device);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()) {
                return service.getConnectedDevices();
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<BluetoothDevice>();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()) {
                return service.getDevicesMatchingConnectionStates(states);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<BluetoothDevice>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<BluetoothDevice>();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                return service.getConnectionState(device);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.STATE_DISCONNECTED;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    /**
     * Select a connected device as active.
     *
     * The active device selection is per profile. An active device's
     * purpose is profile-specific. For example, Hearing Aid audio
     * streaming is to the active Hearing Aid device. If a remote device
     * is not connected, it cannot be selected as active.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is not connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that the
     * {@link #ACTION_ACTIVE_DEVICE_CHANGED} intent will be broadcasted
     * with the active device.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device the remote Bluetooth device. Could be null to clear
     * the active device and stop streaming audio to a Bluetooth device.
     * @return false on immediate error, true otherwise
     * @hide
     */
    public boolean setActiveDevice(@Nullable BluetoothDevice device) {
        if (DBG) log("setActiveDevice(" + device + ")");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()
                    && ((device == null) || isValidDevice(device))) {
                service.setActiveDevice(device);
                return true;
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * Get the connected physical Hearing Aid devices that are active
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     * permission.
     *
     * @return the list of active devices. The first element is the left active
     * device; the second element is the right active device. If either or both side
     * is not active, it will be null on that position. Returns empty list on error.
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public List<BluetoothDevice> getActiveDevices() {
        if (VDBG) log("getActiveDevices()");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()) {
                return service.getActiveDevices();
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return new ArrayList<>();
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return new ArrayList<>();
        }
    }

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.
     * Priority can be one of {@link #PRIORITY_ON} orgetBluetoothManager
     * {@link #PRIORITY_OFF},
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     * permission.
     *
     * @param device Paired bluetooth device
     * @param priority
     * @return true if priority is set, false on error
     * @hide
     */
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) log("setPriority(" + device + ", " + priority + ")");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                if (priority != BluetoothProfile.PRIORITY_OFF
                        && priority != BluetoothProfile.PRIORITY_ON) {
                    return false;
                }
                return service.setPriority(device, priority);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * Get the priority of the profile.
     *
     * <p> The priority can be any of:
     * {@link #PRIORITY_AUTO_CONNECT}, {@link #PRIORITY_OFF},
     * {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * @param device Bluetooth device
     * @return priority of the device
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getPriority(BluetoothDevice device) {
        if (VDBG) log("getPriority(" + device + ")");
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                return service.getPriority(device);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return BluetoothProfile.PRIORITY_OFF;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return BluetoothProfile.PRIORITY_OFF;
        }
    }

    /**
     * Helper for converting a state to a string.
     *
     * For debug use only - strings are not internationalized.
     *
     * @hide
     */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_DISCONNECTED:
                return "disconnected";
            case STATE_CONNECTING:
                return "connecting";
            case STATE_CONNECTED:
                return "connected";
            case STATE_DISCONNECTING:
                return "disconnecting";
            case STATE_PLAYING:
                return "playing";
            case STATE_NOT_PLAYING:
                return "not playing";
            default:
                return "<unknown state " + state + ">";
        }
    }

    /**
     * Get the volume of the device.
     *
     * <p> The volume is between -128 dB (mute) to 0 dB.
     *
     * @return volume of the hearing aid device.
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getVolume() {
        if (VDBG) {
            log("getVolume()");
        }
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()) {
                return service.getVolume();
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return 0;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return 0;
        }
    }

    /**
     * Tells remote device to adjust volume. Uses the following values:
     * <ul>
     * <li>{@link AudioManager#ADJUST_LOWER}</li>
     * <li>{@link AudioManager#ADJUST_RAISE}</li>
     * <li>{@link AudioManager#ADJUST_MUTE}</li>
     * <li>{@link AudioManager#ADJUST_UNMUTE}</li>
     * </ul>
     *
     * @param direction One of the supported adjust values.
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public void adjustVolume(int direction) {
        if (DBG) log("adjustVolume(" + direction + ")");

        final IBluetoothHearingAid service = getService();
        try {
            if (service == null) {
                Log.w(TAG, "Proxy not attached to service");
                return;
            }

            if (!isEnabled()) return;

            service.adjustVolume(direction);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
    }

    /**
     * Tells remote device to set an absolute volume.
     *
     * @param volume Absolute volume to be set on remote
     * @hide
     */
    public void setVolume(int volume) {
        if (DBG) Log.d(TAG, "setVolume(" + volume + ")");

        final IBluetoothHearingAid service = getService();
        try {
            if (service == null) {
                Log.w(TAG, "Proxy not attached to service");
                return;
            }

            if (!isEnabled()) return;

            service.setVolume(volume);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
        }
    }

    /**
     * Get the CustomerId of the device.
     *
     * @param device Bluetooth device
     * @return the CustomerId of the device
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public long getHiSyncId(BluetoothDevice device) {
        if (VDBG) {
            log("getCustomerId(" + device + ")");
        }
        final IBluetoothHearingAid service = getService();
        try {
            if (service == null) {
                Log.w(TAG, "Proxy not attached to service");
                return HI_SYNC_ID_INVALID;
            }

            if (!isEnabled() || !isValidDevice(device)) return HI_SYNC_ID_INVALID;

            return service.getHiSyncId(device);
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return HI_SYNC_ID_INVALID;
        }
    }

    /**
     * Get the side of the device.
     *
     * @param device Bluetooth device.
     * @return SIDE_LEFT or SIDE_RIGHT
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getDeviceSide(BluetoothDevice device) {
        if (VDBG) {
            log("getDeviceSide(" + device + ")");
        }
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                return service.getDeviceSide(device);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return SIDE_LEFT;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return SIDE_LEFT;
        }
    }

    /**
     * Get the mode of the device.
     *
     * @param device Bluetooth device
     * @return MODE_MONAURAL or MODE_BINAURAL
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getDeviceMode(BluetoothDevice device) {
        if (VDBG) {
            log("getDeviceMode(" + device + ")");
        }
        final IBluetoothHearingAid service = getService();
        try {
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                return service.getDeviceMode(device);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return MODE_MONAURAL;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return MODE_MONAURAL;
        }
    }

    private boolean isEnabled() {
        if (mAdapter.getState() == BluetoothAdapter.STATE_ON) return true;
        return false;
    }

    private boolean isValidDevice(BluetoothDevice device) {
        if (device == null) return false;

        if (BluetoothAdapter.checkBluetoothAddress(device.getAddress())) return true;
        return false;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}

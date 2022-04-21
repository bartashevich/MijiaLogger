package de.gorian.sensorreader;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.WorkManager;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class BluetoothLeService extends Service implements BluetoothProfile, MqttCallback {

	public final static String TEMPERATURE = "de.gorian.sensorreader.TEMPERATURE";
	public final static String HUMIDITY = "de.gorian.sensorreader.HUMIDITY";
	public final static String BATTERY = "de.gorian.sensorreader.BATTERY";
	public final static String ADDRESS = "de.gorian.sensorreader.ADDRESS";
	private final static String READ_SENSOR_WORKER_TAG = "ReadSensor";
	private final static String TAG = BluetoothLeService.class.getSimpleName();
	public static final String SENSOR_UPDATE_ACTION = "de.gorian.sensorReader.MIJIA_DATA_RECEIVED";
	Set<BluetoothDevice> devices = new HashSet<>();
	// Various callback methods defined by the BLE API.
	private ScanCallback leScanCallback =
			new ScanCallback() {
				@Override
				public void onScanResult(int callbackType, ScanResult result) {

					final BluetoothDevice device = result.getDevice();

//					Log.i(TAG, "New BLE device found: " + device.getName() + " " + device.getAddress());
					if (device.getName() != null && device.getName().contains(Constant.MIJIA_NAME)) {

						devices.add(device);
						try {
							Collection<byte[]> scanData = Objects.requireNonNull(result.getScanRecord()).getServiceData().values();
							for (byte[] aByteSet : scanData) {
								BluetoothLeService.this.broadcastUpdate(aByteSet, device);
							}

						} catch (NullPointerException ignored) {
						}


						// TODO: update UI

					}
				}
			};
	private BluetoothLeScanner bluetoothLeScanner;
	private BluetoothManager bluetoothManager;
	private BluetoothAdapter bluetoothAdapter;
	private List<BluetoothGatt> bluetoothGatt = new ArrayList<>();
	private SensorUpdateBroadcastReceiver sensorUpdateBroadcastReceiver;

	public static BluetoothLeService getINSTANCE() {
		return LazyHolder.INSTANCE;
	}

	public BluetoothLeService() {
		super();
		Log.d(TAG, "Constructor executed.");
	}

	@Override
	public List<BluetoothDevice> getConnectedDevices() {
		return new ArrayList<>(devices);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		LazyHolder.INSTANCE = this;
		initBTLE();
		this.sensorUpdateBroadcastReceiver = new SensorUpdateBroadcastReceiver();
		this.sensorUpdateBroadcastReceiver.setContext(this);
		this.sensorUpdateBroadcastReceiver.createClient();
		this.sensorUpdateBroadcastReceiver.connect();
		LocalBroadcastManager.getInstance(this).registerReceiver(this.sensorUpdateBroadcastReceiver, new IntentFilter(SENSOR_UPDATE_ACTION));
	}

	@Override
	public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
//		List<BluetoothDevice> someDevices = new ArrayList<>();
//		for (int ii = 0; ii < devices.size(); ii++) {
//			if (Arrays.stream(states).boxed().collect(Collectors.toList()).contains(connectionState.get(ii))) {
//				someDevices.add(devices.get(ii));
//			}
//		}
		return null;
	}

	@Override
	public int getConnectionState(BluetoothDevice device) {
//		int state = -1;
//		try {
//			state = connectionState.get(devices.indexOf(device));
//		} catch (IndexOutOfBoundsException e) {
//			Log.e(TAG, "device (" + device.getName() + " " + device.getAddress() + ") unknown. Known devices :");
//			devices.forEach(dev -> Log.d(TAG, "\t\t" + dev.getName() + " " + dev.getAddress()));
//		}
		return BluetoothProfile.STATE_CONNECTED;
	}

	private void broadcastUpdate(final byte[] rawData, BluetoothDevice device) {

		Log.d(TAG, "RAW DATA: " + Arrays.toString(rawData));
		// write the data formatted in HEX.
		if (rawData != null && rawData.length > 6) {
			final StringBuilder stringBuilder = new StringBuilder(rawData.length);
			for (byte byteChar : rawData) {
				stringBuilder.append(String.format("%02X ", byteChar));
			}
			Log.d(TAG, "Sensor update broadcasted: " + stringBuilder.toString());
			String[] hexBytes = stringBuilder.toString().split(" ");
			Integer battery = null;
			Double temperature = null;
			Double humidity = null;
			try {
				if (hexBytes.length < 12) return;

				temperature = (double) (Integer.parseInt(hexBytes[7] + hexBytes[6], 16) / 100.0);
				humidity = (double) (Integer.parseInt(hexBytes[9] + hexBytes[8], 16) / 100.0);
				battery = Integer.parseInt(hexBytes[12], 16);
			} catch (ArrayIndexOutOfBoundsException e) {
				e.printStackTrace();
			}
			final Intent intent = new Intent(BluetoothLeService.SENSOR_UPDATE_ACTION);
			intent.putExtra(TEMPERATURE, temperature);
			intent.putExtra(HUMIDITY, humidity);
			intent.putExtra(BATTERY, battery);
			intent.putExtra(ADDRESS, device.getAddress().replace(":", ""));
			LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
		}
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	boolean isInitialized() {
		return bluetoothLeScanner != null;
	}

	private void initBTLE() {

		Log.d(TAG, "Check permissions: " + hasRequiredPermissions());
		if (!hasRequiredPermissions()) return;

		if (bluetoothManager == null) {
			bluetoothManager = (BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE);
			bluetoothAdapter = bluetoothManager.getAdapter();
			bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
		}

		if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
			Log.d(TAG, "Sending request to enable bluetooth adapter. This could fail.");
			Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			enableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//			int REQUEST_ENABLE_BT = 42;
			startActivity(enableIntent);
		}

	}

	private boolean hasPermission(String permission) {
		return ActivityCompat.checkSelfPermission(getApplicationContext(), permission) == PackageManager.PERMISSION_GRANTED;
	}

	boolean hasRequiredPermissions() {
		boolean hasBluetoothPermission = hasPermission(Manifest.permission.BLUETOOTH);
		boolean hasBluetoothAdminPermission = hasPermission(Manifest.permission.BLUETOOTH_ADMIN);
		boolean hasLocationPermission = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
		return hasBluetoothPermission && hasBluetoothAdminPermission && hasLocationPermission;
	}

	@Override
	public void onDestroy() {
		stopScan();
		super.onDestroy();
		try {
			sensorUpdateBroadcastReceiver.mqttAndroidClient.disconnect();
		} catch (MqttException e) {
			Log.e(TAG, Arrays.toString(e.getStackTrace()));
		}
		LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorUpdateBroadcastReceiver);
		Log.d(TAG, "Service died.");
	}

	public void stopScan() {
		bluetoothLeScanner.stopScan(leScanCallback);
		if (bluetoothGatt.size() > 0) {
			Log.i(TAG, "SERVICES: ");
			bluetoothGatt.get(0).getServices().forEach(bluetoothGattService -> Log.i(TAG, bluetoothGattService.getUuid().toString()));
		}
	}

	@Override
	public void connectionLost(Throwable cause) {
	}

	public void startScan() {
		bluetoothGatt.clear();
		bluetoothLeScanner.startScan(leScanCallback);
		WorkManager workMan = WorkManager.getInstance(this);
		workMan.cancelAllWorkByTag(READ_SENSOR_WORKER_TAG);
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) {
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
	}

	public static class LazyHolder {
		public static BluetoothLeService INSTANCE;
	}

}
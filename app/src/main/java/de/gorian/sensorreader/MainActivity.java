package de.gorian.sensorreader;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.preference.PreferenceManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

	public static String TAG;
	private AppBarConfiguration mAppBarConfiguration;
	private BluetoothLeService bluetoothLeService;
	private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {

			Log.d(TAG, "Main Broadcast received.");
			updateDevicesList();
			LinearLayout devicesList = findViewById(R.id.devices_list);
			for (int ii = 0; ii < devicesList.getChildCount(); ii++) {
				View sensorCard = devicesList.getChildAt(ii);
				TextView macTextField = sensorCard.findViewById(R.id.sensor_mac);
				Log.d(TAG, macTextField.getText().toString().replace(":", "") + " <--> " + intent.getExtras().getString(BluetoothLeService.ADDRESS));
				if (macTextField.getText().toString().replace(":", "").equals(Objects.requireNonNull(intent.getExtras()).getString(BluetoothLeService.ADDRESS))) {
					TextView temperatureTextField = sensorCard.findViewById(R.id.temperature);
					Double temperature = (Double) intent.getExtras().get(BluetoothLeService.TEMPERATURE);
					if (temperature != null)
						temperatureTextField.setText(getResources().getString(R.string.temperature_template, String.format(Locale.GERMANY, "%2.1f", temperature)));
					TextView humidityTextField = sensorCard.findViewById(R.id.humidity);
					Double humidity = (Double) intent.getExtras().get(BluetoothLeService.HUMIDITY);
					if (humidity != null)
						humidityTextField.setText(getResources().getString(R.string.humidity_template, String.format(Locale.GERMANY, "%2.1f", humidity)));
					return;
				}
			}
			Fragment frg = getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
			if (frg != null) {
				final FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
				ft.detach(frg);
				ft.attach(frg);
				ft.commit();
			}

		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		TAG = getResources().getString(R.string.app_name);
		setContentView(R.layout.activity_main);
		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		FloatingActionButton fab = findViewById(R.id.fab);
		DrawerLayout drawer = findViewById(R.id.drawer_layout);
		NavigationView navigationView = findViewById(R.id.nav_view);
		// Passing each menu ID as a set of Ids because each
		// menu should be considered as top level destinations.
		mAppBarConfiguration = new AppBarConfiguration.Builder(
				R.id.nav_sensors, R.id.nav_logging, R.id.nav_settings)
				.setOpenableLayout(drawer)
				.build();
		final NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
		NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
		NavigationUI.setupWithNavController(navigationView, navController);

		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

		// start bluetooth service
		startService(new Intent(this, BluetoothLeService.class));

		fab.setOnClickListener(view -> {
			navController.navigate(R.id.nav_sensors);

			Snackbar sb = Snackbar.make(findViewById(R.id.nav_host_fragment), R.string.scanning, Snackbar.LENGTH_INDEFINITE).setAction("STOP", view1 -> stopScan());
			sb.getView().setBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.snackbarColor));
			sb.show();

			searchBLEDevices();
		});

		bluetoothLeService = BluetoothLeService.getINSTANCE();
	}

	protected void onStart() {
		super.onStart();
		LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver,
				new IntentFilter(BluetoothLeService.SENSOR_UPDATE_ACTION)
		);
	}

	@Override
	protected void onStop() {
		LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
		super.onStop();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
//		getMenuInflater().inflate(R.menu.main, menu);
		searchBLEDevices();
		return true;
	}

	@Override
	public boolean onSupportNavigateUp() {
		NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
		return NavigationUI.navigateUp(navController, mAppBarConfiguration)
				|| super.onSupportNavigateUp();
	}


	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == 42 && resultCode == RESULT_OK) {
			// user enabled Bluetooth, so start Scanning
			searchBLEDevices();
		} else {
			Log.d(TAG, "Bluetooth enable request returned " + resultCode);
		}
	}

	private void requestPermissions() {
		if (!bluetoothLeService.hasRequiredPermissions()) {
			Log.d(TAG, "Requesting permissions...");
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_COARSE_LOCATION}, PackageManager.PERMISSION_GRANTED);
			Log.d(TAG, "Done!");
		} else return;
		Log.d(TAG, "Recheck permissions: " + bluetoothLeService.hasRequiredPermissions() + "!!!");
		if (!bluetoothLeService.hasRequiredPermissions()) {
			Snackbar sb = Snackbar.make(findViewById(R.id.nav_host_fragment), "Bluetooth and Location permission are needed to use the bluetooth module. ", Snackbar.LENGTH_LONG).setAction("ASK AGAIN", view -> ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_COARSE_LOCATION}, PackageManager.PERMISSION_GRANTED));
			sb.getView().setBackgroundColor(ContextCompat.getColor(this, R.color.snackbarColor));
			sb.show();
		}
	}

	void stopScan() {
		bluetoothLeService.stopScan();
//		int nDevices = bluetoothLeService.getNumberOfDevices();
//		String resultMessage = (nDevices == 0 ? "No" : nDevices) + " compatible " + (nDevices == 1 ? "device" : "devices") + " found.";
		Snackbar sb;
		sb = Snackbar.make(findViewById(R.id.nav_host_fragment), "TODO", Snackbar.LENGTH_LONG).setAction("RETRY", view -> searchBLEDevices());
		sb.getView().setBackgroundColor(ContextCompat.getColor(this, R.color.snackbarColor));
		sb.show();
//		Log.d(TAG, resultMessage);


	}

	void startScan() {
		bluetoothLeService.startScan();
	}

	void searchBLEDevices() {

		if (bluetoothLeService == null) {
			bluetoothLeService = BluetoothLeService.getINSTANCE();
		}
		requestPermissions();
		if (!bluetoothLeService.isInitialized()) return;
//		bluetoothLeService.clearDevices();
		Log.i(TAG, "Suche nach BLE Geräten gestartet...");
		AsyncTask.execute(this::startScan);

//		handler.postDelayed(() -> {
//			Log.i(TAG, "Suche nach BLE Geräten beendet.");
//			AsyncTask.execute(this::stopScan);
//		}, BluetoothLeService.BLE_SEARCH_INTERVAL_MS);
	}

	private void updateDevicesList() {

		Navigation.findNavController(this, R.id.nav_host_fragment).navigate(R.id.nav_sensors);

		LinearLayout devicesList = findViewById(R.id.devices_list);
		devicesList.removeAllViews();
		for (BluetoothDevice device : bluetoothLeService.getConnectedDevices()) {
			Log.d(TAG, "Creating device card for " + device.getName() + " " + device.getAddress());
			View sensorCard = LayoutInflater.from(MainActivity.this).inflate(R.layout.sensor_element, devicesList);
			TextView nameTextField = sensorCard.findViewById(R.id.sensor_name);
			nameTextField.setText(device.getName());
			TextView macTextField = sensorCard.findViewById(R.id.sensor_mac);
			macTextField.setText(device.getAddress());
//			devicesList.addView(sensorCard);

			// TODO: add enable/disable logging preference for switch

			// TODO: add onClick Listener on info button and display a popup

		}

	}


}
package com.example.bluetoothgatt;

import java.util.UUID;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

/**
 * HackIllinois
 * 
 * @author Han, Yi
 * @author Wang, Ning
 * 
 * Implemented the item tracking using TI SensorTag, BLE technology. On-board sensors to display the temperature and humidity data. 
 * 
 */
public class MainActivity extends Activity implements BluetoothAdapter.LeScanCallback {

	boolean notificationSent = false;
	private boolean interationComplete = false;

	private ImageView ivHumitity;
	private ImageView ivProximity;

	// ***************************************************************************
	// Notification ID to allow for future updates
	private static final int MY_NOTIFICATION_ID = 1;

	// Notification Count
	private int mNotificationCount;

	// Notification Text Elements
	private final CharSequence tickerText = "Alert! Alert! Alert! SensorTag is in DANGER!";
	private final CharSequence contentTitle = "Notification";
	private final CharSequence contentText = "Don't forget SensorTag";

	// Notification Action Elements
	private Intent mNotificationIntent;
	private PendingIntent mContentIntent;

	// Notification Sound and Vibration on Arrival
	private Uri soundURI = Uri.parse("android.resource://com.example.bluetoothgatt/" + R.raw.out_of_range_notification);
	private long[] mVibratePattern = { 0, 200, 200, 300 };

	RemoteViews mContentView = new RemoteViews("com.example.bluetoothgatt", R.layout.custom_notification);
	// ***************************************************************************

	private static final String TAG = "BluetoothGattActivity";

	private static final String DEVICE_NAME = "SensorTag";

	/* Humidity Service */
	private static final UUID HUMIDITY_SERVICE = UUID.fromString("f000aa20-0451-4000-b000-000000000000");
	private static final UUID HUMIDITY_DATA_CHAR = UUID.fromString("f000aa21-0451-4000-b000-000000000000");
	private static final UUID HUMIDITY_CONFIG_CHAR = UUID.fromString("f000aa22-0451-4000-b000-000000000000");
	/* Barometric Pressure Service */
	private static final UUID PRESSURE_SERVICE = UUID.fromString("f000aa40-0451-4000-b000-000000000000");
	private static final UUID PRESSURE_DATA_CHAR = UUID.fromString("f000aa41-0451-4000-b000-000000000000");
	private static final UUID PRESSURE_CONFIG_CHAR = UUID.fromString("f000aa42-0451-4000-b000-000000000000");
	private static final UUID PRESSURE_CAL_CHAR = UUID.fromString("f000aa43-0451-4000-b000-000000000000");
	/* Client Configuration Descriptor */
	private static final UUID CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

	private BluetoothAdapter mBluetoothAdapter;
	private SparseArray<BluetoothDevice> mDevices;

	private BluetoothGatt mConnectedGatt;

	private TextView mTemperature, mHumidity, mProximity;

	private ProgressDialog mProgress;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		setContentView(R.layout.activity_main);
		setProgressBarIndeterminate(true);

		// ***************************************************************************
		mNotificationIntent = new Intent(getApplicationContext(), NotificationSubActivity.class);
		mContentIntent = PendingIntent.getActivity(getApplicationContext(), 0, mNotificationIntent, Intent.FLAG_ACTIVITY_NEW_TASK);

		// ***************************************************************************

		/*
		 * We are going to display the results in some text fields
		 */
		mTemperature = (TextView) findViewById(R.id.text_temperature);
		mHumidity = (TextView) findViewById(R.id.text_humidity);
		// mPressure = (TextView) findViewById(R.id.text_pressure);
		mProximity = (TextView) findViewById(R.id.text_proximity);

		/*
		 * We are going to display the images
		 */
		ivHumitity = (ImageView) findViewById(R.id.ivHumidity);
		// ivPressure = (ImageView) findViewById(R.id.ivPressure);
		// ivTemperature = (ImageView) findViewById(R.id.ivTemperature);
		ivProximity = (ImageView) findViewById(R.id.ivProximity);
		/*
		 * Bluetooth in Android 4.3 is accessed via the BluetoothManager, rather
		 * than the old static BluetoothAdapter.getInstance()
		 */
		BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
		mBluetoothAdapter = manager.getAdapter();

		mDevices = new SparseArray<BluetoothDevice>();

		/*
		 * A progress dialog will be needed while the connection process is
		 * taking place
		 */
		mProgress = new ProgressDialog(this);
		mProgress.setIndeterminate(true);
		mProgress.setCancelable(false);
	}

	@Override
	protected void onResume() {
		super.onResume();
		/*
		 * We need to enforce that Bluetooth is first enabled, and take the user
		 * to settings to enable it if they have not done so.
		 */
		if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
			// Bluetooth is disabled
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivity(enableBtIntent);
			finish();
			return;
		}

		/*
		 * Check for Bluetooth LE Support. In production, our manifest entry
		 * will keep this from installing on these devices, but this will allow
		 * test devices or other sideloads to report whether or not the feature
		 * exists.
		 */
		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		clearDisplayValues();
	}

	@Override
	protected void onPause() {
		super.onPause();
		// Make sure dialog is hidden
		mProgress.dismiss();
		// Cancel any scans in progress
		mHandler.removeCallbacks(mStopRunnable);
		mHandler.removeCallbacks(mStartRunnable);
		mHandler.removeCallbacks(mStartRSSIRunnable);
		mBluetoothAdapter.stopLeScan(this);
	}

	@Override
	protected void onStop() {
		super.onStop();
		// Disconnect from any active tag connection
		if (mConnectedGatt != null) {
			mConnectedGatt.disconnect();
			mConnectedGatt = null;
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Add the "scan" option to the menu
		getMenuInflater().inflate(R.menu.main, menu);

		// Add any device elements we've discovered to the overflow menu
		for (int i = 0; i < mDevices.size(); i++) {
			BluetoothDevice device = mDevices.valueAt(i);
			menu.add(0, mDevices.keyAt(i), 0, device.getName());
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.action_scan:
			// mDevices.clear();
			// startScan();
			mBluetoothAdapter.stopLeScan(this);
			mBluetoothAdapter.startLeScan(this);
			return true;
		default:

			notificationSent = false;
			interationComplete = false;
			// Obtain the discovered device to connect with
			BluetoothDevice device = mDevices.get(item.getItemId());
			Log.i(TAG, "Connecting to " + device.getName());
			/*
			 * Make a connection with the device using the special LE-specific
			 * connectGatt() method, passing in a callback for GATT events
			 */
			mConnectedGatt = device.connectGatt(this, false, mGattCallback);
			// Display progress UI
			mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Connecting to " + device.getName() + "..."));
			mHandler.post(mStartRSSIRunnable);
			return super.onOptionsItemSelected(item);

		}
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (mDevices.size() == 0 && notificationSent) {
			MenuItem mi = menu.getItem(0);
			String s = (String) mi.getTitle();
			menu.removeItem(mi.getItemId());
			interationComplete = true;
		}
		if (mDevices.size() != 0 && interationComplete) {
			if (!((String) (menu.getItem(0).getTitle())).equals(DEVICE_NAME))
				for (int i = 0; i < mDevices.size(); i++) {
					BluetoothDevice device = mDevices.valueAt(i);
					menu.add(0, mDevices.keyAt(i), 0, device.getName());
				}
			mBluetoothAdapter.stopLeScan(this);
		}

		return true;

	}

	private void clearDisplayValues() {
		mTemperature.setText("---");
		mHumidity.setText("---");
		mProximity.setText("---");
		mProximity.setTextColor(Color.BLACK);
		ivHumitity.setImageResource(R.drawable.error);
		ivProximity.setImageResource(R.drawable.error);
		mDevices.clear();
	}

	private Runnable mStartRSSIRunnable = new Runnable() {
		@Override
		public void run() {
			startRSSIReading();
		}
	};

	private void startRSSIReading() {
		mConnectedGatt.readRemoteRssi();
		mHandler.postDelayed(mStartRSSIRunnable, 500);
	}

	private Runnable mStopRunnable = new Runnable() {
		@Override
		public void run() {
			stopScan();
		}
	};
	private Runnable mStartRunnable = new Runnable() {
		@Override
		public void run() {
			startScan();
		}
	};

	private void startScan() {
		mBluetoothAdapter.startLeScan(this);
		setProgressBarIndeterminateVisibility(true);

		mHandler.postDelayed(mStopRunnable, 2500);
	}

	private void stopScan() {
		mBluetoothAdapter.stopLeScan(this);
		setProgressBarIndeterminateVisibility(false);
	}

	/* BluetoothAdapter.LeScanCallback */

	@Override
	public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
		Log.i(TAG, "New LE Device: " + device.getName() + " @ " + rssi);
		/*
		 * We are looking for SensorTag devices only, so validate the name that
		 * each device reports before adding it to our collection
		 */
		if (DEVICE_NAME.equals(device.getName())) {
			mDevices.put(device.hashCode(), device);
			// Update the overflow menu
			invalidateOptionsMenu();
			System.out.println(device.toString());
		}
	}

	/*
	 * In this callback, we've created a bit of a state machine to enforce that
	 * only one characteristic be read or written at a time until all of our
	 * sensors are enabled and we are registered to get notifications.
	 */
	private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

		/* State Machine Tracking */
		private int mState = 0;

		private void reset() {
			mState = 0;

		}

		private void advance() {
			mState++;
		}

		// 2. Enable Sensors
		/*
		 * Send an enable command to each sensor by writing a configuration
		 * characteristic. This is specific to the SensorTag to keep power low
		 * by disabling sensors you aren't using.
		 */
		private void enableNextSensor(BluetoothGatt gatt) {
			BluetoothGattCharacteristic characteristic;
			switch (mState) {
			case 0:
				Log.d(TAG, "Enabling pressure cal");
				characteristic = gatt.getService(PRESSURE_SERVICE).getCharacteristic(PRESSURE_CONFIG_CHAR);
				characteristic.setValue(new byte[] { 0x02 });
				break;
			case 1:
				Log.d(TAG, "Enabling pressure");
				characteristic = gatt.getService(PRESSURE_SERVICE).getCharacteristic(PRESSURE_CONFIG_CHAR);
				characteristic.setValue(new byte[] { 0x01 });
				break;
			case 2:
				Log.d(TAG, "Enabling humidity");
				characteristic = gatt.getService(HUMIDITY_SERVICE).getCharacteristic(HUMIDITY_CONFIG_CHAR);
				characteristic.setValue(new byte[] { 0x01 });
				break;
			default:
				mHandler.sendEmptyMessage(MSG_DISMISS);
				Log.i(TAG, "All Sensors Enabled");
				return;
			}

			gatt.writeCharacteristic(characteristic);
		}

		// 4. Read the value from the sensor
		/*
		 * Read the data characteristic's value for each sensor explicitly
		 */
		private void readNextSensor(BluetoothGatt gatt) {
			BluetoothGattCharacteristic characteristic;
			switch (mState) {
			case 0:
				Log.d(TAG, "Reading pressure cal");
				characteristic = gatt.getService(PRESSURE_SERVICE).getCharacteristic(PRESSURE_CAL_CHAR);
				break;
			case 1:
				Log.d(TAG, "Reading pressure");
				characteristic = gatt.getService(PRESSURE_SERVICE).getCharacteristic(PRESSURE_DATA_CHAR);
				break;
			case 2:
				Log.d(TAG, "Reading humidity");
				characteristic = gatt.getService(HUMIDITY_SERVICE).getCharacteristic(HUMIDITY_DATA_CHAR);
				break;
			default:
				mHandler.sendEmptyMessage(MSG_DISMISS);
				Log.i(TAG, "All Sensors Enabled");
				return;
			}

			gatt.readCharacteristic(characteristic);
		}

		// 6. Tell the sensor, whenever the value changes, push the data back to
		// us
		/*
		 * Enable notification of changes on the data characteristic for each
		 * sensor by writing the ENABLE_NOTIFICATION_VALUE flag to that
		 * characteristic's configuration descriptor.
		 */
		private void setNotifyNextSensor(BluetoothGatt gatt) {
			BluetoothGattCharacteristic characteristic;
			switch (mState) {
			case 0:
				Log.d(TAG, "Set notify pressure cal");
				characteristic = gatt.getService(PRESSURE_SERVICE).getCharacteristic(PRESSURE_CAL_CHAR);
				break;
			case 1:
				Log.d(TAG, "Set notify pressure");
				characteristic = gatt.getService(PRESSURE_SERVICE).getCharacteristic(PRESSURE_DATA_CHAR);
				break;
			case 2:
				Log.d(TAG, "Set notify humidity");
				characteristic = gatt.getService(HUMIDITY_SERVICE).getCharacteristic(HUMIDITY_DATA_CHAR);
				break;
			default:
				mHandler.sendEmptyMessage(MSG_DISMISS);
				Log.i(TAG, "All Sensors Enabled");
				return;
			}

			// Step 1: Enable local notifications, on the app
			gatt.setCharacteristicNotification(characteristic, true);
			// Step 2: Enabled remote notifications, on the SensorTag
			BluetoothGattDescriptor desc = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
			desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			gatt.writeDescriptor(desc);
		}

		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			Log.d(TAG, "Connection State Change: " + status + " -> " + connectionState(newState));
			if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
				/*
				 * Once successfully connected, we must next discover all the
				 * services on the device before we can read and write their
				 * characteristics.
				 */
				gatt.discoverServices();
				mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Discovering Services..."));
			} else if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
				/*
				 * If at any point we disconnect, send a message to clear the
				 * weather values out of the UI
				 */
				mHandler.sendEmptyMessage(MSG_CLEAR);
				mHandler.postAtFrontOfQueue(showNotification);
				notificationSent = true;
				mDevices.clear();
			} else if (status != BluetoothGatt.GATT_SUCCESS) {
				/*
				 * If there is a failure at any stage, simply disconnect
				 */
				gatt.disconnect();
			}
		}

		// 1. Call Enable Sensor
		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			Log.d(TAG, "Services Discovered: " + status);
			mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Enabling Sensors..."));
			/*
			 * With services discovered, we are going to reset our state machine
			 * and start working through the sensors we need to enable
			 */
			reset();
			gatt.readRemoteRssi();
			enableNextSensor(gatt);
		}

		// 5. Async callback after reading is completed.
		// then set the sensor to notify us when new value appear
		@Override
		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			// For each read, pass the data up to the UI thread to update the
			// display

			if (HUMIDITY_DATA_CHAR.equals(characteristic.getUuid())) {
				mHandler.sendMessage(Message.obtain(null, MSG_HUMIDITY, characteristic));
			}
			if (PRESSURE_DATA_CHAR.equals(characteristic.getUuid())) {
				mHandler.sendMessage(Message.obtain(null, MSG_PRESSURE, characteristic));
			}
			if (PRESSURE_CAL_CHAR.equals(characteristic.getUuid())) {
				mHandler.sendMessage(Message.obtain(null, MSG_PRESSURE_CAL, characteristic));
			}

			// After reading the initial value, next we enable notifications
			setNotifyNextSensor(gatt);
		}

		// 3. After the sensor enable succeeded, then read the sensor value
		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			// After writing the enable flag, next we read the initial value
			readNextSensor(gatt);
		}

		// Regular callbacks, when the written descriptor sensor has a changed
		// data, it will call this method
		// Handler is a background thread, so we have to synchronize the handler
		// with the UI Thread in order to have the updates.
		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			/*
			 * After notifications are enabled, all updates from the device on
			 * characteristic value changes will be posted here. Similar to
			 * read, we hand these up to the UI thread to update the display.
			 */
			// gatt.readRemoteRssi();
			if (HUMIDITY_DATA_CHAR.equals(characteristic.getUuid())) {
				mHandler.sendMessage(Message.obtain(null, MSG_HUMIDITY, characteristic));
			}
			if (PRESSURE_DATA_CHAR.equals(characteristic.getUuid())) {
				mHandler.sendMessage(Message.obtain(null, MSG_PRESSURE, characteristic));
			}
			if (PRESSURE_CAL_CHAR.equals(characteristic.getUuid())) {
				mHandler.sendMessage(Message.obtain(null, MSG_PRESSURE_CAL, characteristic));
			}
		}

		// The descriptor has been writen to push data back to us, advance the
		// FSM
		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			// Once notifications are enabled, we move to the next sensor and
			// start over with enable
			advance();
			enableNextSensor(gatt);
		}

		@Override
		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
			Log.d(TAG, "Remote RSSI: " + rssi);
			mHandler.sendMessage(Message.obtain(null, MSG_PROXIMITY, Integer.valueOf(rssi)));

		}

		private String connectionState(int status) {
			switch (status) {
			case BluetoothProfile.STATE_CONNECTED:
				return "Connected";
			case BluetoothProfile.STATE_DISCONNECTED:
				return "Disconnected";
			case BluetoothProfile.STATE_CONNECTING:
				return "Connecting";
			case BluetoothProfile.STATE_DISCONNECTING:
				return "Disconnecting";
			default:
				return String.valueOf(status);
			}
		}
	};

	/*
	 * We have a Handler to process event results on the main thread
	 */
	private static final int MSG_HUMIDITY = 101;
	private static final int MSG_PRESSURE = 102;
	private static final int MSG_PRESSURE_CAL = 103;
	private static final int MSG_PROGRESS = 201;
	private static final int MSG_DISMISS = 202;
	private static final int MSG_CLEAR = 301;
	private static final int MSG_PROXIMITY = 999;
	// Characteristics is in the objects carried by the message
	private Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			BluetoothGattCharacteristic characteristic;
			switch (msg.what) {
			case MSG_PROXIMITY:
				Integer proximity_num = (Integer) msg.obj;
				if (proximity_num == null) {
					Log.w(TAG, "Error reading proximity value");
					return;
				}
				updateProximity(proximity_num.intValue());
				break;
			case MSG_HUMIDITY:
				characteristic = (BluetoothGattCharacteristic) msg.obj;
				if (characteristic.getValue() == null) {
					Log.w(TAG, "Error obtaining humidity value");
					return;
				}
				updateHumidityValues(characteristic);
				break;
			case MSG_PRESSURE:
				characteristic = (BluetoothGattCharacteristic) msg.obj;
				if (characteristic.getValue() == null) {
					Log.w(TAG, "Error obtaining pressure value");
					return;
				}
				updatePressureValue(characteristic);
				break;
			case MSG_PRESSURE_CAL:
				characteristic = (BluetoothGattCharacteristic) msg.obj;
				if (characteristic.getValue() == null) {
					Log.w(TAG, "Error obtaining cal value");
					return;
				}
				updatePressureCals(characteristic);
				break;
			case MSG_PROGRESS:
				mProgress.setMessage((String) msg.obj);
				if (!mProgress.isShowing()) {
					mProgress.show();
				}
				break;
			case MSG_DISMISS:
				mProgress.hide();
				break;
			case MSG_CLEAR:
				clearDisplayValues();
				break;
			}
		}
	};

	/* Methods to extract sensor raw data and update the UI */

	private void updateHumidityValues(BluetoothGattCharacteristic characteristic) {
		double humidity = SensorTagData.extractHumidity(characteristic);

		if (humidity <= 40.0) {
			ivHumitity.setImageResource(R.drawable.humidity1);
		} else if (humidity > 40.0 && humidity < 60.0) {
			ivHumitity.setImageResource(R.drawable.humidity2);
		} else if (humidity >= 60) {
			ivHumitity.setImageResource(R.drawable.humidity3);
		}
		mHumidity.setText(String.format("%.0f%%", humidity));
	}

	private int[] mPressureCals;

	private void updatePressureCals(BluetoothGattCharacteristic characteristic) {
		mPressureCals = SensorTagData.extractCalibrationCoefficients(characteristic);
	}

	private void updatePressureValue(BluetoothGattCharacteristic characteristic) {
		if (mPressureCals == null)
			return;
		double pressure = SensorTagData.extractBarometer(characteristic, mPressureCals);
		double temp = SensorTagData.extractBarTemperature(characteristic, mPressureCals);

		mTemperature.setText(String.format("%.1f\u00B0C", temp));
	}

	private void updateProximity(int rssi) {
		// show notification to the user

		if (rssi <= -90) {
			ivProximity.setImageResource(R.drawable.signal1);
			mProximity.setText(String.format("%.0f", (float) rssi));
			mProximity.setTextColor(Color.rgb(255, 0, 0));
			if (rssi <= -90 && !notificationSent) {
				mHandler.postAtFrontOfQueue(showNotification);
				notificationSent = true;
			}
			return;
		} else if (rssi <= -80) {
			mProximity.setText(String.format("%.0f", (float) rssi));
			mProximity.setTextColor(Color.rgb(255, 0, 0));
			ivProximity.setImageResource(R.drawable.signal2);
		} else if (rssi <= -70) {
			mProximity.setText(String.format("%.0f", (float) rssi));
			mProximity.setTextColor(Color.rgb(255, 50, 0));
			ivProximity.setImageResource(R.drawable.signal3);
		} else if (rssi <= -60) {
			mProximity.setText(String.format("%.0f", (float) rssi));
			mProximity.setTextColor(Color.rgb(0, 155, 0));
			ivProximity.setImageResource(R.drawable.signal4);
		} else {
			mProximity.setText(String.format("%.0f", (float) rssi));
			mProximity.setTextColor(Color.rgb(0, 255, 0));
			ivProximity.setImageResource(R.drawable.signal5);
		}
		notificationSent = false;
	}

	private Runnable showNotification = new Runnable() {
		@Override
		public void run() {
			// Define the Notification's expanded message and Intent:

			mContentView.setTextViewText(R.id.text, contentText);

			// Build the Notification

			Notification.Builder notificationBuilder = new Notification.Builder(getApplicationContext()).setTicker(tickerText)
					.setSmallIcon(android.R.drawable.stat_sys_warning).setAutoCancel(true).setContentIntent(mContentIntent).setSound(soundURI)
					.setVibrate(mVibratePattern).setContent(mContentView);

			// Pass the Notification to the NotificationManager:
			NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			mNotificationManager.notify(MY_NOTIFICATION_ID, notificationBuilder.build());
		}

	};
}

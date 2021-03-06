/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.restracks.android.ble;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.chart.PointStyle;
import org.achartengine.model.SeriesSelection;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class DeviceControlActivity extends Activity {
    private SharedPreferences SP;
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    public enum RunningState{start,stopped,pause}

    private RunningState curState=RunningState.stopped;
    private TextView mConnectionState;
    private TextView mDataField;
    private TextView mDataFieldHeartRate;
    private Chronometer mChronometer;
    private String mDeviceName;
    private String mDeviceAddress;
    private ExpandableListView mGattServicesList;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private ArrayList<Beat> recordedData = new ArrayList<Beat>();

    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";
    private long pauseBased=0;

    // Define a graph
    GraphicalView mChart;
    XYSeries xSeries=new XYSeries("X Series");
    int measureMoment=0;
    public int curRate;

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
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
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
                mChronometer.start(SystemClock.elapsedRealtime());
                ((Button)findViewById(R.id.btnStartPause)).setText("Pause");
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                mChronometer.stop();
                ((Button)findViewById(R.id.btnStartPause)).setText("Start");
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    // If a given GATT characteristic is selected, check for supported features.  This sample
    // demonstrates 'Read' and 'Notify' features.  See
    // http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete
    // list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListner =
            new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
                                            int childPosition, long id) {
                    if (mGattCharacteristics != null) {
                        final BluetoothGattCharacteristic characteristic =
                                mGattCharacteristics.get(groupPosition).get(childPosition);
                        final int charaProp = characteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(characteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = characteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    characteristic, true);
                        }
                        return true;
                    }
                    return false;
                }
    };

    private void clearUI() {
        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        mDataField.setText(R.string.no_data);
        mDataFieldHeartRate.setText(R.string.no_data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);
        SP = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
        mGattServicesList.setOnChildClickListener(servicesListClickListner);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mDataField = (TextView) findViewById(R.id.data_value);
        mDataFieldHeartRate = (TextView) findViewById(R.id.data_value_heartrate);
        mChronometer = (Chronometer)findViewById(R.id.chronoss);

        getActionBar().setTitle(mDeviceName);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        // Check in settings whether to display graph
        //if (SP.getBoolean("pref_showgraph",true)){
           CreateMainChart();
        //}

        // Check in settings whether to display start/stop
        //Boolean showStartStopButtons = SP.getBoolean("pref_startstop", true);
        //findViewById(R.id.btnSetStartStop).setVisibility(showStartStopButtons ? View.VISIBLE : View.GONE);
    }

    private long resumeTime;
    private boolean reset;
    public void startChrono(View view) {
        CharSequence btnText = ((Button)findViewById(R.id.btnStartPause)).getText();

        if (reset){
            mChronometer.start(SystemClock.elapsedRealtime());
            reset=false;
            ((Button)findViewById(R.id.btnStartPause)).setText("Pause");

            // Clear the chart
            xSeries.clear();
            mChart.repaint();

            return;
        }

        if (btnText.equals("Pause")){
            // We are in pause, continue
            resumeTime  = SystemClock.elapsedRealtime() - mChronometer.getBase();
            mChronometer.stop();
            ((Button)findViewById(R.id.btnStartPause)).setText("Start");
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("Would you like to store results (to: email / Google drive /etc)?").setPositiveButton("Yes", dialogClickListener)
                    .setNegativeButton("No", dialogClickListener).show();
        }
        else{
            mChronometer.start(SystemClock.elapsedRealtime() - resumeTime);
            ((Button)findViewById(R.id.btnStartPause)).setText("Pause");
        }
    }

    public void stopChrono(View view){
        mChronometer.stop();
        ((Chronometer)findViewById(R.id.chronoss)).setText("00:00:00");

        //PATCH: Reacreate the chart by rebuilding it
        CreateMainChart();
        mChart.repaint();
        reset=true;
    }

    private void CreateMainChart()
    {
        LinearLayout chart_container=(LinearLayout)findViewById(R.id.Chart_layout);

        chart_container.removeAllViews();

        // Create a Dataset to hold the XSeries.
        XYMultipleSeriesDataset dataset =new XYMultipleSeriesDataset();
        dataset.addSeries(xSeries);

        // Create XYSeriesRenderer to customize XSeries
        XYSeriesRenderer Xrenderer=new XYSeriesRenderer();
        Xrenderer.setColor(Color.GREEN);
        Xrenderer.setPointStyle(PointStyle.DIAMOND);
        Xrenderer.setDisplayChartValues(true);
        Xrenderer.setLineWidth(2);
        Xrenderer.setFillPoints(true);

        // Create XYMultipleSeriesRenderer to customize the whole chart
        XYMultipleSeriesRenderer mRenderer=new XYMultipleSeriesRenderer();

        //todo: Would be nice to have [time] on X-axis, to save display space removed for now
        mRenderer.setXTitle("Time");
        mRenderer.setXLabels(0);
        mRenderer.setLabelsTextSize(22);
        mRenderer.setPanEnabled(false);
        mRenderer.setShowGrid(true);
        mRenderer.setClickEnabled(true);
        mRenderer.setInScroll(true);
        mRenderer.setShowLegend(false);
        mRenderer.setZoomEnabled(false);

        // Adding the XSeriesRenderer to the MultipleRenderer.
        mRenderer.addSeriesRenderer(Xrenderer);


        // Creating an intent to plot line chart using dataset and multipleRenderer
        mChart= ChartFactory.getCubeLineChartView(getBaseContext(), dataset, mRenderer, 0.3f);

        // Add the graphical view mChart object into the Linear layout .
        chart_container.addView(mChart);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
//            case R.id.menu_gmap:
//                Intent mapIntent = new Intent("com.restracks.android.ble.MapsActivity.HOME");
//                //mapIntent.putExtra("HEARTRATE",curRate);
//                startActivity(mapIntent);
//                return true;
            case R.id.menu_settings:
                Intent prefIntent = new Intent("android.intent.action.PREFS");
                startActivity(prefIntent);
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;

        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayData(String data) {
        if (data != null) {
            mDataField.setText(data);
            mDataFieldHeartRate.setText(data);

            DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date date = new Date();
            int currentRate=Integer.parseInt(data);
            recordedData.add(new Beat(currentRate,date));

            // MvK: The option to turn off the graph is disabled in the settings panel for now:
           // If we do display a Graph, make sure to update it
           //  if (SP.getBoolean("pref_showgraph",true)){
           //     if (!SP.getBoolean("pref_startstop",true) | SP.getBoolean("pref_startstop",true) &&
           if (mChronometer.getStarted()){
                xSeries.add(measureMoment++,Integer.parseInt(data));

                if (xSeries.getItemCount() > 300 ){
                    xSeries.remove(0);
                }
                mChart.repaint();
            }
           // }
        }
    }

    // Iterate through the supported GATT Services/Characteristics
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharaString = getResources().getString(R.string.unknown_characteristic);
        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            //currentServiceData.put(LIST_NAME, SampleGattAttributes.lookup(uuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            List<String> vals = new ArrayList<String>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                currentCharaData.put(LIST_NAME, SampleGattAttributes.lookup(uuid, unknownCharaString));
                currentCharaData.put(LIST_UUID, uuid);
                //currentCharaData.put(, gattCharacteristic.getStringValue(0));
                gattCharacteristicGroupData.add(currentCharaData);

                try{
                    if (uuid.equals("00002a37-0000-1000-8000-00805f9b34fb")){
                        if (mGattCharacteristics != null) {
                            BluetoothGattCharacteristic characteristic =gattCharacteristic;
                            int charaProp = characteristic.getProperties();
                            if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                                // If there is an active notification on a characteristic, clear
                                // it first so it doesn't update the data field on the user interface.
                                if (mNotifyCharacteristic != null) {
                                    mBluetoothLeService.setCharacteristicNotification(
                                            mNotifyCharacteristic, false);
                                    mNotifyCharacteristic = null;
                                }
                                mBluetoothLeService.readCharacteristic(characteristic);
                            }
                            if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                mNotifyCharacteristic = characteristic;
                                mBluetoothLeService.setCharacteristicNotification(
                                        characteristic, true);
                            }
                        }
                    }
                }
                catch(Exception ex){

                }
            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }
    }

    DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            switch (which){
                case DialogInterface.BUTTON_POSITIVE:
                    //Yes button clicked
                    String state = Environment.getExternalStorageState();
                    if (Environment.MEDIA_MOUNTED.equals(state)) {
                        // we can write
                        String curDateTime = java.text.DateFormat.getDateTimeInstance().format(Calendar.getInstance().getTime());
                        String fileName = "AlphaMonitor Result " + curDateTime.replace(":","") + ".txt";
                        File fileResult = new File(Environment.getExternalStorageDirectory(), fileName);
                        FileOutputStream stream = null;

                        try {
                            stream = new FileOutputStream(fileResult);
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        }
                        try {
                            stream.write(GetRecordedRateDataJSON().getBytes());

                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                stream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        Uri uri = Uri.fromFile(fileResult);

                        Intent shareIntent = new Intent();
                        shareIntent.setAction(Intent.ACTION_SEND);
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, fileName );
                        shareIntent.putExtra(Intent.EXTRA_TEXT, "Contained within the attachment of this message are the AlphaMonitor heartrate reading results.");

                        shareIntent.setType("text/plain");

                        try {
                            startActivity(Intent.createChooser(shareIntent, "Share results..."));
                        } catch (android.content.ActivityNotFoundException ex) {
                            Toast.makeText(DeviceControlActivity.this, "There are no applications installed to share this with.", Toast.LENGTH_SHORT).show();
                        }
                    }
                    else{
                        Toast.makeText(DeviceControlActivity.this, "There is not external storage available for saving the data to.", Toast.LENGTH_SHORT).show();
                    }

                    break;
                case DialogInterface.BUTTON_NEGATIVE:
                    // No button clicked
                    // No need to do anything furthermore
                    break;
            }
        }
    };

    private String GetRecordedRateDataJSON() {
        Type collectionType = new TypeToken<List<Beat>>(){}.getType();
        return new Gson().toJson(recordedData, collectionType);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
}

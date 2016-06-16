package com.example.matheus.sleepwatch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.apache.commons.lang3.SerializationUtils;

import java.util.ArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Service that logs data for user determined time.
 * Sends data back to phone for writing.
 */
public class WearSensorLogService extends WearableListenerService implements SensorEventListener  {

    public static final String TAG = "WearSensorLogService";

    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private Sensor mGyroscope;
    private ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1);
    private ArrayList<ThreeTupleRecord> mWatchAccelerometerRecords = new ArrayList<ThreeTupleRecord>();
    private ArrayList<ThreeTupleRecord> mWatchGyroscopeRecords = new ArrayList<ThreeTupleRecord>();
    private ArrayList<ThreeTupleRecord> mWatchAccelerometerRecordsToSave = new ArrayList<ThreeTupleRecord>();
    private ArrayList<ThreeTupleRecord> mWatchGyroscopeRecordsToSave = new ArrayList<ThreeTupleRecord>();
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private GoogleApiClient mGoogleApiClient;
    private String username;
    private String activityName;
    private boolean timedMode;
    private long logDelay = 5000;
    private long phoneToWatchDelay;

    public WearSensorLogService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
    }

    @Override//?????????????????
    public int onStartCommand(Intent intent, int flags, int startId) {
        int minutes = 15;
        int samplingRate = intent.getIntExtra("SAMPLING_RATE", 0);


        Log.d(TAG, "Service Started. Username: " + username + ", Activity: " + activityName +
                ", Sampling Rate: " + samplingRate + ", Minutes: " + minutes);
        registerListeners(minutes, samplingRate);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sendData(); //send the data to the phone

        if (null != mGoogleApiClient && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    /**
     * This method acquires and registers the sensors and the wake lock for sensor sampling.
     * Listeners are registered after a delay to allow user to set up phone/watch position if needed.
     * Listeners are unregistered after user specified time if in timed mode. Otherwise, waits for
     * user to stop service manually via stop button.
     */
    private void registerListeners(final int minutes, final int samplingRate) {
        getSensors();
        acquireWakeLock();

        // Register the Sensors and
         exec.schedule(new Runnable() {
            @Override
            public void run() {
                mSensorManager.registerListener(WearSensorLogService.this, mAccelerometer, samplingRate);
                mSensorManager.registerListener(WearSensorLogService.this, mGyroscope, samplingRate);
                Log.d(TAG, "Start: " + System.currentTimeMillis());
                scheduleLogStop(1); //schedule the stop of collection in 1 minute
            }
        }, 1, TimeUnit.MILLISECONDS); //schedule to initiate in 1 milisecond
    }

    /**
     * Unregister sensor listener after specified minutes
     * @param minutes
     */
    private void scheduleLogStop(int minutes) {
        exec.schedule(new Runnable() {
            @Override
            public void run() {
                mSensorManager.unregisterListener(WearSensorLogService.this, mAccelerometer);
                mSensorManager.unregisterListener(WearSensorLogService.this, mGyroscope);
                Log.d(TAG, "End: " + System.currentTimeMillis());
                Log.d(TAG, "About to stop service...");
                calculateTheAverage();
              //  stopSelf();
            }
        }, minutes, TimeUnit.MINUTES);
    }

    /**
     * Get the accelerometer and gyroscope if available on device
     */
    private void getSensors() {

        if (mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null){
            mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
    }

    /**
     * Acquire wake lock to sample with the screen off.
     * Wake locks are reference counted. See for more details:
     * http://stackoverflow.com/questions/5920798/wakelock-finalized-while-still-held
     */
    private void acquireWakeLock() {
        //
        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
    }

    /**
     * comment
     */
    private void sendData() {
        Log.d(TAG, "Sending data from watch to phone");
        Asset accelAsset = Asset.createFromBytes(SerializationUtils.serialize(mWatchAccelerometerRecordsToSave));
        Asset gyroAsset = Asset.createFromBytes(SerializationUtils.serialize(mWatchGyroscopeRecordsToSave));

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();

        PutDataMapRequest dataMap = PutDataMapRequest.create("/data");
        dataMap.getDataMap().putAsset("ACCEL_ASSET", accelAsset);
        dataMap.getDataMap().putAsset("GYRO_ASSET", gyroAsset);
       // dataMap.getDataMap().putString("USERNAME", username);
       // dataMap.getDataMap().putString("ACTIVITY_NAME", activityName);
        PutDataRequest request = dataMap.asPutDataRequest();
        PendingResult<DataApi.DataItemResult> pendingResult = Wearable.DataApi
                .putDataItem(mGoogleApiClient, request);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                mWatchAccelerometerRecords.add(
                        new ThreeTupleRecord(
                                event.timestamp, event.values[0], event.values[1], event.values[2]));
                break;
            case Sensor.TYPE_GYROSCOPE:
                mWatchGyroscopeRecords.add(
                        new ThreeTupleRecord(
                                event.timestamp, event.values[0], event.values[1], event.values[2]));
                break;
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    /**
     * Calculates the average of the value collected in 1 minute. To this application
     *  we only want one value that express his position in that time.
     */
    public void calculateTheAverage()
    {
        ThreeTupleRecord av = new ThreeTupleRecord(0,0,0,0);
        ThreeTupleRecord item = new ThreeTupleRecord(0,0,0,0);
        int qtd = 0;
        //average for the Accelerometer
        for(int i = 0; i < mWatchAccelerometerRecords.size(); i++)
        {
            item = mWatchAccelerometerRecords.get(i);
            av.setAll(av.getX() + item.getX(), av.getY() + item.getY(), av.getZ() + item.getZ());
            qtd++;
        }
        av.setAll(av.getX()/qtd, av.getY()/qtd, av.getZ()/qtd);
        //save the value in a new object in the array that actually is gonna be saved in the file
        mWatchAccelerometerRecordsToSave.add(new ThreeTupleRecord(0,av.getX(), av.getY(),av.getZ()));

        av.setAll(0,0,0);
        qtd = 0;
        for(int i = 0; i < mWatchGyroscopeRecords.size(); i++)
        {
            item =mWatchGyroscopeRecords.get(i);
            av.setAll(av.getX() + item.getX(), av.getY() + item.getY(), av.getZ() + item.getZ());
            qtd++;
        }
        av.setAll(av.getX()/qtd, av.getY()/qtd, av.getZ()/qtd);
        //save the value in a new object in the array that actually is gonna be saved in the file
        mWatchGyroscopeRecordsToSave.add(new ThreeTupleRecord(0,av.getX(), av.getY(),av.getZ()));

        //clear the arrays for the next iterarion
        mWatchGyroscopeRecords.clear();
        mWatchGyroscopeRecordsToSave.clear();
    }
}
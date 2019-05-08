package com.sai.drowsydriver;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.app.ProgressDialog;

import org.opencv.android.*;
import org.opencv.core.Mat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import zephyr.android.HxMBT.BTClient;

public class MainActivity extends Activity implements CameraBridgeViewBase.CvCameraViewListener2 {
    public static final String KEY_RESTING_HR = "RestingHR";
    public static final String KEY_PHONE = "PhoneNumber";
    public static final String KEY_EMAIL = "EmailAddress";
    public static final String KEY_ACCEL = "UseAcceleration";
    private static final int INVALID_RESTING_HR = 0;
    public static final String PREF = "MyPref";
    private static String TAG = "MainActivity";

    private BluetoothAdapter mBtAdapter = null;
    private BTClient mBtClient;
    private NewConnectedListener mNewConnListener;
    private ProgressDialog mRestingHRProgress = null;

    private CameraBridgeViewBase mOpenCvCameraView;
    private TextView mCurrentHeartRate, mHrStatus, mRestingHrLabel, mSpeedLabel, mYawnLabel, mEyeLabel, mAccelerationLabel;
    private Button mSetRestingHRBtn;
    private int mRestingHRCount = 0;
    private int mRestingHR = 0;
    private long mRestingHRSum = 0;
    private static int mRestingHRMaxCount = 20;
    private SharedPreferences mPref;
    private MediaPlayer mWakeupAlarm;
    private MediaPlayer mAbnormalAlarm;
    private boolean mShowingWarning = false;
    private ImageDetector mImageDetector = null;
    private LocationManager mLocationManager = null;
    private boolean mFirstSpeedDetected = false;
    private float mLastSpeed = 0.f;
    private long mLastSpeedTime = 0;
    private float mAcceleration = 0;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mImageDetector = new ImageDetector(getBaseContext());
                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mPref = getApplicationContext().getSharedPreferences(PREF, 0);
        mWakeupAlarm = MediaPlayer.create(getApplicationContext(), R.raw.wakeup);
        mAbnormalAlarm = MediaPlayer.create(getApplicationContext(), R.raw.abnormal);

        mWakeupAlarm.setLooping(true);
        mAbnormalAlarm.setLooping(true);

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.HelloOpenCvView);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        _setupHR();
        _setupRestingHR();

        mLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        mSpeedLabel = (TextView)findViewById(R.id.labelSpeed);
        mYawnLabel = (TextView)findViewById(R.id.labelYawn);
        mEyeLabel = (TextView)findViewById(R.id.labelEye);
        mAccelerationLabel = (TextView)findViewById(R.id.labelAcceleration);
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(i);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_10, this, mLoaderCallback);
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mLocationListener);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();

        if(mWakeupAlarm != null && mWakeupAlarm.isPlaying()) {
            mWakeupAlarm.pause();
        }

        if(mAbnormalAlarm != null && mAbnormalAlarm.isPlaying()) {
            mAbnormalAlarm.pause();
        }

        mLocationManager.removeUpdates(mLocationListener);
    }

    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();

        if(mBtClient != null && mBtClient.IsConnected()) {
			/*This disconnects listener from acting on received messages*/
            mBtClient.removeConnectedEventListener(mNewConnListener);

			/*Close the communication with the device & throw an exception if failure*/
            mBtClient.Close();
        }
    }

    public void onCameraViewStarted(int width, int height) {
    }

    public void onCameraViewStopped() {
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat rgba = inputFrame.rgba();

        final ImageDetector.DetectionResult result = mImageDetector.Detect(inputFrame.gray(), rgba);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mEyeLabel.setText(result.eye);
                mYawnLabel.setText(result.yawn);
            }
        });

        if(result.sleep &&
           (!mPref.getBoolean(MainActivity.KEY_ACCEL, true) || ((mAcceleration < -0.00025) && (mAcceleration > -0.0005)))) {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    OnDrowsyCondition();
                }
            });
        }

        return rgba;
    }

    private void _setupRestingHR() {
        mRestingHrLabel = (TextView)findViewById(R.id.labelRestingHR);
        mRestingHR = mPref.getInt(KEY_RESTING_HR, INVALID_RESTING_HR);
        if(mRestingHR == INVALID_RESTING_HR) {
            mRestingHrLabel.setText("doesn't exist");
        } else {
            mRestingHrLabel.setText("" + mRestingHR);
        }

        mSetRestingHRBtn =  (Button)findViewById(R.id.btnSetRestingHR);
        mSetRestingHRBtn.setEnabled(false);
        mSetRestingHRBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRestingHRProgress = new ProgressDialog(v.getContext());
                mRestingHRProgress.setCancelable(false);
                mRestingHRProgress.setMessage("Calibrating ...");
                mRestingHRProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                mRestingHRProgress.setProgress(0);
                mRestingHRProgress.setMax(mRestingHRMaxCount);

                mRestingHRCount = 0;
                mRestingHRSum = 0;
                mRestingHRProgress.show();
            }
        });
    }

    private void _setupHR() {
        mCurrentHeartRate = (TextView)findViewById(R.id.labelHeartRate);
        mHrStatus = (TextView)findViewById(R.id.labelHRStatus);
        mHrStatus.setText("Searching...");

        IntentFilter filter = new IntentFilter("android.bluetooth.device.action.PAIRING_REQUEST");
        /*Registering a new BTBroadcast receiver from the Main Activity context with pairing request event*/
        this.getApplicationContext().registerReceiver(new BTBroadcastReceiver(), filter);
        // Registering the BTBondReceiver in the application that the status of the receiver has changed to Paired
        IntentFilter filter2 = new IntentFilter("android.bluetooth.device.action.BOND_STATE_CHANGED");
        this.getApplicationContext().registerReceiver(new BTBondReceiver(), filter2);

        SearchHeartRateTask search = new SearchHeartRateTask();
        search.execute();
    }

    private class SearchHeartRateTask extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... params) {
            String result = "";
            mBtAdapter = BluetoothAdapter.getDefaultAdapter();
            Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();
            if (pairedDevices.size() > 0)
            {
                for (BluetoothDevice device : pairedDevices)
                    if (device.getName().startsWith("HXM")) {
                        BluetoothDevice btDevice = device;
                        String BhMacID = btDevice.getAddress();
                        BluetoothDevice Device = mBtAdapter.getRemoteDevice(BhMacID);
                        String DeviceName = Device.getName();
                        mBtClient = new BTClient(mBtAdapter, BhMacID);
                        mNewConnListener = new NewConnectedListener(mHeartRateHandler);
                        mBtClient.addConnectedEventListener(mNewConnListener);

                        if (mBtClient.IsConnected())
                            result = DeviceName;
                        break;
                    }
            }
            return result;
        }

        @Override
        protected void onPostExecute(String result) {
            if(!result.isEmpty())
            {
                mBtClient.start();
                mHrStatus.setText("Connected to " + result);
                mSetRestingHRBtn.setEnabled(true);
            }
            else
            {
                mHrStatus.setText("Unable to Connect !");
            }
        }
    }

    private Handler mHeartRateHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0x100:
                    String hrStr = msg.getData().getString("HeartRate");
                    mCurrentHeartRate.setText(hrStr);
                    int hr = Integer.parseInt(hrStr);

                    if (mRestingHRProgress != null && mRestingHRProgress.isShowing()) {
                        mRestingHRCount++;

                        mRestingHRProgress.setProgress(mRestingHRCount);
                        mRestingHRSum += (long) hr;

                        if (mRestingHRCount == mRestingHRMaxCount) {
                            mRestingHRProgress.dismiss();
                            mRestingHR = (int) (mRestingHRSum / (long) mRestingHRMaxCount);
                            mRestingHrLabel.setText("" + mRestingHR);

                            SharedPreferences.Editor editor = mPref.edit();
                            editor.putInt(KEY_RESTING_HR, mRestingHR);
                            editor.commit();
                        }
                    } else if (mRestingHR != INVALID_RESTING_HR && hr > 0) {
                        if (((float) hr) < 0.85 * ((float) mRestingHR)) {
                            OnDrowsyCondition();
                            Log.w("HR", "Drowsy Condition Executed");
                        } else if (((float) hr) > 1.5 * ((float) mRestingHR)) {
                            OnAbnormalCondition();
                            Log.w("HR", "Abnormal Condition Executed");
                        }
                    }
                    break;
            }
        }

    };

    private class BTBondReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle b = intent.getExtras();
            BluetoothDevice device = mBtAdapter.getRemoteDevice(b.get("android.bluetooth.device.extra.DEVICE").toString());
            Log.d("Bond state", "BOND_STATED = " + device.getBondState());
        }
    }

    private class BTBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("BTIntent", intent.getAction());
            Bundle b = intent.getExtras();
            Log.d("BTIntent", b.get("android.bluetooth.device.extra.DEVICE").toString());
            Log.d("BTIntent", b.get("android.bluetooth.device.extra.PAIRING_VARIANT").toString());
            try {
                BluetoothDevice device = mBtAdapter.getRemoteDevice(b.get("android.bluetooth.device.extra.DEVICE").toString());
                Method m = BluetoothDevice.class.getMethod("convertPinToBytes", new Class[] {String.class} );
                byte[] pin = (byte[])m.invoke(device, "1234");
                m = device.getClass().getMethod("setPin", new Class [] {pin.getClass()});
                Object result = m.invoke(device, pin);
                Log.d("BTTest", result.toString());
            } catch (SecurityException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            } catch (NoSuchMethodException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            } catch (IllegalArgumentException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            float curSpeed = location.getSpeed();
            long curTime = System.currentTimeMillis();

            if(mFirstSpeedDetected) {
                mAcceleration = (curSpeed - mLastSpeed) / ((float)(curTime - mLastSpeedTime));
            }

            Log.d(TAG, "" + curSpeed);
            mSpeedLabel.setText("" + curSpeed);
            mAccelerationLabel.setText("" + mAcceleration);

            mFirstSpeedDetected = true;
            mLastSpeedTime = curTime;
            mLastSpeed = curSpeed;
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    private void OnDrowsyCondition() {
        if (!mShowingWarning && !mWakeupAlarm.isPlaying()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            try {
                mWakeupAlarm.start();
            } catch (Exception e) {
                e.printStackTrace();
            }

            builder
                .setTitle("Drowsy Driver")
                .setMessage("Sleepy condition detected. Please take a break.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mShowingWarning = false;
                        if (mWakeupAlarm.isPlaying())
                            mWakeupAlarm.pause();
                    }
                })
                .show();

            mShowingWarning = true;

            SendSmsEmailTask task = new SendSmsEmailTask();
            task.execute(new String[] {"Sleepy condition detected. Please take a break."});
        }
    }

    private void OnAbnormalCondition() {
        if (!mShowingWarning) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            try {
                mAbnormalAlarm.start();
            } catch (Exception e) {
                e.printStackTrace();
            }

            builder
                .setTitle("Abnormal Health Condition")
                .setMessage("Your Heart Rate is more than normal condition. Please see the doctor.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mShowingWarning = false;
                        if(mAbnormalAlarm.isPlaying())
                            mAbnormalAlarm.pause();
                    }
                })
                .show();

            mShowingWarning = true;
            SendSmsEmailTask task = new SendSmsEmailTask();
            task.execute(new String[] { "Your Heart Rate is more than normal condition. Please see the doctor." });
        }
    }

    private class SendSmsEmailTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... params) {

            for(String param : params) {
                Utils.sms(getApplicationContext(), mPref.getString(KEY_PHONE, "3605566277"), param);
                Utils.email(getApplicationContext(), mPref.getString(KEY_EMAIL, "DrowsyDriverDoNotReply@gmail.com"), param);

            }
            return null;
        }
    }
}



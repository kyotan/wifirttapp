package com.example.wifirtt;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WifiRttApp";
    private static final String SERVICE_NAME = "My_Rtt_Service";
    private static final int REQUEST_CODE_PERMISSIONS = 100;
    private static final long RANGING_INTERVAL_MS = 2000; // 2秒ごとに距離を計測

    private WifiAwareManager mWifiAwareManager;
    private WifiAwareSession mWifiAwareSession;
    private WifiRttManager mWifiRttManager;
    private TextView mStatusTextView;
    private TextView mPeerInfoTextView;
    private TextView mRttResultTextView;
    private RadioGroup mRoleRadioGroup;
    private RadioButton mRadioPublisher;
    private RadioButton mRadioSubscriber;
    private Button mStartButton;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private PeerHandle mPeerHandle;
    private boolean isPublisher = false;
    private boolean isSubscriber = false;

    private Handler mRangingHandler = new Handler(Looper.getMainLooper());
    private Runnable mRangingRunnable;
    private SubscribeDiscoverySession mCurrentSubscribeSession;
    private PublishDiscoverySession mCurrentPublishSession;


    private BroadcastReceiver mWifiAwareStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED.equals(intent.getAction())) {
                if (mWifiAwareManager != null && mWifiAwareManager.isAvailable()) {
                    Log.d(TAG, "Wi-Fi Aware is available");
                    mStatusTextView.setText("Wi-Fi Aware is available. Select role and start.");
                } else {
                    Log.d(TAG, "Wi-Fi Aware is not available");
                    mStatusTextView.setText("Wi-Fi Aware is not available");
                    if (mWifiAwareSession != null) {
                        mWifiAwareSession.close(); // Close existing session
                        mWifiAwareSession = null;
                    }
                    stopPeriodicRanging(); // Stop ranging if Aware becomes unavailable
                    // Also close discovery sessions if they exist
                    if (mCurrentPublishSession != null) {
                        mCurrentPublishSession.close();
                        mCurrentPublishSession = null;
                    }
                    if (mCurrentSubscribeSession != null) {
                        mCurrentSubscribeSession.close();
                        mCurrentSubscribeSession = null;
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusTextView = findViewById(R.id.statusTextView);
        mPeerInfoTextView = findViewById(R.id.peerInfoTextView);
        mRttResultTextView = findViewById(R.id.rttResultTextView);
        mRoleRadioGroup = findViewById(R.id.roleRadioGroup);
        mRadioPublisher = findViewById(R.id.radioPublisher);
        mRadioSubscriber = findViewById(R.id.radioSubscriber);
        mStartButton = findViewById(R.id.startButton);

        mWifiAwareManager = (WifiAwareManager) getSystemService(Context.WIFI_AWARE_SERVICE);
        mWifiRttManager = (WifiRttManager) getSystemService(Context.WIFI_RTT_RANGING_SERVICE);

        IntentFilter filter = new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
        registerReceiver(mWifiAwareStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);


        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Stop any existing sessions before starting a new one
                if (mCurrentPublishSession != null) {
                    mCurrentPublishSession.close();
                    mCurrentPublishSession = null;
                }
                if (mCurrentSubscribeSession != null) {
                    mCurrentSubscribeSession.close();
                    mCurrentSubscribeSession = null;
                }
                stopPeriodicRanging();
                mRttResultTextView.setText("");
                mPeerInfoTextView.setText("");

                int selectedId = mRoleRadioGroup.getCheckedRadioButtonId();
                if (selectedId == -1) {
                    Toast.makeText(MainActivity.this, "Please select a role (Publisher or Subscriber)", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (selectedId == mRadioPublisher.getId()) {
                    isPublisher = true;
                    isSubscriber = false;
                    mStatusTextView.setText("Role: Publisher. Initializing Wi-Fi Aware...");
                } else if (selectedId == mRadioSubscriber.getId()) {
                    isPublisher = false;
                    isSubscriber = true;
                    mStatusTextView.setText("Role: Subscriber. Initializing Wi-Fi Aware...");
                }
                checkPermissionsAndStartWifiAware();
            }
        });

        mRangingRunnable = new Runnable() {
            @Override
            public void run() {
                if (mPeerHandle != null && mWifiAwareSession != null && isSubscriber && mCurrentSubscribeSession != null) {
                    startRanging(mPeerHandle);
                    // Reschedule the runnable
                    mRangingHandler.postDelayed(this, RANGING_INTERVAL_MS);
                }
            }
        };

        // Initial check for Wi-Fi Aware availability
        if (mWifiAwareManager != null && mWifiAwareManager.isAvailable()) {
            mStatusTextView.setText("Wi-Fi Aware is available. Select role and start.");
        } else {
            mStatusTextView.setText("Wi-Fi Aware is not available.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mWifiAwareStateReceiver);
        stopPeriodicRanging(); // Stop ranging when activity is destroyed
        if (mCurrentPublishSession != null) {
            mCurrentPublishSession.close();
            mCurrentPublishSession = null;
        }
        if (mCurrentSubscribeSession != null) {
            mCurrentSubscribeSession.close();
            mCurrentSubscribeSession = null;
        }
        if (mWifiAwareSession != null) {
            mWifiAwareSession.close();
            mWifiAwareSession = null;
        }
    }

    private void checkPermissionsAndStartWifiAware() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES}, REQUEST_CODE_PERMISSIONS);
        } else {
            attachWifiAware();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allPermissionsGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            if (allPermissionsGranted) {
                attachWifiAware();
            } else {
                Toast.makeText(this, "Permissions denied. Wi-Fi Aware/RTT cannot function.", Toast.LENGTH_LONG).show();
                mStatusTextView.setText("Permissions denied");
            }
        }
    }

    private void attachWifiAware() {
        if (mWifiAwareManager == null || !mWifiAwareManager.isAvailable()) {
            mStatusTextView.setText("Wi-Fi Aware is not available. Cannot attach.");
            return;
        }
        if (mWifiAwareSession != null) {
            Log.d(TAG, "Already attached to Wi-Fi Aware session or in process. Performing selected role.");
            // If session exists, proceed to role-specific actions
             if (isPublisher) {
                publishService();
            }
            if (isSubscriber) {
                subscribeService();
            }
            return;
        }

        mStatusTextView.setText("Attaching to Wi-Fi Aware...");
        mWifiAwareManager.attach(new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession session) {
                mWifiAwareSession = session;
                Log.d(TAG, "Wi-Fi Aware session attached.");
                mStatusTextView.setText("Wi-Fi Aware session attached.");
                if (isPublisher) {
                    publishService();
                }
                if (isSubscriber) {
                    subscribeService();
                }
            }

            @Override
            public void onAttachFailed() {
                Log.e(TAG, "Failed to attach to Wi-Fi Aware session.");
                mStatusTextView.setText("Failed to attach to Wi-Fi Aware session.");
                mWifiAwareSession = null; // Ensure session is null on failure
            }

            @Override
            public void onAwareSessionTerminated() {
                Log.e(TAG, "Wi-Fi Aware session terminated.");
                mStatusTextView.setText("Wi-Fi Aware session terminated.");
                mWifiAwareSession = null;
                stopPeriodicRanging();
                 if (mCurrentPublishSession != null) {
                    mCurrentPublishSession.close();
                    mCurrentPublishSession = null;
                }
                if (mCurrentSubscribeSession != null) {
                    mCurrentSubscribeSession.close();
                    mCurrentSubscribeSession = null;
                }
            }
        }, mHandler);
    }

    private void publishService() {
        if (mWifiAwareSession == null) {
            mStatusTextView.setText("Publisher: Wi-Fi Aware session not available.");
            Log.e(TAG, "publishService: Wi-Fi Aware session is null");
            return;
        }
        if (mCurrentPublishSession != null) {
            Log.d(TAG,"Publisher: Already publishing.");
            mStatusTextView.setText("Publisher: Already publishing.");
            return;
        }

        PublishConfig publishConfig = new PublishConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .setRangingEnabled(true)
                .build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            mStatusTextView.setText("Publisher: Permissions not granted.");
            return;
        }
        mStatusTextView.setText("Publisher: Publishing service...");
        mWifiAwareSession.publish(publishConfig, new DiscoverySessionCallback() {
            @Override
            public void onPublishStarted(@NonNull PublishDiscoverySession session) {
                Log.d(TAG, "Publish service started.");
                mStatusTextView.setText("Publisher: Service published.");
                mCurrentPublishSession = session;
            }

            @Override
            public void onSessionTerminated() {
                Log.d(TAG, "Publish session terminated.");
                mStatusTextView.setText("Publisher: Publish session terminated.");
                mCurrentPublishSession = null;
            }

            //@Override
            //public void onSessionConfigFailed(int reason) {
              //  Log.e(TAG, "Publisher: Session config failed: " + reason);
                //mStatusTextView.setText("Publisher: Publish config failed: " + reason);
                //mCurrentPublishSession = null;
            //}
        }, mHandler);
    }

    private void subscribeService() {
        if (mWifiAwareSession == null) {
            mStatusTextView.setText("Subscriber: Wi-Fi Aware session not available.");
            Log.e(TAG, "subscribeService: Wi-Fi Aware session is null");
            return;
        }
         if (mCurrentSubscribeSession != null) {
            Log.d(TAG,"Subscriber: Already subscribing.");
            mStatusTextView.setText("Subscriber: Already subscribing.");
            return;
        }

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .setMinDistanceMm(0)
                .setMaxDistanceMm(100000)
                .build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            mStatusTextView.setText("Subscriber: Permissions not granted.");
            return;
        }
        mStatusTextView.setText("Subscriber: Subscribing to service...");
        mWifiAwareSession.subscribe(subscribeConfig, new DiscoverySessionCallback() {
            @Override
            public void onSubscribeStarted(@NonNull SubscribeDiscoverySession session) {
                Log.d(TAG, "Subscribe service started.");
                mStatusTextView.setText("Subscriber: Service subscription started. Discovering peers...");
                mCurrentSubscribeSession = session;
            }

            @Override
            public void onServiceDiscovered(@NonNull PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                Log.d(TAG, "Service discovered from peer: " + peerHandle.toString());
                mStatusTextView.setText("Subscriber: Service discovered. Starting periodic ranging...");
                if (mCurrentSubscribeSession != null) { // Ensure session is active
                    startPeriodicRanging(peerHandle);
                }
            }

            @Override
            public void onServiceDiscoveredWithinRange(@NonNull PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter, int distanceMm) {
                Log.d(TAG, "Service discovered within range: " + peerHandle.toString() + ", distance: " + distanceMm + "mm");
                mStatusTextView.setText("Subscriber: Service discovered within range. Starting periodic ranging...");
                 if (mCurrentSubscribeSession != null) { // Ensure session is active
                    startPeriodicRanging(peerHandle);
                }
            }

            @Override
            public void onSessionTerminated() {
                Log.d(TAG, "Subscribe session terminated.");
                mStatusTextView.setText("Subscriber: Subscribe session terminated.");
                stopPeriodicRanging();
                mCurrentSubscribeSession = null;
            }

            //@Override
            //public void onSessionConfigFailed(int reason) {
              //  Log.e(TAG, "Subscriber: Session config failed: " + reason);
                //mStatusTextView.setText("Subscriber: Subscribe config failed: " + reason);
                //stopPeriodicRanging();
                //mCurrentSubscribeSession = null;
            //}
        }, mHandler);
    }

    private void startPeriodicRanging(PeerHandle peerHandle) {
        if (!isSubscriber || mCurrentSubscribeSession == null) { // Only start if still subscriber and session active
            Log.d(TAG, "Not starting periodic ranging: Not in subscriber mode or session is not active.");
            return;
        }
        mPeerHandle = peerHandle; // Store the peer handle
        mRangingHandler.removeCallbacks(mRangingRunnable); // Remove any existing callbacks
        mRangingHandler.post(mRangingRunnable); // Start immediately and then periodically
        mStatusTextView.setText("Subscriber: Periodic ranging started.");
    }

    private void stopPeriodicRanging() {
        mRangingHandler.removeCallbacks(mRangingRunnable);
        mPeerHandle = null; // Clear the peer handle
        if (isSubscriber) { // Only update text if it was subscribing
            Log.d(TAG, "Periodic ranging stopped.");
           // mStatusTextView.setText("Subscriber: Periodic ranging stopped."); // Avoid overwriting other status
        }
    }

    private void startRanging(PeerHandle peerHandle) {
        if (mWifiRttManager == null || !mWifiRttManager.isAvailable()) {
            Log.e(TAG, "Wi-Fi RTT is not available.");
            mRttResultTextView.setText("Wi-Fi RTT is not available.");
            stopPeriodicRanging(); // Stop if RTT is not available
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission not granted for RTT.");
            mRttResultTextView.setText("RTT: Location permission needed.");
            stopPeriodicRanging(); // Stop if permission is missing
            return;
        }
        if (peerHandle == null){
            Log.e(TAG, "PeerHandle is null, cannot start ranging.");
            mRttResultTextView.setText("RTT: No peer to range.");
            stopPeriodicRanging();
            return;
        }

        RangingRequest request = new RangingRequest.Builder()
                .addWifiAwarePeer(peerHandle)
                .build();

        mWifiRttManager.startRanging(request, getMainExecutor(), new RangingResultCallback() {
            @Override
            public void onRangingResults(@NonNull List<RangingResult> results) {
                if (results.isEmpty()) {
                    Log.d(TAG, "No RTT results.");
                    // mRttResultTextView.setText("No RTT results."); // Keep previous result if no new one
                    return;
                }

                RangingResult firstValidResult = null;
                for (RangingResult result : results) {
                    if (result.getStatus() == RangingResult.STATUS_SUCCESS) {
                        firstValidResult = result;
                        break;
                    }
                }

                if (firstValidResult != null) {
                    String rttResult = "Distance: " + firstValidResult.getDistanceMm() + "mm, RSSI: " + firstValidResult.getRssi() + "dBm";
                    Log.d(TAG, "RTT Success: " + rttResult);
                    mRttResultTextView.setText(rttResult);

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        android.net.MacAddress mac = firstValidResult.getMacAddress();
                        if (mac != null) {
                            mPeerInfoTextView.setText("Peer MAC: " + mac.toString());
                        } else {
                            mPeerInfoTextView.setText("Peer MAC: NULL");
                        }
                    } else {
                        mPeerInfoTextView.setText("Peer MAC: NULL (API < Q)");
                    }
                } else {
                     Log.e(TAG, "RTT Failed: No successful result. Last status: " + results.get(results.size() -1).getStatus());
                    // mRttResultTextView.setText("RTT Failed: No success. Status: " + results.get(results.size() -1).getStatus());
                }
            }

            @Override
            public void onRangingFailure(int code) {
                Log.e(TAG, "RTT Ranging failed entirely: " + code);
                mRttResultTextView.setText("RTT Ranging failed: " + code);
                // Consider if periodic ranging should stop on full failure
                // stopPeriodicRanging(); // Or let it retry
            }
        });
    }
}

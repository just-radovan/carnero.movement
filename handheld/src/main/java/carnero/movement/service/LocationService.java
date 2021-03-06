package carnero.movement.service;

import java.util.*;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import carnero.movement.R;
import carnero.movement.common.Constants;
import carnero.movement.common.Preferences;
import carnero.movement.common.Utils;
import carnero.movement.common.location.LocationComparator;
import carnero.movement.common.model.Movement;
import carnero.movement.common.model.MovementEnum;
import carnero.movement.common.remotelog.RemoteLog;
import carnero.movement.db.Helper;
import carnero.movement.model.MovementChange;
import carnero.movement.model.MovementContainer;
import carnero.movement.model.MovementData;
import carnero.movement.model.OnFootMetrics;
import carnero.movement.receiver.WakeupReceiver;
import carnero.movement.ui.MainActivity;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.mariux.teleport.lib.TeleportClient;
import com.mariux.teleport.lib.TeleportService;

public class LocationService
    extends TeleportService
    implements LocationListener, SensorEventListener {

    private Preferences mPreferences;
    private Helper mHelper;
    private TeleportClient mTeleport;
    private AlarmManager mAlarmManager;
    private PowerManager mPowerManager;
    private SensorManager mSensorManager;
    private LocationManager mLocationManager;
    private NotificationManagerCompat mNotificationManager;
    private PowerManager.WakeLock mWakeLock;
    private final ArrayList<Location> mLocationHistory = new ArrayList<Location>();
    private final ArrayList<OnFootMetrics> mOnFootHistory = new ArrayList<OnFootMetrics>();
    private final ArrayList<String> mAchievements = new ArrayList<String>();
    private boolean[] mObtained = new boolean[]{false, false}; // Matches OBTAINED_ constants
    private int mWatchX = 320;
    private int mWatchY = 320;
    private long mLastStepNanos;
    private long mLastSaveToDB = 0;
    private long mLastSentToWear = 0;
    private long mLastSentToNotification = 0;
    // counters
    private int mStepsStart;
    private int mStepsSensor;
    private int mSteps;
    private float mDistance;
    private Movement mMovement;
    private Movement mMovementWear;
    private Location mLocation;
    //
    private static final int sLocationTimeThreshold = 5 * 60 * 1000; // 5min
    private static final int sLocationDistanceThreshold = 250; // 250m
    private static final int sLocationTimeThresholdLong = 3 * 60 * 60 * 1000; // 3hr
    private static final int sLocationDistanceThresholdLong = 25; // 25m
    //
    private static final int OBTAINED_STEPS = 0;
    private static final int OBTAINED_LOCATION = 1;
    //
    public static final String KILL = "kill_service";
    public static final String WAKE = "wake_run_service";

    @Override
    public void onCreate() {
        super.onCreate();

        mPreferences = new Preferences();
        mHelper = Helper.getInstance();
        mAlarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager)getSystemService(POWER_SERVICE);
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        mNotificationManager = NotificationManagerCompat.from(this);

        // Load saved values
        mStepsStart = mSteps = mPreferences.getSteps();
        mStepsSensor = mPreferences.getStepsSensor();
        mDistance = mPreferences.getDistance();
        mLocation = mPreferences.getLocation();

        if (mLocation == null) {
            getLastLocation();
        }

        // Initialize
        mTeleport = new TeleportClient(this);
        mTeleport.connect();

        // Fire initial notification & start service
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_notification)
            .setTicker(getString(R.string.notification_ticker))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(Utils.formatDistance(mDistance) + " | " + mSteps + " steps")
            .setContentIntent(PendingIntent.getActivity(
                LocationService.this,
                1010,
                new Intent(LocationService.this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT
            ));

        startForeground(Constants.ID_NOTIFICATION_SERVICE, builder.build());

        // Set alarm for repeating
        final Intent intent = new Intent(this, WakeupReceiver.class);
        intent.putExtra(WAKE, true);

        final PendingIntent alarmIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        mAlarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + (10 * 60 * 1000),
            21 * 60 * 1000, // 21 mins
            alarmIntent
        );

        init();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(KILL, false)) {
            RemoteLog.i("Requested kill");

            stopSelf();
        } else if (intent != null && intent.getBooleanExtra(WAKE, false)) {
            RemoteLog.i("Requested wake");

            // Check battery level
            final float battery = Utils.getBatteryLevel();
            if (battery < 20) {
                RemoteLog.i("Wake denied, battery @ " + battery + "%");
            } else {
                resetObtained();

                // Get wake lock
                if (mWakeLock != null && mWakeLock.isHeld()) {
                    mWakeLock.release();
                }

                mWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    ((Object)this).getClass().getSimpleName()
                );
                mWakeLock.acquire();

                // Set timer for releasing wake lock
                final TimerTask task = new TimerTask() {
                    @Override
                    public void run() {
                        if (mWakeLock == null) {
                            return;
                        }

                        getLastLocation(); // Get last location in case LocationManager didn't fire event
                        handleData(); // Send data we have to refresh Wear

                        if (mWakeLock != null && mWakeLock.isHeld()) { // Check again, it could be released meanwhile
                            mWakeLock.release();
                            mWakeLock = null;

                            RemoteLog.i("Wake lock released (timer)");
                        }
                    }
                };

                final Timer timer = new Timer(true);
                timer.schedule(task, 30 * 1000); // 30 sec
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        final String path = messageEvent.getPath();

        RemoteLog.d("Message received: " + path);

        if (path.startsWith(Constants.PATH_RESOLUTION)) {
            Uri uri = Uri.parse(path);
            String[] resolution = uri.getQuery().split("|");

            mWatchX = Integer.parseInt(resolution[0]);
            mWatchY = Integer.parseInt(resolution[1]);
        }
    }

    @Override
    public void onDestroy() {
        mSensorManager.unregisterListener(this);
        mLocationManager.removeUpdates(this);
        mTeleport.disconnect();

        RemoteLog.wtf("Destroying service");

        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            // Register motion
            checkLocation();

            // Latest steps count
            final int steps = (int)event.values[0];
            final int stepsPrev = mSteps;

            if (mStepsSensor > steps) { // Device was probably rebooted
                mStepsSensor = 0;
            }

            mSteps = mStepsStart + (steps - mStepsSensor);

            mPreferences.saveSteps(mSteps);
            mPreferences.saveStepsSensor(steps);

            // Approximate movement
            approximateMovement(stepsPrev, mSteps, event);

            // Save & send data
            handleData();

            // Notify wake lock (if held)
            setObtained(OBTAINED_STEPS);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // empty
    }

    @Override
    public void onLocationChanged(Location location) {
        RemoteLog.i(location.getProvider() + ": " + location.getAccuracy() + "...");

        Bundle extras = location.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                /*
                extras: networkLocationType: cell | wifi
                extras: travelState: stationary
                 */
                RemoteLog.i("...extras: " + key + " = " + String.valueOf(extras.get(key)));
            }
        }

        if (location.getAccuracy() > 1000) {
            return;
        }

        boolean distanceThrShort = true;
        boolean distanceThrLong = true;
        if (mLocation != null) {
            distanceThrShort = (mLocation.getTime() + sLocationTimeThreshold) < location.getTime() && mLocation.distanceTo(location) > sLocationDistanceThreshold;
            distanceThrLong = (mLocation.getTime() + sLocationTimeThresholdLong) < location.getTime() && mLocation.distanceTo(location) > sLocationDistanceThresholdLong;
        }

        if (distanceThrShort || distanceThrLong) {
            synchronized (mLocationHistory) {
                mLocationHistory.add(location);
            }

            checkLocation();
        }

        // Notify wake lock (if held)
        setObtained(OBTAINED_LOCATION);
    }

    @Override
    public void onProviderEnabled(String provider) {
        // unused
    }

    @Override
    public void onProviderDisabled(String provider) {
        // unused
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // unused
    }

    private void init() {
        mTeleport.sendMessage(Constants.PATH_RESOLUTION, null);

        // Set listener for step counter
        Sensor stepCounter = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (stepCounter != null) {
            boolean batchMode = mSensorManager.registerListener(
                this,
                stepCounter,
                SensorManager.SENSOR_DELAY_NORMAL
            );

            RemoteLog.i("Step counter registered (batch:" + batchMode + ")");
        } else {
            RemoteLog.i("Step counter is not present on this device");
        }

        // Set listeners for criteria-based provider, and for passive provider
        final Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_MEDIUM);
        criteria.setPowerRequirement(Criteria.POWER_LOW);
        criteria.setCostAllowed(true);

        final String bestProvider = mLocationManager.getBestProvider(criteria, true);
        if (bestProvider != null) {
            mLocationManager.requestLocationUpdates(
                bestProvider,
                sLocationTimeThreshold,
                sLocationDistanceThreshold,
                this
            );
            RemoteLog.i("Location provider registered: " + bestProvider);
        }

        boolean isPassiveBest = LocationManager.PASSIVE_PROVIDER.equals(bestProvider);
        boolean isPassiveAvail = mLocationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
        if (!isPassiveBest && isPassiveAvail) {
            mLocationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                sLocationTimeThreshold,
                sLocationDistanceThreshold,
                this
            );
            RemoteLog.i("Location provider registered: passive");
        }
    }

    private void getLastLocation() {
        List<String> providers = mLocationManager.getAllProviders();
        Location lastLoc = null;
        for (String provider : providers) {
            Location location = mLocationManager.getLastKnownLocation(provider);
            if (location != null && (lastLoc == null || lastLoc.getTime() < location.getTime())) {
                lastLoc = location;
            }
        }

        if (lastLoc != null) {
            RemoteLog.d("Using last location from " + lastLoc.getProvider());

            onLocationChanged(lastLoc);
        }
    }

    private void checkLocation() {
        synchronized (mLocationHistory) {
            Collections.sort(mLocationHistory, new LocationComparator());

            for (Location location : mLocationHistory) {
                if (mLocation != null && mLocation.getTime() >= location.getTime()) {
                    // Location from history is older than already processed location
                    continue;
                }

                if (mLocation != null) {
                    mDistance += mLocation.distanceTo(location);
                }
                mLocation = location;

                // Save status
                if (mDistance > 0) {
                    mPreferences.saveDistance(mDistance);
                }
                mPreferences.saveLocation(mLocation);
            }

            // Clean history
            final ArrayList<Location> drop = new ArrayList<Location>();
            for (Location location : mLocationHistory) {
                if (mLocation != null && location.getTime() <= mLocation.getTime()) {
                    drop.add(location);
                } else {
                    break; // It's sorted by time
                }
            }

            for (Location dr : drop) {
                mLocationHistory.remove(dr);
            }
        }

        // Save & send data
        handleData();
    }

    private void resetObtained() {
        for (int i = 0; i < mObtained.length; i++) {
            mObtained[i] = false;
        }
    }

    private void setObtained(int what) {
        mObtained[what] = true;

        boolean all = true;
        for (boolean obtained : mObtained) {
            if (!obtained) {
                all = false;

                break;
            }
        }

        if (all && mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;

            RemoteLog.i("Wake lock released (obtained)");
        }
    }

    private void approximateMovement(int stepsPrev, int steps, @NonNull SensorEvent event) {
        if (mLastStepNanos <= 0) {
            mLastStepNanos = event.timestamp;
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Cadence
        int delta = steps - stepsPrev; // steps
        double time = (event.timestamp - mLastStepNanos) / 1e9; // ns → seconds
        double cadence = (delta / time) * 60.0; // steps per minute

        // Calculate approximate step length and speed
        double cadenceRun = Math.min(
            Constants.CADENCE_RUN_MAX,
            Math.max(Constants.CADENCE_RUN_MIN, cadence)
        ) - Constants.CADENCE_RUN_MIN;

        if (cadenceRun < 0) {
            cadenceRun = 0.0;
        } else if (cadenceRun > (Constants.CADENCE_RUN_MAX - Constants.CADENCE_RUN_MIN)) {
            cadenceRun = Constants.CADENCE_RUN_MAX - Constants.CADENCE_RUN_MIN;
        }
        double over = cadenceRun / (Constants.CADENCE_RUN_MAX - Constants.CADENCE_RUN_MIN);

        double length = Constants.STEP_LENGTH_WALK
            + (over * (Constants.STEP_LENGTH_RUN - Constants.STEP_LENGTH_WALK));
        double distance = delta * length; // steps → metres
        double speedKPH = (distance / time) * 3.6; // km per hour

        // Save history
        final OnFootMetrics metrics = new OnFootMetrics();
        metrics.timestamp = currentTime;
        metrics.elapsed = event.timestamp;
        metrics.steps = steps;
        metrics.cadence = cadence;
        metrics.length = length;
        metrics.speed = speedKPH;

        mOnFootHistory.add(metrics);
        Collections.sort(mOnFootHistory);

        // Get activity type within time frame
        final ArrayList<OnFootMetrics> toDelete = new ArrayList<OnFootMetrics>();

        long timeFrame = (long)(30 * 1e9); // 30 seconds (in ns)
        long still = 0;
        long walk = 0;
        long run = 0;

        boolean first = true;
        long previousElapsed = event.timestamp - timeFrame; // Start of our time frame
        for (OnFootMetrics entry : mOnFootHistory) {
            if (entry.elapsed < (event.timestamp - timeFrame)) { // Too old entry
                toDelete.add(entry);
                continue;
            }

            if (first) { // Consider pause between start of time frame and first entry as STILL
                still += (entry.elapsed - previousElapsed);
                first = false;

                previousElapsed = entry.elapsed;
                continue;
            }

            if (entry.cadence < Constants.CADENCE_WALK_MIN) {
                still += (entry.elapsed - previousElapsed);
            } else if (entry.cadence < Constants.CADENCE_RUN_MIN) {
                walk += (entry.elapsed - previousElapsed);
            } else {
                run += (entry.elapsed - previousElapsed);
            }

            previousElapsed = entry.elapsed;
        }

        // Delete old values
        for (OnFootMetrics del : toDelete) {
            mOnFootHistory.remove(del);
        }

        // Get movement activity type
        double coefRun = run / (double)timeFrame;
        double coefWalk = walk / (double)timeFrame;
        double coefStill = still / (double)timeFrame;

        MovementEnum current = MovementEnum.UNKNOWN;
        if (coefStill > coefWalk && coefStill > coefRun) {
            current = MovementEnum.STILL;
        } else if (coefWalk > coefStill && coefWalk > coefRun) {
            current = MovementEnum.WALK;
        } else if (coefRun > coefStill && coefRun > coefWalk) {
            current = MovementEnum.RUN;
        }

        RemoteLog.d("Movement: "
                + (int)cadence + " spm | "
                + String.format("%.1f", speedKPH) + " kph"
        );

        boolean noData = (mMovement == null);
        boolean change = (mMovement != null
            && mMovement.type != current
            && (current.ordinal() > mMovement.type.ordinal() || event.timestamp > (mMovement.startElapsed + 15 * 1e9))
        ); // "Higher" type of activity or 15 secs after last activity change

        if (noData || change) {
            if (mMovement != null) {
                mHelper.saveMovement(mMovement);

                RemoteLog.d("Changing movement " + mMovement.type + " → " + current);
            }

            mMovement = new Movement(current, currentTime, event.timestamp);
        }

        mMovement.endElapsed = event.timestamp;
        mLastStepNanos = event.timestamp;
    }

    private void handleData() {
        final MovementChange today = mHelper.getDayToDayChange(0);

        saveToDB();
        checkAchievements();
        sendDataToWear(today);
        notifyHandheld(today);
    }

    private void saveToDB() {
        if (mLastSaveToDB > (SystemClock.elapsedRealtime() - (5 * 60 * 1000))) { // Once in 10 mins
            return;
        }

        boolean status = mHelper.saveData(mSteps, mDistance, mLocation);
        if (status) {
            mLastSaveToDB = SystemClock.elapsedRealtime();
        }
    }

    private void checkAchievements() {
        // Warning: This is not the only place where achievements are handled

        // Distance
        if (mDistance > 100000) {
            addAchievement(getString(R.string.achievement_100_km));
        }
        if (mDistance > 1000000) {
            addAchievement(getString(R.string.achievement_1000_km));
        }
        if (mDistance > 40075000) {
            addAchievement(getString(R.string.achievement_40075_km));
        }
        if (mDistance > 100000000) {
            addAchievement(getString(R.string.achievement_100000_km));
        }
        if (mDistance > 384400000) {
            addAchievement(getString(R.string.achievement_384400_km));
        }
        if (mDistance > 149597871000l) {
            addAchievement(getString(R.string.achievement_1_au));
        }

        // Steps
        if (mSteps > 100) {
            addAchievement(getString(R.string.achievement_first_steps));
        }
        if (mSteps > 100000) {
            addAchievement(getString(R.string.achievement_100k_steps));
        }
        if (mSteps > 500000) {
            addAchievement(getString(R.string.achievement_500k_steps));
        }
        if (mSteps > 1000000) {
            addAchievement(getString(R.string.achievement_1m_steps));
        }
        if (mSteps > 10000000) {
            addAchievement(getString(R.string.achievement_10m_steps));
        }
        if (mSteps > 100000000) {
            addAchievement(getString(R.string.achievement_100m_steps));
        }
    }

    private void sendDataToWear(MovementChange today) {
        if (mLastSentToWear > (SystemClock.elapsedRealtime() - (5 * 60 * 1000))
            && mMovementWear != null && mMovementWear.type == mMovement.type) {
            return;
        }

        // Summary
        final ArrayList<DataMap> statusList = new ArrayList<DataMap>();

        if (today == null) {
            return; // No data
        }

        final DataMap statusMap = new DataMap();
        statusMap.putInt("steps_total", mSteps);
        statusMap.putFloat("distance_total", mDistance);
        statusMap.putInt("steps_today", today.steps);
        statusMap.putFloat("distance_today", today.distance);
        statusMap.putDouble("steps_change", today.stepsChange);
        statusMap.putDouble("distance_change", today.distanceChange);
        if (mMovement != null) {
            statusMap.putInt("activity", mMovement.type.ordinal());
        }

        statusList.add(statusMap);

        mMovementWear = mMovement;

        // History
        double minDst = Double.MAX_VALUE;
        double maxDst = Double.MIN_VALUE;
        double minStp = Double.MAX_VALUE;
        double maxStp = Double.MIN_VALUE;
        double stepsPrev = -1d;
        double distancePrev = -1d;

        final MovementContainer yesterdayContainer = mHelper.getDataForDay(-1, 12);
        final MovementContainer todayContainer = mHelper.getDataForDay(0, 12);
        final ArrayList<Double> distanceArray = new ArrayList<Double>();
        final ArrayList<Double> stepsArray = new ArrayList<Double>();

        final ArrayList<MovementData> movements = new ArrayList<MovementData>();
        Collections.addAll(movements, yesterdayContainer.movements);
        Collections.addAll(movements, todayContainer.movements);

        if (yesterdayContainer.previousEntry != null) {
            stepsPrev = yesterdayContainer.previousEntry.steps;
            distancePrev = yesterdayContainer.previousEntry.distance;
        }

        for (MovementData movement : movements) {
            double steps;
            double distance;
            if (movement == null) {
                steps = 0;
                distance = 0;
            } else if (stepsPrev <= 0f || distancePrev <= 0f) {
                stepsPrev = movement.steps;
                distancePrev = movement.distance;

                continue;
            } else {
                steps = movement.steps - stepsPrev;
                distance = movement.distance - distancePrev;
                stepsPrev = movement.steps;
                distancePrev = movement.distance;
            }

            // Avoiding accidental resets/wrong values
            if (steps < 0) {
                steps = 0;
            }
            if (distance < 0) {
                distance = 0;
            }

            distanceArray.add(distance);
            stepsArray.add(steps);

            minDst = Math.min(minDst, distance);
            maxDst = Math.max(maxDst, distance);
            minStp = Math.min(minStp, steps);
            maxStp = Math.max(maxStp, steps);
        }

        // Create DataMaps
        final ArrayList<DataMap> stepsList = new ArrayList<DataMap>();
        for (int i = 0; i < stepsArray.size(); i++) {
            DataMap map = new DataMap();
            map.putDouble("value", stepsArray.get(i));

            stepsList.add(map);
        }

        final ArrayList<DataMap> distanceList = new ArrayList<DataMap>();
        for (int i = 0; i < distanceArray.size(); i++) {
            DataMap map = new DataMap();
            map.putDouble("value", distanceArray.get(i));

            distanceList.add(map);
        }

        // Send data
        PutDataMapRequest data = PutDataMapRequest.createWithAutoAppendedId("/status");
        data.getDataMap().putDataMapArrayList("summary", statusList);
        data.getDataMap().putDataMapArrayList("steps", stepsList);
        data.getDataMap().putDataMapArrayList("distance", distanceList);
        syncDataItem(data);

        mLastSentToWear = SystemClock.elapsedRealtime();
    }

    private void notifyHandheld(MovementChange today) {
        if (mLastSentToNotification > (SystemClock.elapsedRealtime() - (1 * 60 * 1000))) {
            return;
        }

        String text;

        if (today == null) {
            text = Utils.formatDistance(mDistance) + " | " + mSteps + " steps";
        } else {
            double stepsPercent;
            double distancePercent;
            String stepsChange;
            String distanceChange;

            if (today.stepsChange > 1.0) {
                stepsPercent = (today.stepsChange - 1.0) * 100f;
                stepsChange = "↗";
            } else if (today.stepsChange < 1.0) {
                stepsPercent = (1.0 - today.stepsChange) * 100f;
                stepsChange = "↘";
            } else {
                stepsPercent = 0;
                stepsChange = "→";
            }
            if (today.distanceChange > 1.0) {
                distancePercent = (today.distanceChange - 1.0) * 100f;
                distanceChange = "↗";
            } else if (today.distanceChange < 1.0) {
                distancePercent = (1.0 - today.distanceChange) * 100f;
                distanceChange = "↘";
            } else {
                distancePercent = 0;
                distanceChange = "→";
            }

            String stepsString;
            String distanceString;

            if (stepsPercent < 700) {
                stepsString = String.valueOf((int)stepsPercent) + "%";
            } else {
                stepsString = getString(R.string.stats_lot);
            }
            if (distancePercent < 700) {
                distanceString = String.valueOf((int)distancePercent) + "%";
            } else {
                distanceString = getString(R.string.stats_lot);
            }

            text = getString(R.string.notification_distance, distanceChange + " " + distanceString)
                + " | "
                + getString(R.string.notification_steps, stepsChange + " " + stepsString);

            // Activity types
            final ArrayList<Movement> movements = mHelper.getMovementsForDay(0);
            if (movements != null) {
                long walk = 0;
                long run = 0;
                for (Movement movement : movements) {
                    if (movement.type == MovementEnum.WALK) {
                        walk += movement.endElapsed - movement.startElapsed;
                    } else if (movement.type == MovementEnum.RUN) {
                        run += movement.endElapsed - movement.startElapsed;
                    }
                }

                int runMins = (int) Math.round(run / 1e9 / 60.0);
                if (runMins > 5) {
                    addAchievement(getString(R.string.achievement_first_run));
                }
            }
        }

        final Notification.Builder builder = new Notification.Builder(this)
            .setPriority(Notification.PRIORITY_MIN)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_notification)
            .setTicker(getString(R.string.notification_ticker))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(
                LocationService.this,
                1010,
                new Intent(LocationService.this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT
            ));

        mNotificationManager.notify(Constants.ID_NOTIFICATION_SERVICE, builder.build());

        mLastSentToNotification = SystemClock.elapsedRealtime();
    }

    private void addAchievement(String achievement) {
        if (mAchievements.contains(achievement)) {
            return;
        }

        mPreferences.addAchievementToQueue(achievement);
        mAchievements.add(achievement);
    }
}

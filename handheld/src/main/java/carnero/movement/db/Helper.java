package carnero.movement.db;

import java.util.ArrayList;
import java.util.Calendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.format.DateUtils;

import carnero.movement.App;
import carnero.movement.common.Utils;
import carnero.movement.common.model.Movement;
import carnero.movement.common.model.MovementEnum;
import carnero.movement.common.remotelog.RemoteLog;
import carnero.movement.model.*;
import carnero.movement.model.Location;

public class Helper extends SQLiteOpenHelper {

    private static SQLiteDatabase sDatabase;
    private static Helper sInstance;

    public static Helper getInstance() {
        if (sInstance == null) {
            sInstance = new Helper(App.get());
        }

        return sInstance;
    }

    private Helper(Context context) {
        super(context, Structure.name, null, Structure.version);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL(Structure.getHistoryStructure());
            for (String index : Structure.getHistoryIndexes()) {
                db.execSQL(index);
            }

            db.execSQL(Structure.getCheckinsStructure());
            for (String index : Structure.getCheckinsIndexes()) {
                db.execSQL(index);
            }

            db.execSQL(Structure.getActivitiesStructure());
            for (String index : Structure.getActivitiesIndexes()) {
                db.execSQL(index);
            }
        } catch (SQLException e) {
            RemoteLog.e("Failed to create database");
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(Structure.getCheckinsStructure());
            for (String index : Structure.getCheckinsIndexes()) {
                db.execSQL(index);
            }
        }

        if (oldVersion < 3) {
            db.execSQL("drop table " + Structure.Table.Checkins.name);

            db.execSQL(Structure.getCheckinsStructure());
            for (String index : Structure.getCheckinsIndexes()) {
                db.execSQL(index);
            }
        }

        if (oldVersion < 4) {
            db.execSQL(Structure.getActivitiesStructure());
            for (String index : Structure.getActivitiesIndexes()) {
                db.execSQL(index);
            }
        }

        if (oldVersion < 5) {
            db.execSQL("drop table " + Structure.Table.Activities.name);

            db.execSQL(Structure.getActivitiesStructure());
            for (String index : Structure.getActivitiesIndexes()) {
                db.execSQL(index);
            }
        }
    }

    private synchronized SQLiteDatabase getDatabase() {
        if (sDatabase == null) {
            sDatabase = getWritableDatabase();
            if (sDatabase.inTransaction()) {
                sDatabase.endTransaction();
            }
        }

        return sDatabase;
    }

    // Movement

    public boolean saveData(float steps, float distance, android.location.Location location) {
        boolean status = false;

        ContentValues values = new ContentValues();
        values.put(Structure.Table.History.TIME, System.currentTimeMillis());
        values.put(Structure.Table.History.STEPS, steps);
        values.put(Structure.Table.History.DISTANCE, distance);
        if (location != null) {
            values.put(Structure.Table.History.LATITUDE, location.getLatitude());
            values.put(Structure.Table.History.LONGITUDE, location.getLongitude());
            values.put(Structure.Table.History.ACCURACY, location.getAccuracy());
        }

        long id = getDatabase().insert(Structure.Table.History.name, null, values);
        if (id >= 0) {
            status = true;
        }

        return status;
    }

    public MovementData getSummaryForDay(int day) {
        long[] dayTimes = Utils.getTimesForDay(day);

        return getSummary(dayTimes[0], dayTimes[1]);
    }

    public MovementData getSummary(long start, long end) {
        Cursor cursor;
        int stepsStart = 0;
        float distanceStart = 0;
        int stepsEnd = -1;
        float distanceEnd = -1;

        // Get last entry from previous day
        cursor = null;
        try {
            cursor = getDatabase().query(
                Structure.Table.History.name,
                Structure.Table.History.projectionData,
                Structure.Table.History.TIME + " < " + start,
                null, null, null,
                Structure.Table.History.TIME + " desc",
                "1"
            );

            if (cursor.moveToFirst()) {
                int idxSteps = cursor.getColumnIndex(Structure.Table.History.STEPS);
                int idxDistance = cursor.getColumnIndex(Structure.Table.History.DISTANCE);

                stepsStart = cursor.getInt(idxSteps);
                distanceStart = cursor.getFloat(idxDistance);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // Get last entry from previous day
        cursor = null;
        try {
            cursor = getDatabase().query(
                Structure.Table.History.name,
                Structure.Table.History.projectionData,
                Structure.Table.History.TIME + " <= " + end,
                null, null, null,
                Structure.Table.History.TIME + " desc",
                "1"
            );

            if (cursor.moveToFirst()) {
                int idxSteps = cursor.getColumnIndex(Structure.Table.History.STEPS);
                int idxDistance = cursor.getColumnIndex(Structure.Table.History.DISTANCE);

                stepsEnd = cursor.getInt(idxSteps);
                distanceEnd = cursor.getFloat(idxDistance);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (stepsEnd == -1 || distanceEnd == -1) {
            return null;
        }

        final MovementData summary = new MovementData();
        summary.steps = stepsEnd - stepsStart;
        summary.distance = distanceEnd - distanceStart;

        return summary;
    }

    public MovementContainer getDataForDay(int day) {
        return getDataForDay(day, -1);
    }

    public MovementContainer getDataForDay(int day, int intervals) {
        long[] dayTimes = Utils.getTimesForDay(day);

        return getData(dayTimes[0], dayTimes[1], intervals);
    }

    public MovementContainer getData(long start, long end, int intervals) {
        long millisInterval;

        if (intervals < 0) {
            int days = (int)((end - start) / DateUtils.DAY_IN_MILLIS);

            if (days <= 1) {
                millisInterval = DateUtils.HOUR_IN_MILLIS;
            } else if (days <= 3) {
                millisInterval = DateUtils.HOUR_IN_MILLIS * 2;
            } else if (days <= 7) {
                millisInterval = DateUtils.HOUR_IN_MILLIS * 4;
            } else {
                millisInterval = DateUtils.HOUR_IN_MILLIS * 8;
            }

            intervals = (int)Math.ceil((end - start) / millisInterval);
        } else {
            millisInterval = (end - start) / intervals;
        }

        long oldest = Long.MAX_VALUE;

        final MovementContainer container = new MovementContainer();
        container.movements = new MovementData[intervals];
        container.locations = new ArrayList<Location>();

        // Get entries for given interval
        Cursor cursor = null;
        try {
            cursor = getDatabase().query(
                Structure.Table.History.name,
                Structure.Table.History.projectionFull,
                Structure.Table.History.TIME + " >= " + start + " and " + Structure.Table.History.TIME + " <= " + end,
                null, null, null,
                Structure.Table.History.TIME + " asc"
            );

            if (cursor.moveToFirst()) {
                int idxTime = cursor.getColumnIndex(Structure.Table.History.TIME);
                int idxSteps = cursor.getColumnIndex(Structure.Table.History.STEPS);
                int idxDistance = cursor.getColumnIndex(Structure.Table.History.DISTANCE);
                int idxLatitude = cursor.getColumnIndex(Structure.Table.History.LATITUDE);
                int idxLongitude = cursor.getColumnIndex(Structure.Table.History.LONGITUDE);
                int idxAccuracy = cursor.getColumnIndex(Structure.Table.History.ACCURACY);

                do {
                    // Movements
                    long time = cursor.getLong(idxTime);
                    if (time < oldest) {
                        oldest = time;
                    }

                    // Oldest is the first interval
                    int interval = intervals - ((int)((end - time) / millisInterval)) - 1;

                    MovementData movement = container.movements[interval];
                    if (movement == null) {
                        movement = new MovementData();
                        movement.steps = cursor.getInt(idxSteps);
                        movement.distance = cursor.getFloat(idxDistance);

                        container.movements[interval] = movement;
                    } else {
                        movement.steps = Math.max(movement.steps, cursor.getInt(idxSteps));
                        movement.distance = Math.max(movement.distance, cursor.getFloat(idxDistance));
                    }

                    // Locations
                    if (!cursor.isNull(idxLatitude) && !cursor.isNull(idxLongitude)) {
                        Location location = new Location();
                        location.time = time;
                        location.latitude = cursor.getDouble(idxLatitude);
                        location.longitude = cursor.getDouble(idxLongitude);
                        location.accuracy = cursor.getDouble(idxAccuracy);

                        container.locations.add(location);
                    }
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // Get oldest entry before given interval
        try {
            cursor = getDatabase().query(
                Structure.Table.History.name,
                Structure.Table.History.projectionData,
                Structure.Table.History.TIME + " < " + oldest,
                null, null, null,
                Structure.Table.History.TIME + " desc",
                "1"
            );

            if (cursor.moveToFirst()) {
                int idxSteps = cursor.getColumnIndex(Structure.Table.History.STEPS);
                int idxDistance = cursor.getColumnIndex(Structure.Table.History.DISTANCE);

                MovementData model = new MovementData();
                model.steps = cursor.getInt(idxSteps);
                model.distance = cursor.getFloat(idxDistance);

                container.previousEntry = model;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (container.previousEntry == null) {
            container.previousEntry = new MovementData();
            container.previousEntry.steps = 0;
            container.previousEntry.distance = 0;
        }

        return container;
    }

    public ArrayList<Location> getLocations(long start, long end) {
        final ArrayList<Location> data = new ArrayList<Location>();

        Cursor cursor = null;
        try {
            cursor = getDatabase().query(
                Structure.Table.History.name,
                Structure.Table.History.projectionLocation,
                Structure.Table.History.TIME + " >= " + start + " and " + Structure.Table.History.TIME + " <= " + end,
                null, null, null,
                Structure.Table.History.TIME + " desc"
            );

            if (cursor.moveToFirst()) {
                int idxTime = cursor.getColumnIndex(Structure.Table.History.TIME);
                int idxLatitude = cursor.getColumnIndex(Structure.Table.History.LATITUDE);
                int idxLongitude = cursor.getColumnIndex(Structure.Table.History.LONGITUDE);
                int idxAccuracy = cursor.getColumnIndex(Structure.Table.History.ACCURACY);

                do {
                    Location model = new Location();
                    model.time = cursor.getLong(idxTime);
                    model.latitude = cursor.getDouble(idxLatitude);
                    model.longitude = cursor.getDouble(idxLongitude);
                    model.accuracy = cursor.getDouble(idxAccuracy);

                    data.add(model);
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return data;
    }

    // Activities

    public boolean saveMovement(Movement movement) {
        boolean status = false;

        ContentValues values = new ContentValues();
        values.put(Structure.Table.Activities.TYPE, movement.type.ordinal());
        values.put(Structure.Table.Activities.TIMESTAMP, movement.timestamp);
        values.put(Structure.Table.Activities.START, movement.startElapsed);
        values.put(Structure.Table.Activities.END, movement.endElapsed);

        long id = getDatabase().insert(
            Structure.Table.Activities.name,
            null,
            values
        );

        if (id >= 0) {
            status = true;
        }

        return status;
    }

    public ArrayList<Movement> getMovementsForDay(int day) {
        long[] dayTimes = Utils.getTimesForDay(day);

        return getMovements(dayTimes[0], dayTimes[1]);
    }

    public ArrayList<Movement> getMovements(long start, long end) {
        final ArrayList<Movement> data = new ArrayList<Movement>();

        Cursor cursor = null;
        try {
            cursor = getDatabase().query(
                Structure.Table.Activities.name,
                Structure.Table.Activities.projectionFull,
                Structure.Table.Activities.TIMESTAMP + " >= " + start
                    + " and " + Structure.Table.Activities.TIMESTAMP + " <= " + end,
                null, null, null,
                Structure.Table.Activities.START + " asc"
            );

            if (cursor.moveToFirst()) {
                int idxType = cursor.getColumnIndex(Structure.Table.Activities.TYPE);
                int idxTimestamp = cursor.getColumnIndex(Structure.Table.Activities.TIMESTAMP);
                int idxStart = cursor.getColumnIndex(Structure.Table.Activities.START);
                int idxEnd = cursor.getColumnIndex(Structure.Table.Activities.END);

                do {
                    Movement model = new Movement();
                    model.type = MovementEnum.values()[cursor.getInt(idxType)];
                    model.timestamp = cursor.getLong(idxTimestamp);
                    model.startElapsed = cursor.getLong(idxStart);
                    model.endElapsed = cursor.getLong(idxEnd);

                    data.add(model);
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return data;
    }

    // Foursquare

    public long getLatestCheckinTime() {
        long time = 0;

        Cursor cursor = null;
        try {
            cursor = getDatabase().query(
                Structure.Table.Checkins.name,
                new String[] {Structure.Table.Checkins.CREATED},
                null, null, null, null,
                Structure.Table.Checkins.CREATED + " desc",
                "1"
            );

            if (cursor.moveToFirst()) {
                int idxTime = cursor.getColumnIndex(Structure.Table.Checkins.CREATED);
                time = cursor.getLong(idxTime);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return time;
    }

    public boolean saveCheckin(Checkin checkin) {
        boolean status = false;

        ContentValues values = new ContentValues();
        values.put(Structure.Table.Checkins.CHECKIN_ID, checkin.checkinId);
        values.put(Structure.Table.Checkins.CREATED, checkin.createdAt);
        values.put(Structure.Table.Checkins.LATITUDE, checkin.latitude);
        values.put(Structure.Table.Checkins.LONGITUDE, checkin.longitude);
        values.put(Structure.Table.Checkins.NAME, checkin.name);
        values.put(Structure.Table.Checkins.SHOUT, checkin.shout);
        values.put(Structure.Table.Checkins.ICON_PREFIX, checkin.iconPrefix);
        values.put(Structure.Table.Checkins.ICON_SUFFIX, checkin.iconSuffix);

        long id = getDatabase().insertWithOnConflict(
            Structure.Table.Checkins.name,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        );

        if (id >= 0) {
            status = true;
        }

        return status;
    }

    public ArrayList<Checkin> getCheckinsForDay(int day) {
        long[] dayTimes = Utils.getTimesForDay(day);

        return getCheckins(dayTimes[0], dayTimes[1]);
    }

    public ArrayList<Checkin> getCheckins(long start, long end) {
        final ArrayList<Checkin> data = new ArrayList<Checkin>();

        Cursor cursor = null;
        try {
            cursor = getDatabase().query(
                Structure.Table.Checkins.name,
                Structure.Table.Checkins.projectionFull,
                Structure.Table.Checkins.CREATED + " >= " + start + " and " + Structure.Table.Checkins.CREATED + " <= " + end,
                null, null, null,
                Structure.Table.Checkins.CREATED + " asc"
            );

            if (cursor.moveToFirst()) {
                int idxCreated = cursor.getColumnIndex(Structure.Table.Checkins.CREATED);
                int idxLatitude = cursor.getColumnIndex(Structure.Table.Checkins.LATITUDE);
                int idxLongitude = cursor.getColumnIndex(Structure.Table.Checkins.LONGITUDE);
                int idxName = cursor.getColumnIndex(Structure.Table.Checkins.NAME);
                int idxShout = cursor.getColumnIndex(Structure.Table.Checkins.SHOUT);
                int idxPrefix = cursor.getColumnIndex(Structure.Table.Checkins.ICON_PREFIX);
                int idxSuffix = cursor.getColumnIndex(Structure.Table.Checkins.ICON_SUFFIX);

                do {
                    Checkin checkin = new Checkin();
                    checkin.createdAt = cursor.getLong(idxCreated);
                    checkin.latitude = cursor.getDouble(idxLatitude);
                    checkin.longitude = cursor.getDouble(idxLongitude);
                    checkin.name = cursor.getString(idxName);
                    checkin.shout = cursor.getString(idxShout);
                    checkin.iconPrefix = cursor.getString(idxPrefix);
                    checkin.iconSuffix = cursor.getString(idxSuffix);

                    data.add(checkin);
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return data;
    }

    /**
     * Get changes between given day and day before that
     *
     * @param day
     * @return
     */
    public MovementChange getDayToDayChange(int day) {
        final MovementData thisDay = getSummaryForDay(day);
        final MovementData dayBefore;
        if (day == 0) { // Get only interval to this time day before (incomplete this day)
            final Calendar calendar = Calendar.getInstance();

            calendar.add(Calendar.DAY_OF_MONTH, -1);
            long yesterdayEnd = calendar.getTimeInMillis();

            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            long yesterdayStart = calendar.getTimeInMillis();

            dayBefore = getSummary(yesterdayStart, yesterdayEnd);
        } else { // Days before today are completed
            dayBefore = getSummaryForDay(day - 1);
        }

        if (thisDay == null) {
            return null;
        } else if (dayBefore == null) {
            return new MovementChange(
                thisDay.steps,
                thisDay.distance,
                1.0,
                1.0
            );
        } else {
            return new MovementChange(
                thisDay.steps,
                thisDay.distance,
                (double)thisDay.steps / (double)dayBefore.steps,
                (double)thisDay.distance / (double)dayBefore.distance
            );
        }
    }
}

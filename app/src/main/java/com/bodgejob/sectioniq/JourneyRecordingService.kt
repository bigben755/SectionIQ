package com.bodgejob.sectioniq

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID


class JourneyRecordingService : Service() {

    companion object {

        const val ACTION_START =
            "com.bodgejob.sectioniq.action.START_JOURNEY"

        const val ACTION_STOP =
            "com.bodgejob.sectioniq.action.STOP_JOURNEY"

        const val ACTION_MARK_STATION =
            "com.bodgejob.sectioniq.action.MARK_STATION"


        const val PREFS_NAME =
            "sectioniq_recording_state"


        const val KEY_RECORDING =
            "recording"

        const val KEY_SESSION_ID =
            "session_id"

        const val KEY_POINT_COUNT =
            "point_count"

        const val KEY_STARTED_ELAPSED =
            "started_elapsed"

        const val KEY_STARTED_WALL =
            "started_wall"

        const val KEY_LAST_LATITUDE =
            "last_latitude"

        const val KEY_LAST_LONGITUDE =
            "last_longitude"

        const val KEY_LAST_ACCURACY =
            "last_accuracy"

        const val KEY_LAST_SPEED =
            "last_speed"

        const val KEY_LAST_SPEED_VALID =
            "last_speed_valid"

        const val KEY_LAST_FIX_TIME =
            "last_fix_time"

        const val KEY_LAST_SAVED_FILE =
            "last_saved_file"

        const val KEY_PATHFINDER_MODE =
            "pathfinder_mode"

        const val KEY_PATHFINDER_MARK_COUNT =
            "pathfinder_mark_count"


        /*
         * Pathfinder station marks are only accepted while the
         * train is effectively stationary.
         *
         * 0.7 m/s ≈ 1.6 mph.
         */
        const val PATHFINDER_MAX_SPEED_MPS =
            0.7f


        /*
         * Pathfinder must not use an old/stale GPS fix.
         */
        const val PATHFINDER_MAX_FIX_AGE_MS =
            10_000L


        /*
         * Stops accidental double taps on MARK STATION.
         */
        private const val PATHFINDER_MARK_COOLDOWN_MS =
            3_000L


        private const val NOTIFICATION_CHANNEL_ID =
            "sectioniq_journey_recording"

        private const val NOTIFICATION_ID =
            1001
    }


    /*
     * ---------------------------------------------------------
     * LOCATION
     * ---------------------------------------------------------
     */

    private val fusedLocationClient by lazy {

        LocationServices
            .getFusedLocationProviderClient(
                this
            )
    }


    private var locationCallback:
            LocationCallback? =
        null


    /*
     * ---------------------------------------------------------
     * CURRENT JOURNEY
     * ---------------------------------------------------------
     */

    private var currentJourneyFile:
            File? =
        null


    private var currentSessionId:
            String? =
        null


    private var pointCount =
        0


    private var pathfinderMarkCount =
        0


    private var startedElapsed =
        0L


    /*
     * The service owns the authoritative last GPS point used
     * for Pathfinder.
     *
     * MainActivity is never allowed to provide its own
     * latitude/longitude for a station datum.
     */
    private var lastJourneyPoint:
            JourneyPoint? =
        null


    private var lastJourneyPointHasSpeed =
        false


    private var lastPathfinderMarkElapsed =
        0L


    /*
     * ---------------------------------------------------------
     * RECORDING PREFERENCES
     * ---------------------------------------------------------
     */

    private val recordingPreferences by lazy {

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
    }


    /*
     * ---------------------------------------------------------
     * SERVICE LIFECYCLE
     * ---------------------------------------------------------
     */

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
        ) {

            ACTION_START -> {

                if (
                    !isCurrentlyRecording()
                ) {

                    startJourneyRecording()
                }
            }


            ACTION_MARK_STATION -> {

                markPathfinderStation()
            }


            ACTION_STOP -> {

                stopJourneyRecording(
                    finaliseJourney = true
                )
            }
        }


        /*
         * We deliberately don't automatically restart an
         * interrupted journey yet.
         */
        return START_NOT_STICKY
    }


    /*
     * ---------------------------------------------------------
     * START JOURNEY
     * ---------------------------------------------------------
     */

    private fun startJourneyRecording() {

        val hasFineLocation =

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED


        val hasCoarseLocation =

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED


        if (
            !hasFineLocation &&
            !hasCoarseLocation
        ) {

            Log.e(
                "SectionIQRecorder",
                "Cannot start: location permission missing"
            )

            stopSelf()

            return
        }


        /*
         * Promote immediately to a foreground service.
         */
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildRecordingNotification(
                pointCount = 0
            ),
            ServiceInfo
                .FOREGROUND_SERVICE_TYPE_LOCATION
        )


        try {

            val sessionId =

                UUID.randomUUID()
                    .toString()


            currentSessionId =
                sessionId


            currentJourneyFile =

                createJourneyFile(
                    context = this,
                    sessionId = sessionId,
                    deviceName =
                        getDeviceName(
                            this
                        )
                )


            pointCount =
                0


            pathfinderMarkCount =
                0


            lastJourneyPoint =
                null


            lastJourneyPointHasSpeed =
                false


            lastPathfinderMarkElapsed =
                0L


            startedElapsed =
                SystemClock.elapsedRealtime()


            /*
             * IMPORTANT:
             *
             * KEY_PATHFINDER_MODE is deliberately NOT changed
             * here.
             *
             * MainActivity writes the selected Pathfinder mode
             * immediately before starting the journey.
             */
            recordingPreferences
                .edit()

                .putBoolean(
                    KEY_RECORDING,
                    true
                )

                .putString(
                    KEY_SESSION_ID,
                    sessionId
                )

                .putInt(
                    KEY_POINT_COUNT,
                    0
                )

                .putInt(
                    KEY_PATHFINDER_MARK_COUNT,
                    0
                )

                .putLong(
                    KEY_STARTED_ELAPSED,
                    startedElapsed
                )

                .putLong(
                    KEY_STARTED_WALL,
                    System.currentTimeMillis()
                )

                .putBoolean(
                    KEY_LAST_SPEED_VALID,
                    false
                )

                .remove(
                    KEY_LAST_SPEED
                )

                .remove(
                    KEY_LAST_FIX_TIME
                )

                .remove(
                    KEY_LAST_SAVED_FILE
                )

                .apply()


            Log.d(
                "SectionIQRecorder",
                "Journey started: $sessionId"
            )


            startLocationUpdates()


        } catch (
            e: Exception
        ) {

            Log.e(
                "SectionIQRecorder",
                "Failed to start journey",
                e
            )


            stopJourneyRecording(
                finaliseJourney = false
            )
        }
    }


    /*
     * ---------------------------------------------------------
     * GPS RECORDING
     * ---------------------------------------------------------
     */

    private fun startLocationUpdates() {

        val request =

            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000L
            )
                .setMinUpdateIntervalMillis(
                    1000L
                )
                .build()


        val callback =

            object : LocationCallback() {

                override fun onLocationResult(
                    locationResult: LocationResult
                ) {

                    locationResult
                        .locations
                        .forEach { location ->


                            val hasSpeed =
                                location.hasSpeed()


                            val point =

                                JourneyPoint(

                                    timestamp =
                                        location.time,

                                    latitude =
                                        location.latitude,

                                    longitude =
                                        location.longitude,

                                    accuracyMetres =
                                        location.accuracy,

                                    speedMetresPerSecond =

                                        if (
                                            hasSpeed
                                        ) {

                                            location.speed

                                        } else {

                                            /*
                                             * General journey recording can
                                             * retain zero when Android doesn't
                                             * provide speed.
                                             *
                                             * Pathfinder separately tracks
                                             * hasSpeed and will reject such a
                                             * fix.
                                             */
                                            0f
                                        },

                                    speedAvailable =

                                        hasSpeed,

                                    bearingDegrees =

                                        if (
                                            location.hasBearing()
                                        ) {

                                            location.bearing

                                        } else {

                                            0f
                                        },

                                    altitudeMetres =

                                        if (
                                            location.hasAltitude()
                                        ) {

                                            location.altitude

                                        } else {

                                            null
                                        }
                                )


                            try {

                                appendJourneyPoint(
                                    currentJourneyFile,
                                    point
                                )


                                pointCount++


                                /*
                                 * Store the exact fix that was written into
                                 * the journey JSONL.
                                 */
                                lastJourneyPoint =
                                    point


                                lastJourneyPointHasSpeed =
                                    hasSpeed


                                recordingPreferences
                                    .edit()

                                    .putInt(
                                        KEY_POINT_COUNT,
                                        pointCount
                                    )

                                    .putLong(
                                        KEY_LAST_LATITUDE,
                                        java.lang.Double
                                            .doubleToRawLongBits(
                                                location.latitude
                                            )
                                    )

                                    .putLong(
                                        KEY_LAST_LONGITUDE,
                                        java.lang.Double
                                            .doubleToRawLongBits(
                                                location.longitude
                                            )
                                    )

                                    .putFloat(
                                        KEY_LAST_ACCURACY,
                                        location.accuracy
                                    )

                                    .putFloat(
                                        KEY_LAST_SPEED,
                                        point
                                            .speedMetresPerSecond
                                    )

                                    .putBoolean(
                                        KEY_LAST_SPEED_VALID,
                                        hasSpeed
                                    )

                                    .putLong(
                                        KEY_LAST_FIX_TIME,
                                        point.timestamp
                                    )

                                    .apply()


                                /*
                                 * Refresh notification periodically rather
                                 * than on every GPS point.
                                 */
                                if (
                                    pointCount == 1 ||
                                    pointCount % 5 == 0
                                ) {

                                    updateNotification()
                                }


                            } catch (
                                e: Exception
                            ) {

                                Log.e(
                                    "SectionIQRecorder",
                                    "Failed to save GPS point",
                                    e
                                )
                            }
                        }
                }
            }


        locationCallback =
            callback


        try {

            fusedLocationClient
                .requestLocationUpdates(
                    request,
                    callback,
                    Looper.getMainLooper()
                )


        } catch (
            e: SecurityException
        ) {

            Log.e(
                "SectionIQRecorder",
                "Location permission lost",
                e
            )


            stopJourneyRecording(
                finaliseJourney = false
            )
        }
    }


    /*
     * ---------------------------------------------------------
     * PATHFINDER - MARK STATION
     * ---------------------------------------------------------
     */

    private fun markPathfinderStation() {

        /*
         * Must be actively recording.
         */
        if (
            !isCurrentlyRecording()
        ) {

            Log.w(
                "SectionIQPathfinder",
                "Station mark rejected: no active journey"
            )

            return
        }


        /*
         * This journey itself must have been started in
         * Pathfinder Mode.
         */
        val pathfinderMode =

            recordingPreferences
                .getBoolean(
                    KEY_PATHFINDER_MODE,
                    false
                )


        if (
            !pathfinderMode
        ) {

            Log.w(
                "SectionIQPathfinder",
                "Station mark rejected: Pathfinder not active"
            )

            return
        }


        val sessionId =

            currentSessionId
                ?: return


        val file =

            currentJourneyFile
                ?: return


        val point =

            lastJourneyPoint
                ?: run {

                    Log.w(
                        "SectionIQPathfinder",
                        "Station mark rejected: no GPS point yet"
                    )

                    return
                }


        /*
         * At least one GPS point must have been recorded.
         */
        if (
            pointCount <= 0
        ) {

            return
        }


        /*
         * CRITICAL:
         *
         * Unknown speed must NOT be interpreted as stationary.
         */
        if (
            !lastJourneyPointHasSpeed
        ) {

            Log.w(
                "SectionIQPathfinder",
                "Station mark rejected: reliable speed unavailable"
            )

            return
        }


        /*
         * Train must be effectively stationary.
         */
        if (
            point.speedMetresPerSecond >
            PATHFINDER_MAX_SPEED_MPS
        ) {

            Log.w(
                "SectionIQPathfinder",
                "Station mark rejected: train moving at " +
                        "${point.speedMetresPerSecond} m/s"
            )

            return
        }


        /*
         * Reject stale GPS fixes.
         */
        val fixAgeMs =

            System.currentTimeMillis() -
                    point.timestamp


        if (
            fixAgeMs < 0L ||
            fixAgeMs >
            PATHFINDER_MAX_FIX_AGE_MS
        ) {

            Log.w(
                "SectionIQPathfinder",
                "Station mark rejected: GPS fix is stale"
            )

            return
        }


        /*
         * Anti-double-tap protection.
         */
        val nowElapsed =

            SystemClock.elapsedRealtime()


        if (
            lastPathfinderMarkElapsed > 0L &&
            nowElapsed -
            lastPathfinderMarkElapsed <
            PATHFINDER_MARK_COOLDOWN_MS
        ) {

            Log.w(
                "SectionIQPathfinder",
                "Station mark rejected: duplicate tap"
            )

            return
        }


        val markId =

            UUID.randomUUID()
                .toString()


        /*
         * Journey point sequence numbers are zero based in
         * JourneyUploader.
         *
         * pointCount has already been incremented after writing
         * the GPS point, therefore the latest point is:
         *
         * pointCount - 1
         */
        val sequenceNumber =

            (pointCount - 1)
                .coerceAtLeast(
                    0
                )


        val record =

            JSONObject().apply {

                put(
                    "record_type",
                    "pathfinder_station_mark"
                )

                put(
                    "schema_version",
                    1
                )

                put(
                    "id",
                    markId
                )

                put(
                    "session_id",
                    sessionId
                )

                put(
                    "timestamp_ms",
                    point.timestamp
                )

                put(
                    "sequence_number",
                    sequenceNumber
                )

                put(
                    "latitude",
                    point.latitude
                )

                put(
                    "longitude",
                    point.longitude
                )

                put(
                    "accuracy_m",
                    point.accuracyMetres
                )

                put(
                    "speed_mps",
                    point.speedMetresPerSecond
                )

                /*
                 * Pathfinder users are instructed to position
                 * themselves as close as practicable to the
                 * leading driving cab.
                 */
                put(
                    "train_position",
                    "front"
                )

                /*
                 * Station identification comes later once the
                 * station reference dataset is populated.
                 */
                put(
                    "station_id",
                    JSONObject.NULL
                )

                /*
                 * Android collectors are only allowed to create
                 * candidate observations.
                 */
                put(
                    "confirmation_status",
                    "candidate"
                )
            }


        try {

            /*
             * The service owns the JSONL file, so Pathfinder
             * marks are serialised into the same journey file
             * as the GPS trace.
             */
            file.appendText(
                record.toString() +
                        "\n"
            )


            pathfinderMarkCount++


            lastPathfinderMarkElapsed =
                nowElapsed


            recordingPreferences
                .edit()

                .putInt(
                    KEY_PATHFINDER_MARK_COUNT,
                    pathfinderMarkCount
                )

                .apply()


            updateNotification()


            Log.d(
                "SectionIQPathfinder",
                "Station mark recorded: " +
                        "$markId at " +
                        "${point.latitude}," +
                        "${point.longitude}"
            )


        } catch (
            e: Exception
        ) {

            Log.e(
                "SectionIQPathfinder",
                "Failed to save Pathfinder station mark",
                e
            )
        }
    }


    /*
     * ---------------------------------------------------------
     * STOP JOURNEY
     * ---------------------------------------------------------
     *
     * IMPORTANT:
     *
     * The recording state MUST be changed to false before cloud
     * synchronisation begins.
     *
     * The previous version launched uploadPendingJourneys()
     * while KEY_RECORDING was still true. The uploader therefore
     * treated the newly completed journey as still active and
     * deliberately skipped it.
     *
     * This version also uploads the exact completed file directly
     * before scanning for any older pending files.
     */

    private fun stopJourneyRecording(
        finaliseJourney: Boolean
    ) {

        /*
         * -----------------------------------------------------
         * STOP GPS
         * -----------------------------------------------------
         */

        locationCallback
            ?.let { callback ->

                fusedLocationClient
                    .removeLocationUpdates(
                        callback
                    )
            }


        locationCallback =
            null


        /*
         * Preserve a reference to this journey before clearing
         * service state.
         */
        val completedJourneyFile =

            currentJourneyFile


        /*
         * -----------------------------------------------------
         * FINALISE LOCAL JSONL
         * -----------------------------------------------------
         */

        if (
            finaliseJourney &&
            completedJourneyFile != null
        ) {

            try {

                finishJourneyFile(
                    file =
                        completedJourneyFile,

                    pointCount =
                        pointCount
                )


                recordingPreferences
                    .edit()

                    .putString(
                        KEY_LAST_SAVED_FILE,
                        completedJourneyFile
                            .name
                    )

                    .apply()


                Log.d(
                    "SectionIQRecorder",
                    "Journey finalised locally: " +
                            completedJourneyFile.name
                )


            } catch (
                e: Exception
            ) {

                Log.e(
                    "SectionIQRecorder",
                    "Failed to finalise journey",
                    e
                )
            }
        }


        /*
         * -----------------------------------------------------
         * END RECORDING STATE
         * -----------------------------------------------------
         *
         * THIS MUST HAPPEN BEFORE CLOUD SYNC.
         */

        recordingPreferences
            .edit()

            .putBoolean(
                KEY_RECORDING,
                false
            )

            .putInt(
                KEY_POINT_COUNT,
                pointCount
            )

            .putInt(
                KEY_PATHFINDER_MARK_COUNT,
                pathfinderMarkCount
            )

            .putBoolean(
                KEY_LAST_SPEED_VALID,
                false
            )

            .apply()


        /*
         * -----------------------------------------------------
         * CLEAR IN-MEMORY RECORDING STATE
         * -----------------------------------------------------
         */

        currentJourneyFile =
            null


        currentSessionId =
            null


        lastJourneyPoint =
            null


        lastJourneyPointHasSpeed =
            false


        /*
         * -----------------------------------------------------
         * CLOUD SYNC
         * -----------------------------------------------------
         *
         * Upload the exact journey that was just completed.
         *
         * If connectivity fails:
         *
         * - the JSONL remains on-device
         * - no .uploaded marker is created
         * - startup sync will retry it later
         */

        if (
            finaliseJourney &&
            completedJourneyFile != null
        ) {

            CoroutineScope(
                SupervisorJob() +
                        Dispatchers.IO
            ).launch {

                try {

                    Log.d(
                        "SectionIQCloud",
                        "Starting automatic upload: " +
                                completedJourneyFile.name
                    )


                    /*
                     * First upload the journey we have just
                     * completed.
                     */
                    JourneyUploader
                        .uploadJourneyFile(
                            context =
                                applicationContext,

                            file =
                                completedJourneyFile
                        )


                    Log.d(
                        "SectionIQCloud",
                        "Current journey upload completed"
                    )


                    /*
                     * Then retry anything older that may still
                     * be waiting locally.
                     */
                    val pendingUploaded =

                        JourneyUploader
                            .uploadPendingJourneys(
                                applicationContext
                            )


                    Log.d(
                        "SectionIQCloud",
                        "Additional pending journeys uploaded: " +
                                pendingUploaded
                    )


                } catch (
                    e: Exception
                ) {

                    /*
                     * Do not delete the JSONL.
                     *
                     * Startup sync will retry later.
                     */
                    Log.e(
                        "SectionIQCloud",
                        "Automatic journey upload failed",
                        e
                    )
                }
            }
        }


        /*
         * -----------------------------------------------------
         * STOP FOREGROUND SERVICE
         * -----------------------------------------------------
         */

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )


        stopSelf()
    }


    /*
     * ---------------------------------------------------------
     * RECORDING STATE
     * ---------------------------------------------------------
     */

    private fun isCurrentlyRecording():
            Boolean {

        return recordingPreferences
            .getBoolean(
                KEY_RECORDING,
                false
            )
    }


    /*
     * ---------------------------------------------------------
     * NOTIFICATION CHANNEL
     * ---------------------------------------------------------
     */

    private fun createNotificationChannel() {

        val manager =

            getSystemService(
                NotificationManager::class.java
            )


        val channel =

            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Journey recording",
                NotificationManager
                    .IMPORTANCE_LOW
            ).apply {

                description =
                    "Shows when SectionIQ is recording journey data."
            }


        manager.createNotificationChannel(
            channel
        )
    }


    /*
     * ---------------------------------------------------------
     * RECORDING NOTIFICATION
     * ---------------------------------------------------------
     */

    private fun buildRecordingNotification(
        pointCount: Int
    ): android.app.Notification {

        val openAppIntent =

            Intent(
                this,
                MainActivity::class.java
            )


        val openAppPendingIntent =

            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent
                    .FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )


        val stopIntent =

            Intent(
                this,
                JourneyRecordingService::class.java
            ).apply {

                action =
                    ACTION_STOP
            }


        val stopPendingIntent =

            PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent
                    .FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )


        val pathfinderMode =

            recordingPreferences
                .getBoolean(
                    KEY_PATHFINDER_MODE,
                    false
                )


        val markCount =

            recordingPreferences
                .getInt(
                    KEY_PATHFINDER_MARK_COUNT,
                    pathfinderMarkCount
                )


        val contentText =

            if (
                pathfinderMode
            ) {

                "$pointCount GPS points • " +
                        "$markCount station " +
                        if (
                            markCount == 1
                        ) {

                            "mark"

                        } else {

                            "marks"
                        }

            } else {

                "$pointCount GPS points captured"
            }


        return NotificationCompat
            .Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )

            .setSmallIcon(
                R.mipmap.ic_launcher
            )

            .setContentTitle(

                if (
                    pathfinderMode
                ) {

                    "SectionIQ Pathfinder active"

                } else {

                    "SectionIQ is recording"
                }
            )

            .setContentText(
                contentText
            )

            .setContentIntent(
                openAppPendingIntent
            )

            .setOngoing(
                true
            )

            .setOnlyAlertOnce(
                true
            )

            .setCategory(
                NotificationCompat
                    .CATEGORY_SERVICE
            )

            .addAction(
                0,
                "Stop journey",
                stopPendingIntent
            )

            .build()
    }


    private fun updateNotification() {

        val manager =

            getSystemService(
                NotificationManager::class.java
            )


        manager.notify(
            NOTIFICATION_ID,
            buildRecordingNotification(
                pointCount
            )
        )
    }


    /*
     * ---------------------------------------------------------
     * SERVICE CLEANUP
     * ---------------------------------------------------------
     */

    override fun onDestroy() {

        locationCallback
            ?.let { callback ->

                fusedLocationClient
                    .removeLocationUpdates(
                        callback
                    )
            }


        locationCallback =
            null


        super.onDestroy()
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}

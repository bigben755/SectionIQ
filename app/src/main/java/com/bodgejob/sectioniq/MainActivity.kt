package com.bodgejob.sectioniq

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bodgejob.sectioniq.ui.theme.SectionIQTheme
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale


data class JourneyPoint(

    val timestamp: Long,

    val latitude: Double,

    val longitude: Double,

    val accuracyMetres: Float,

    val speedMetresPerSecond: Float,

    val speedAvailable: Boolean,

    val speedAccuracyMetresPerSecond: Float?,

    val elapsedRealtimeNanos: Long,

    val bearingAccuracyDegrees: Float?,

    val verticalAccuracyMetres: Float?,

    val bearingDegrees: Float,

    val altitudeMetres: Double?
)


class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        enableEdgeToEdge()


        /*
         * -----------------------------------------------------
         * CLOUD STARTUP
         * -----------------------------------------------------
         */

        lifecycleScope.launch {

            try {

                val device =

                    SectionIQSupabase
                        .registerDevice()


                saveRegisteredDevice(
                    context =
                        this@MainActivity,
                    device =
                        device
                )


                Log.d(
                    "SectionIQCloud",
                    "Registered as SectionIQ Device " +
                            "${device.deviceNumber} | " +
                            "id=${device.id}"
                )


                /*
                 * Pathfinder entitlement is cached locally for
                 * UI availability.
                 *
                 * Supabase remains authoritative.
                 */
                try {

                    val pathfinderEnabled =

                        SectionIQSupabase
                            .isPathfinderEnabled(
                                device.id
                            )


                    savePathfinderAvailability(
                        context =
                            this@MainActivity,
                        enabled =
                            pathfinderEnabled
                    )


                    Log.d(
                        "SectionIQPathfinder",
                        "Pathfinder entitlement: " +
                                pathfinderEnabled
                    )


                } catch (
                    e: Exception
                ) {

                    /*
                     * A temporary network issue must not break
                     * device registration or journey sync.
                     */
                    Log.e(
                        "SectionIQPathfinder",
                        "Could not refresh Pathfinder entitlement",
                        e
                    )
                }


                val uploadedCount =

                    JourneyUploader
                        .uploadPendingJourneys(
                            context =
                                this@MainActivity
                        )


                Log.d(
                    "SectionIQCloud",
                    "Startup journey sync finished: " +
                            "$uploadedCount uploaded"
                )


            } catch (
                e: Exception
            ) {

                Log.e(
                    "SectionIQCloud",
                    "SectionIQ cloud startup failed",
                    e
                )
            }
        }


        setContent {

            SectionIQTheme {

                Surface(
                    modifier =
                        Modifier.fillMaxSize(),
                    color =
                        Color(0xFFF4F7FA)
                ) {

                    SectionIQHomeScreen()
                }
            }
        }
    }
}


@Composable
fun SectionIQHomeScreen() {

    val context =
        LocalContext.current


    val observationScope =
        rememberCoroutineScope()


    var pendingObservations by remember {
        mutableStateOf<List<JourneyObservation>>(
            emptyList()
        )
    }


    var pendingObservationsLoaded by remember {
        mutableStateOf(
            false
        )
    }


    var pendingObservationsLoading by remember {
        mutableStateOf(
            false
        )
    }


    var selectedObservationKind by remember {
        mutableStateOf(
            "other"
        )
    }


    var observationNote by remember {
        mutableStateOf(
            ""
        )
    }


    var observationError by remember {
        mutableStateOf<String?>(
            null
        )
    }


    val sectionPreferences =

        remember {

            context.getSharedPreferences(
                "sectioniq_preferences",
                Context.MODE_PRIVATE
            )
        }


    val recordingPreferences =

        remember {

            context.getSharedPreferences(
                JourneyRecordingService
                    .PREFS_NAME,
                Context.MODE_PRIVATE
            )
        }


    /*
     * ---------------------------------------------------------
     * HEADCODE / SIGNALLING ID
     * ---------------------------------------------------------
     */

    var headcode by remember {

        mutableStateOf(
            sectionPreferences
                .getString(
                    "last_headcode",
                    ""
                )
                ?: ""
        )
    }


    val headcodeValid =

        Regex(
            "^[0-9][A-Z][0-9]{2}$"
        )
            .matches(
                headcode
            )


    /*
     * ---------------------------------------------------------
     * DEVICE
     * ---------------------------------------------------------
     */

    var deviceName by remember {

        mutableStateOf(
            getDeviceName(
                context
            )
        )
    }


    /*
     * ---------------------------------------------------------
     * PATHFINDER ENTITLEMENT
     * ---------------------------------------------------------
     */

    var pathfinderAvailable by remember {

        mutableStateOf(
            getCachedPathfinderAvailability(
                context
            )
        )
    }


    var pathfinderModeSelected by remember {

        mutableStateOf(
            sectionPreferences
                .getBoolean(
                    "pathfinder_mode_preference",
                    false
                )
        )
    }


    var activePathfinderMode by remember {

        mutableStateOf(
            recordingPreferences
                .getBoolean(
                    JourneyRecordingService
                        .KEY_PATHFINDER_MODE,
                    false
                )
        )
    }


    /*
     * Periodically refresh cloud entitlement.
     *
     * A temporary network error leaves the last successful
     * entitlement unchanged.
     */
    LaunchedEffect(
        Unit
    ) {

        while (
            true
        ) {

            val cloudDeviceId =

                getCloudDeviceId(
                    context
                )


            if (
                cloudDeviceId != null
            ) {

                try {

                    val enabled =

                        SectionIQSupabase
                            .isPathfinderEnabled(
                                cloudDeviceId
                            )


                    pathfinderAvailable =
                        enabled


                    savePathfinderAvailability(
                        context =
                            context,
                        enabled =
                            enabled
                    )


                    if (
                        !enabled
                    ) {

                        pathfinderModeSelected =
                            false
                    }


                } catch (
                    _: Exception
                ) {

                    /*
                     * Keep cached entitlement during temporary
                     * connectivity loss.
                     */
                }
            }


            delay(
                15_000L
            )
        }
    }


    /*
     * ---------------------------------------------------------
     * LOCATION PERMISSION
     * ---------------------------------------------------------
     */

    var hasFineLocationPermission by remember {

        mutableStateOf(

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission
                    .ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }


    val locationPermissionLauncher =

        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .RequestMultiplePermissions()
        ) { permissions ->

            hasFineLocationPermission =

                permissions[
                    Manifest.permission
                        .ACCESS_FINE_LOCATION
                ] == true
        }


    LaunchedEffect(
        Unit
    ) {

        if (
            !hasFineLocationPermission
        ) {

            locationPermissionLauncher
                .launch(
                    arrayOf(
                        Manifest.permission
                            .ACCESS_COARSE_LOCATION,

                        Manifest.permission
                            .ACCESS_FINE_LOCATION
                    )
                )
        }
    }


    /*
     * ---------------------------------------------------------
     * NOTIFICATION PERMISSION
     * ---------------------------------------------------------
     */

    val notificationPermissionLauncher =

        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .RequestPermission()
        ) {
            // No extra action required.
        }


    LaunchedEffect(
        Unit
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val hasNotificationPermission =

                ContextCompat
                    .checkSelfPermission(
                        context,
                        Manifest.permission
                            .POST_NOTIFICATIONS
                    ) ==
                        PackageManager
                            .PERMISSION_GRANTED


            if (
                !hasNotificationPermission
            ) {

                notificationPermissionLauncher
                    .launch(
                        Manifest.permission
                            .POST_NOTIFICATIONS
                    )
            }
        }
    }


    /*
     * ---------------------------------------------------------
     * LIVE GPS WHILE APP OPEN
     * ---------------------------------------------------------
     */

    var latitude by remember {

        mutableStateOf<Double?>(
            null
        )
    }


    var longitude by remember {

        mutableStateOf<Double?>(
            null
        )
    }


    var gpsAccuracy by remember {

        mutableStateOf<Float?>(
            null
        )
    }


    val fusedLocationClient =

        remember {

            LocationServices
                .getFusedLocationProviderClient(
                    context
                )
        }


    DisposableEffect(
        hasFineLocationPermission
    ) {

        if (
            !hasFineLocationPermission
        ) {

            onDispose {
            }

        } else {

            val locationRequest =

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
                        locationResult:
                        LocationResult
                    ) {

                        val location =

                            locationResult
                                .lastLocation
                                ?: return


                        latitude =
                            location.latitude


                        longitude =
                            location.longitude


                        gpsAccuracy =
                            location.accuracy
                    }
                }


            try {

                fusedLocationClient
                    .requestLocationUpdates(
                        locationRequest,
                        callback,
                        Looper.getMainLooper()
                    )


            } catch (
                _: SecurityException
            ) {

                hasFineLocationPermission =
                    false
            }


            onDispose {

                fusedLocationClient
                    .removeLocationUpdates(
                        callback
                    )
            }
        }
    }


    val gpsReady =

        hasFineLocationPermission &&
                latitude != null &&
                longitude != null


    /*
     * ---------------------------------------------------------
     * RECORDING STATE
     * ---------------------------------------------------------
     */

    var isRecording by remember {

        mutableStateOf(

            recordingPreferences
                .getBoolean(
                    JourneyRecordingService
                        .KEY_RECORDING,
                    false
                )
        )
    }


    var pointCount by remember {

        mutableStateOf(

            recordingPreferences
                .getInt(
                    JourneyRecordingService
                        .KEY_POINT_COUNT,
                    0
                )
        )
    }


    var pathfinderMarkCount by remember {

        mutableStateOf(

            recordingPreferences
                .getInt(
                    JourneyRecordingService
                        .KEY_PATHFINDER_MARK_COUNT,
                    0
                )
        )
    }


    var sessionId by remember {

        mutableStateOf(

            recordingPreferences
                .getString(
                    JourneyRecordingService
                        .KEY_SESSION_ID,
                    null
                )
        )
    }


    var startedElapsed by remember {

        mutableLongStateOf(

            recordingPreferences
                .getLong(
                    JourneyRecordingService
                        .KEY_STARTED_ELAPSED,
                    0L
                )
        )
    }


    var elapsedSeconds by remember {

        mutableLongStateOf(
            0L
        )
    }


    var recordedAccuracy by remember {

        mutableStateOf(

            recordingPreferences
                .getFloat(
                    JourneyRecordingService
                        .KEY_LAST_ACCURACY,
                    0f
                )
        )
    }


    var recordingSpeed by remember {

        mutableStateOf<Float?>(
            null
        )
    }


    var recordingSpeedValid by remember {

        mutableStateOf(
            false
        )
    }


    var lastFixTime by remember {

        mutableLongStateOf(
            0L
        )
    }


    var eventMarkCount by remember {

        mutableStateOf(
            recordingPreferences
                .getInt(
                    JourneyRecordingService
                        .KEY_EVENT_MARK_COUNT,
                    0
                )
        )
    }


    var pendingEventId by remember {

        mutableStateOf(

            recordingPreferences
                .getString(
                    JourneyRecordingService
                        .KEY_PENDING_EVENT_ID,
                    null
                )
        )
    }


    var dismissedLiveEventId by remember {

        mutableStateOf<String?>(
            null
        )
    }


    var savedJourneyCount by remember {

        mutableStateOf(
            countSavedJourneys(
                context
            )
        )
    }


    /*
     * ---------------------------------------------------------
     * REFRESH SERVICE STATE
     * ---------------------------------------------------------
     */

    LaunchedEffect(
        Unit
    ) {

        while (
            true
        ) {

            deviceName =
                getDeviceName(
                    context
                )


            pathfinderAvailable =
                getCachedPathfinderAvailability(
                    context
                )


            isRecording =

                recordingPreferences
                    .getBoolean(
                        JourneyRecordingService
                            .KEY_RECORDING,
                        false
                    )


            pointCount =

                recordingPreferences
                    .getInt(
                        JourneyRecordingService
                            .KEY_POINT_COUNT,
                        0
                    )


            eventMarkCount =

                recordingPreferences
                    .getInt(
                        JourneyRecordingService
                            .KEY_EVENT_MARK_COUNT,
                        0
                    )


            pendingEventId =

                recordingPreferences
                    .getString(
                        JourneyRecordingService
                            .KEY_PENDING_EVENT_ID,
                        null
                    )


            pathfinderMarkCount =

                recordingPreferences
                    .getInt(
                        JourneyRecordingService
                            .KEY_PATHFINDER_MARK_COUNT,
                        0
                    )


            activePathfinderMode =

                recordingPreferences
                    .getBoolean(
                        JourneyRecordingService
                            .KEY_PATHFINDER_MODE,
                        false
                    )


            sessionId =

                recordingPreferences
                    .getString(
                        JourneyRecordingService
                            .KEY_SESSION_ID,
                        null
                    )


            startedElapsed =

                recordingPreferences
                    .getLong(
                        JourneyRecordingService
                            .KEY_STARTED_ELAPSED,
                        0L
                    )


            recordedAccuracy =

                recordingPreferences
                    .getFloat(
                        JourneyRecordingService
                            .KEY_LAST_ACCURACY,
                        0f
                    )


            recordingSpeedValid =

                recordingPreferences
                    .getBoolean(
                        JourneyRecordingService
                            .KEY_LAST_SPEED_VALID,
                        false
                    )


            recordingSpeed =

                if (
                    recordingSpeedValid
                ) {

                    recordingPreferences
                        .getFloat(
                            JourneyRecordingService
                                .KEY_LAST_SPEED,
                            0f
                        )

                } else {

                    null
                }


            lastFixTime =

                recordingPreferences
                    .getLong(
                        JourneyRecordingService
                            .KEY_LAST_FIX_TIME,
                        0L
                    )


            if (
                isRecording &&
                startedElapsed > 0L
            ) {

                elapsedSeconds =

                    (
                            SystemClock
                                .elapsedRealtime() -
                                    startedElapsed
                            ) /
                            1000L

            } else {

                elapsedSeconds =
                    0L
            }


            savedJourneyCount =
                countSavedJourneys(
                    context
                )


            delay(
                500L
            )
        }
    }


    /*
     * ---------------------------------------------------------
     * PATHFINDER LIVE CONDITIONS
     * ---------------------------------------------------------
     */

    val recordingFixFresh =

        lastFixTime > 0L &&
                (
                        System.currentTimeMillis() -
                                lastFixTime
                        ) in
                0L..
                JourneyRecordingService
                    .PATHFINDER_MAX_FIX_AGE_MS


    val pathfinderStationary =

        recordingSpeedValid &&
                recordingFixFresh &&
                (
                        recordingSpeed
                            ?: Float.MAX_VALUE
                        ) <=
                JourneyRecordingService
                    .PATHFINDER_MAX_SPEED_MPS


    /*
     * ---------------------------------------------------------
     * UI
     * ---------------------------------------------------------
     */

    /*
     * ---------------------------------------------------------
     * LIVE EVENT EDITOR
     * ---------------------------------------------------------
     */

    if (
        isRecording &&
        pendingEventId != null &&
        pendingEventId != dismissedLiveEventId
    ) {

        val liveEventId =
            pendingEventId


        Column(
            modifier =

                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal =
                            22.dp,
                        vertical =
                            28.dp
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {


            Text(
                text =
                    "Event marked",

                fontSize =
                    28.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    Color(0xFF12263A)
            )


            Spacer(
                modifier =
                    Modifier.height(
                        4.dp
                    )
            )


            Text(
                text =
                    "Journey recording continues",

                fontSize =
                    13.sp,

                color =
                    Color(0xFF178447)
            )


            Spacer(
                modifier =
                    Modifier.height(
                        18.dp
                    )
            )


            Text(
                text =
                    "What happened?",

                fontSize =
                    16.sp,

                fontWeight =
                    FontWeight.Bold,

                modifier =
                    Modifier.fillMaxWidth()
            )


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {

                Button(
                    onClick = {
                        selectedObservationKind =
                            "station_call"
                    },

                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .height(
                                48.dp
                            )
                ) {

                    Text(
                        "Station call",
                        fontSize =
                            12.sp
                    )
                }


                Button(
                    onClick = {
                        selectedObservationKind =
                            "running_event"
                    },

                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .height(
                                48.dp
                            )
                ) {

                    Text(
                        "Running event",
                        fontSize =
                            12.sp
                    )
                }
            }


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {

                Button(
                    onClick = {
                        selectedObservationKind =
                            "train_issue"
                    },

                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .height(
                                48.dp
                            )
                ) {

                    Text(
                        "Train issue",
                        fontSize =
                            12.sp
                    )
                }


                Button(
                    onClick = {
                        selectedObservationKind =
                            "other"
                    },

                    modifier =
                        Modifier
                            .weight(
                                1f
                            )
                            .height(
                                48.dp
                            )
                ) {

                    Text(
                        "Other",
                        fontSize =
                            12.sp
                    )
                }
            }


            Spacer(
                modifier =
                    Modifier.height(
                        10.dp
                    )
            )


            Text(
                text =

                    when (
                        selectedObservationKind
                    ) {

                        "station_call" ->
                            "Selected: Station call"

                        "running_event" ->
                            "Selected: Running event"

                        "train_issue" ->
                            "Selected: Train issue"

                        else ->
                            "Selected: Other"
                    },

                fontSize =
                    12.sp,

                fontWeight =
                    FontWeight.Medium,

                color =
                    Color(0xFF607080),

                modifier =
                    Modifier.fillMaxWidth()
            )


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            OutlinedTextField(
                value =
                    observationNote,

                onValueChange = {
                    observationNote =
                        it
                },

                label = {
                    Text(
                        "Notes"
                    )
                },

                placeholder = {
                    Text(
                        "e.g. wheelchair ramp deployed"
                    )
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            105.dp
                        ),

                maxLines =
                    3
            )


            Spacer(
                modifier =
                    Modifier.height(
                        14.dp
                    )
            )


            Button(
                onClick = {

                    if (
                        liveEventId != null
                    ) {

                        val completeIntent =

                            Intent(
                                context,
                                JourneyRecordingService::class.java
                            ).apply {

                                action =
                                    JourneyRecordingService
                                        .ACTION_COMPLETE_EVENT

                                putExtra(
                                    JourneyRecordingService
                                        .EXTRA_EVENT_ID,
                                    liveEventId
                                )

                                putExtra(
                                    JourneyRecordingService
                                        .EXTRA_EVENT_KIND,
                                    selectedObservationKind
                                )

                                putExtra(
                                    JourneyRecordingService
                                        .EXTRA_EVENT_NOTE,
                                    observationNote
                                )
                            }


                        context.startService(
                            completeIntent
                        )


                        dismissedLiveEventId =
                            liveEventId


                        selectedObservationKind =
                            "other"


                        observationNote =
                            ""
                    }
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            54.dp
                        )
            ) {

                Text(
                    text =
                        "SAVE",

                    fontWeight =
                        FontWeight.Bold
                )
            }


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            Button(
                onClick = {

                    dismissedLiveEventId =
                        liveEventId

                    recordingPreferences
                        .edit()
                        .remove(
                            JourneyRecordingService
                                .KEY_PENDING_EVENT_ID
                        )
                        .apply()

                    pendingEventId =
                        null

                    selectedObservationKind =
                        "other"

                    observationNote =
                        ""
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            46.dp
                        ),

                colors =
                    ButtonDefaults
                        .buttonColors(

                            containerColor =
                                Color(0xFFE1E6EB),

                            contentColor =
                                Color(0xFF12263A)
                        )
            ) {

                Text(
                    text =
                        "LATER",

                    fontWeight =
                        FontWeight.Bold
                )
            }
        }


        return
    }


    Column(
        modifier =

            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal =
                        24.dp
                )
                .padding(
                    top =
                        48.dp,
                    bottom =
                        32.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {


        /*
         * BRAND
         */

        Text(
            text =
                "SectionIQ",
            fontSize =
                42.sp,
            fontWeight =
                FontWeight.Bold,
            color =
                Color(0xFF12263A)
        )


        Text(
            text =
                "Rail Performance Intelligence",
            fontSize =
                16.sp,
            color =
                Color(0xFF607080)
        )


        Spacer(
            modifier =
                Modifier.height(
                    28.dp
                )
        )


        /*
         * MAIN STATUS CARD
         */

        Box(
            modifier =

                Modifier
                    .fillMaxWidth()
                    .background(
                        color =
                            Color.White,
                        shape =
                            RoundedCornerShape(
                                20.dp
                            )
                    )
                    .padding(
                        24.dp
                    )
        ) {

            Column(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {


                Text(
                    text =
                        "DEVICE",
                    fontSize =
                        12.sp,
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        Color(0xFF7A8793)
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            4.dp
                        )
                )


                Text(
                    text =
                        deviceName,
                    fontSize =
                        23.sp,
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        Color(0xFF12263A),
                    textAlign =
                        TextAlign.Center
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            22.dp
                        )
                )


                Text(
                    text =

                        if (
                            isRecording
                        ) {

                            "JOURNEY STATUS"

                        } else {

                            "GPS STATUS"
                        },

                    fontSize =
                        12.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color(0xFF7A8793)
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            7.dp
                        )
                )


                Text(
                    text =

                        when {

                            isRecording ->
                                "●  RECORDING"

                            !hasFineLocationPermission ->
                                "●  PRECISE LOCATION REQUIRED"

                            !gpsReady ->
                                "●  ACQUIRING GPS"

                            else ->
                                "●  GPS READY"
                        },

                    fontSize =
                        18.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =

                        when {

                            isRecording ->
                                Color(0xFFD84343)

                            gpsReady ->
                                Color(0xFF178447)

                            else ->
                                Color(0xFFD18B00)
                        },

                    textAlign =
                        TextAlign.Center
                )


                if (
                    isRecording
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(
                                22.dp
                            )
                    )


                    Text(
                        text =
                            formatElapsedTime(
                                elapsedSeconds
                            ),

                        fontSize =
                            36.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF12263A)
                    )


                    Text(
                        text =
                            "Journey duration",
                        fontSize =
                            12.sp,
                        color =
                            Color(0xFF7A8793)
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                20.dp
                            )
                    )


                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.SpaceEvenly
                    ) {


                        Column(
                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {

                            Text(
                                text =
                                    pointCount
                                        .toString(),

                                fontSize =
                                    24.sp,

                                fontWeight =
                                    FontWeight.Bold,

                                color =
                                    Color(0xFF12263A)
                            )


                            Text(
                                text =
                                    "GPS points",
                                fontSize =
                                    12.sp,
                                color =
                                    Color(0xFF7A8793)
                            )
                        }


                        Column(
                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {

                            Text(
                                text =
                                    "%.1f m"
                                        .format(
                                            Locale.UK,
                                            recordedAccuracy
                                        ),

                                fontSize =
                                    24.sp,

                                fontWeight =
                                    FontWeight.Bold,

                                color =
                                    Color(0xFF12263A)
                            )


                            Text(
                                text =
                                    "Accuracy",
                                fontSize =
                                    12.sp,
                                color =
                                    Color(0xFF7A8793)
                            )
                        }
                    }


                    if (
                        sessionId != null
                    ) {

                        Spacer(
                            modifier =
                                Modifier.height(
                                    16.dp
                                )
                        )


                        Text(
                            text =
                                "Session ${
                                    sessionId!!
                                        .take(
                                            8
                                        )
                                        .uppercase()
                                }",

                            fontSize =
                                11.sp,

                            color =
                                Color(0xFF9AA3AB)
                        )
                    }


                } else if (
                    gpsReady
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(
                                16.dp
                            )
                    )


                    Text(
                        text =
                            "GPS accuracy: %.1f metres"
                                .format(
                                    Locale.UK,
                                    gpsAccuracy
                                        ?: 0f
                                ),

                        fontSize =
                            13.sp,

                        color =
                            Color(0xFF607080)
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            20.dp
                        )
                )


                Text(
                    text =
                        "$savedJourneyCount saved journey" +
                                if (
                                    savedJourneyCount ==
                                    1
                                ) {
                                    ""
                                } else {
                                    "s"
                                },

                    fontSize =
                        12.sp,

                    fontWeight =
                        FontWeight.Medium,

                    color =
                        Color(0xFF7A8793)
                )
            }
        }


        /*
         * -----------------------------------------------------
         * PATHFINDER MODE SELECTOR
         * -----------------------------------------------------
         */

        if (
            pathfinderAvailable &&
            !isRecording
        ) {

            Spacer(
                modifier =
                    Modifier.height(
                        18.dp
                    )
            )


            Box(
                modifier =

                    Modifier
                        .fillMaxWidth()
                        .background(
                            color =

                                if (
                                    pathfinderModeSelected
                                ) {

                                    Color(0xFFE9F5EE)

                                } else {

                                    Color.White
                                },

                            shape =
                                RoundedCornerShape(
                                    20.dp
                                )
                        )
                        .padding(
                            20.dp
                        )
            ) {

                Column(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {


                    Text(
                        text =
                            "PATHFINDER",
                        fontSize =
                            12.sp,
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            Color(0xFF178447)
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                6.dp
                            )
                    )


                    Text(
                        text =
                            "Station Datum Collection",
                        fontSize =
                            20.sp,
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            Color(0xFF12263A)
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )


                    Text(
                        text =
                            "Use Pathfinder while seated as close as practicable " +
                                    "to the leading driving cab.",

                        fontSize =
                            13.sp,

                        color =
                            Color(0xFF607080),

                        textAlign =
                            TextAlign.Center
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                16.dp
                            )
                    )


                    Button(
                        onClick = {

                            pathfinderModeSelected =
                                !pathfinderModeSelected


                            sectionPreferences
                                .edit()
                                .putBoolean(
                                    "pathfinder_mode_preference",
                                    pathfinderModeSelected
                                )
                                .apply()
                        },

                        modifier =

                            Modifier
                                .fillMaxWidth()
                                .height(
                                    54.dp
                                ),

                        shape =
                            RoundedCornerShape(
                                16.dp
                            ),

                        colors =
                            ButtonDefaults
                                .buttonColors(

                                    containerColor =

                                        if (
                                            pathfinderModeSelected
                                        ) {

                                            Color(0xFF178447)

                                        } else {

                                            Color(0xFFE4E9ED)
                                        },

                                    contentColor =

                                        if (
                                            pathfinderModeSelected
                                        ) {

                                            Color.White

                                        } else {

                                            Color(0xFF12263A)
                                        }
                                )
                    ) {

                        Text(
                            text =

                                if (
                                    pathfinderModeSelected
                                ) {

                                    "PATHFINDER MODE ON"

                                } else {

                                    "ENABLE PATHFINDER MODE"
                                },

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }
        }


        /*
         * -----------------------------------------------------
         * ACTIVE PATHFINDER
         * -----------------------------------------------------
         */

        if (
            isRecording &&
            activePathfinderMode
        ) {

            Spacer(
                modifier =
                    Modifier.height(
                        18.dp
                    )
            )


            Box(
                modifier =

                    Modifier
                        .fillMaxWidth()
                        .background(
                            color =
                                Color(0xFFE9F5EE),
                            shape =
                                RoundedCornerShape(
                                    20.dp
                                )
                        )
                        .padding(
                            20.dp
                        )
            ) {

                Column(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {


                    Text(
                        text =
                            "PATHFINDER ACTIVE",
                        fontSize =
                            13.sp,
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            Color(0xFF178447)
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )


                    Text(
                        text =
                            "$pathfinderMarkCount station " +
                                    if (
                                        pathfinderMarkCount ==
                                        1
                                    ) {

                                        "mark"

                                    } else {

                                        "marks"
                                    },

                        fontSize =
                            13.sp,

                        color =
                            Color(0xFF607080)
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                14.dp
                            )
                    )


                    Button(
                        onClick = {

                            val intent =

                                Intent(
                                    context,
                                    JourneyRecordingService::class.java
                                ).apply {

                                    action =
                                        JourneyRecordingService
                                            .ACTION_MARK_STATION
                                }


                            context.startService(
                                intent
                            )
                        },

                        enabled =
                            pathfinderStationary,

                        modifier =

                            Modifier
                                .fillMaxWidth()
                                .height(
                                    70.dp
                                ),

                        shape =
                            RoundedCornerShape(
                                18.dp
                            ),

                        colors =
                            ButtonDefaults
                                .buttonColors(

                                    containerColor =
                                        Color(0xFF178447),

                                    contentColor =
                                        Color.White,

                                    disabledContainerColor =
                                        Color(0xFFB5BDC5),

                                    disabledContentColor =
                                        Color.White
                                )
                    ) {

                        Text(
                            text =
                                "MARK STATION",
                            fontSize =
                                20.sp,
                            fontWeight =
                                FontWeight.Bold
                        )
                    }


                    Spacer(
                        modifier =
                            Modifier.height(
                                10.dp
                            )
                    )


                    Text(
                        text =

                            when {

                                !recordingSpeedValid ->
                                    "Waiting for reliable speed data"

                                !recordingFixFresh ->
                                    "Waiting for a fresh GPS fix"

                                !pathfinderStationary ->
                                    "Station marking becomes available when stationary"

                                else ->
                                    "Stationary • ready to mark"
                            },

                        fontSize =
                            12.sp,

                        color =
                            Color(0xFF607080),

                        textAlign =
                            TextAlign.Center
                    )


                    if (
                        recordingSpeedValid &&
                        recordingSpeed != null
                    ) {

                        Spacer(
                            modifier =
                                Modifier.height(
                                    5.dp
                                )
                        )


                        Text(
                            text =
                                "Current speed: %.2f m/s"
                                    .format(
                                        Locale.UK,
                                        recordingSpeed
                                    ),

                            fontSize =
                                11.sp,

                            color =
                                Color(0xFF7A8793)
                        )
                    }
                }
            }
        }


        if (
            isRecording
        ) {

            Spacer(
                modifier =
                    Modifier.height(
                        24.dp
                    )
            )


            Button(
                onClick = {

                    val markIntent =

                        Intent(
                            context,
                            JourneyRecordingService::class.java
                        ).apply {

                            action =
                                JourneyRecordingService
                                    .ACTION_MARK_EVENT
                        }


                    context.startService(
                        markIntent
                    )
                },

                modifier =

                    Modifier
                        .fillMaxWidth()
                        .height(
                            76.dp
                        ),

                shape =
                    RoundedCornerShape(
                        18.dp
                    ),

                colors =
                    ButtonDefaults
                        .buttonColors(

                            containerColor =
                                Color(0xFF1E6FA8),

                            contentColor =
                                Color.White
                        )
            ) {

                Text(
                    text =
                        "MARK EVENT",

                    fontSize =
                        22.sp,

                    fontWeight =
                        FontWeight.Bold
                )
            }


            Spacer(
                modifier =
                    Modifier.height(
                        10.dp
                    )
            )


            Text(
                text =
                    "$eventMarkCount observation" +
                            if (
                                eventMarkCount == 1
                            ) {
                                " marked"
                            } else {
                                "s marked"
                            },

                fontSize =
                    13.sp,

                fontWeight =
                    FontWeight.Medium,

                color =
                    Color(0xFF607080)
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    24.dp
                )
        )


        /*
         * -----------------------------------------------------
         * REVIEW MARKED OBSERVATIONS
         * -----------------------------------------------------
         */

        if (
            !isRecording
        ) {

            Button(
                onClick = {

                    observationScope.launch {

                        pendingObservationsLoading =
                            true

                        observationError =
                            null

                        try {

                            pendingObservations =

                                SectionIQSupabase
                                    .getMarkedObservations()


                            pendingObservationsLoaded =
                                true

                        } catch (
                            e: Exception
                        ) {

                            observationError =
                                "Could not load observations"

                            Log.e(
                                "SectionIQObservation",
                                "Could not load marked observations",
                                e
                            )

                        } finally {

                            pendingObservationsLoading =
                                false
                        }
                    }
                },

                enabled =
                    !pendingObservationsLoading,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        ),

                shape =
                    RoundedCornerShape(
                        16.dp
                    )
            ) {

                Text(
                    text =
                        if (
                            pendingObservationsLoading
                        ) {
                            "Loading observations..."
                        } else {
                            "REVIEW OBSERVATIONS"
                        },

                    fontWeight =
                        FontWeight.Bold
                )
            }


            if (
                pendingObservationsLoaded
            ) {

                Spacer(
                    modifier =
                        Modifier.height(
                            10.dp
                        )
                )


                Text(
                    text =

                        if (
                            pendingObservations.isEmpty()
                        ) {

                            "No observations awaiting completion"

                        } else {

                            "${pendingObservations.size} observation" +
                                    if (
                                        pendingObservations.size == 1
                                    ) {
                                        " to complete"
                                    } else {
                                        "s to complete"
                                    }
                        },

                    fontSize =
                        13.sp,

                    fontWeight =
                        FontWeight.Medium,

                    color =
                        Color(0xFF607080)
                )
            }


            if (
                pendingObservations.isNotEmpty()
            ) {

                val currentObservation =
                    pendingObservations.first()


                Spacer(
                    modifier =
                        Modifier.height(
                            20.dp
                        )
                )


                Text(
                    text =
                        "Complete observation",

                    fontSize =
                        18.sp,

                    fontWeight =
                        FontWeight.Bold
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            6.dp
                        )
                )


                Text(
                    text =
                        "Observation 1 of ${pendingObservations.size}",

                    fontSize =
                        13.sp,

                    color =
                        Color(0xFF607080)
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            16.dp
                        )
                )


                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {

                    Button(
                        onClick = {
                            selectedObservationKind =
                                "station_call"
                        },

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {

                        Text(
                            "Station call",
                            fontSize =
                                12.sp
                        )
                    }


                    Button(
                        onClick = {
                            selectedObservationKind =
                                "running_event"
                        },

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {

                        Text(
                            "Running event",
                            fontSize =
                                12.sp
                        )
                    }
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            8.dp
                        )
                )


                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {

                    Button(
                        onClick = {
                            selectedObservationKind =
                                "train_issue"
                        },

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {

                        Text(
                            "Train issue",
                            fontSize =
                                12.sp
                        )
                    }


                    Button(
                        onClick = {
                            selectedObservationKind =
                                "other"
                        },

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {

                        Text(
                            "Other",
                            fontSize =
                                12.sp
                        )
                    }
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )


                Text(
                    text =
                        "Selected: " +
                                when (
                                    selectedObservationKind
                                ) {

                                    "station_call" ->
                                        "Station call"

                                    "running_event" ->
                                        "Running event"

                                    "train_issue" ->
                                        "Train issue"

                                    else ->
                                        "Other"
                                },

                    fontSize =
                        13.sp,

                    fontWeight =
                        FontWeight.Medium
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )


                OutlinedTextField(
                    value =
                        observationNote,

                    onValueChange = {
                        observationNote =
                            it
                    },

                    label = {
                        Text(
                            "What happened?"
                        )
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    minLines =
                        3
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            14.dp
                        )
                )


                Button(
                    onClick = {

                        observationScope.launch {

                            observationError =
                                null

                            try {

                                SectionIQSupabase
                                    .completeObservation(
                                        observationId =
                                            currentObservation.id,

                                        eventKind =
                                            selectedObservationKind,

                                        freeText =
                                            observationNote
                                    )


                                pendingObservations =

                                    pendingObservations
                                        .filter {
                                            it.id !=
                                                    currentObservation.id
                                        }


                                selectedObservationKind =
                                    "other"


                                observationNote =
                                    ""

                            } catch (
                                e: Exception
                            ) {

                                observationError =
                                    "Could not complete observation"

                                Log.e(
                                    "SectionIQObservation",
                                    "Observation completion failed",
                                    e
                                )
                            }
                        }
                    },

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                56.dp
                            )
                ) {

                    Text(
                        text =
                            "COMPLETE OBSERVATION",

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }

            if (
                observationError != null
            ) {

                Spacer(
                    modifier =
                        Modifier.height(
                            8.dp
                        )
                )


                Text(
                    text =
                        observationError
                            ?: "",

                    fontSize =
                        13.sp,

                    color =
                        Color(0xFFB3261E)
                )
            }


            Spacer(
                modifier =
                    Modifier.height(
                        24.dp
                    )
            )
        }

        /*
         * -----------------------------------------------------
         * START / STOP JOURNEY
         * -----------------------------------------------------
         */

        Button(
            onClick = {

                if (
                    !isRecording
                ) {

                    /*
                     * Freeze the selected mode into this journey.
                     */
                    val journeyPathfinderMode =

                        pathfinderAvailable &&
                                pathfinderModeSelected


                    recordingPreferences
                        .edit()
                        .putBoolean(
                            JourneyRecordingService
                                .KEY_PATHFINDER_MODE,
                            journeyPathfinderMode
                        )
                        .apply()


                    val startIntent =

                        Intent(
                            context,
                            JourneyRecordingService::class.java
                        ).apply {

                            action =
                                JourneyRecordingService
                                    .ACTION_START
                        }


                    ContextCompat
                        .startForegroundService(
                            context,
                            startIntent
                        )


                } else {

                    val stopIntent =

                        Intent(
                            context,
                            JourneyRecordingService::class.java
                        ).apply {

                            action =
                                JourneyRecordingService
                                    .ACTION_STOP
                        }


                    context.startService(
                        stopIntent
                    )
                }
            },

            enabled =

                if (
                    isRecording
                ) {

                    true

                } else {

                    gpsReady
                },

            modifier =

                Modifier
                    .fillMaxWidth()
                    .height(
                        64.dp
                    ),

            shape =
                RoundedCornerShape(
                    18.dp
                ),

            colors =
                ButtonDefaults
                    .buttonColors(

                        containerColor =

                            if (
                                isRecording
                            ) {

                                Color(0xFFD84343)

                            } else {

                                Color(0xFF12263A)
                            },

                        contentColor =
                            Color.White,

                        disabledContainerColor =
                            Color(0xFFB5BDC5),

                        disabledContentColor =
                            Color.White
                    )
        ) {

            Text(
                text =

                    if (
                        isRecording
                    ) {

                        "STOP JOURNEY"

                    } else if (
                        pathfinderModeSelected &&
                        pathfinderAvailable
                    ) {

                        "START PATHFINDER JOURNEY"

                    } else {

                        "START JOURNEY"
                    },

                fontSize =
                    18.sp,

                fontWeight =
                    FontWeight.Bold
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    14.dp
                )
        )


        Text(
            text =

                when {

                    isRecording &&
                            activePathfinderMode ->

                        "Recording continues in the background • Pathfinder active"

                    isRecording ->

                        "Recording continues while SectionIQ is in the background"

                    gpsReady &&
                            pathfinderModeSelected &&
                            pathfinderAvailable ->

                        "Ready to record a Pathfinder journey"

                    gpsReady ->

                        "Ready to record"

                    !hasFineLocationPermission ->

                        "SectionIQ requires precise location"

                    else ->

                        "Waiting for a precise GPS fix"
                },

            fontSize =
                13.sp,

            color =
                Color(0xFF7A8793),

            textAlign =
                TextAlign.Center
        )
    }
}


/*
 * ---------------------------------------------------------
 * JOURNEY FILE FUNCTIONS
 * ---------------------------------------------------------
 */


fun createJourneyFile(
    context: Context,
    sessionId: String,
    deviceName: String
): File {

    val directory =

        File(
            context.filesDir,
            "journeys"
        )


    if (
        !directory.exists()
    ) {

        directory.mkdirs()
    }


    val file =

        File(
            directory,
            "sectioniq_$sessionId.jsonl"
        )


    val startRecord =

        JSONObject().apply {

            put(
                "record_type",
                "session_start"
            )

            put(
                "schema_version",
                1
            )

            put(
                "session_id",
                sessionId
            )

            put(
                "device_name",
                deviceName
            )

            put(
                "started_at_ms",
                System.currentTimeMillis()
            )
        }


    file.writeText(
        startRecord.toString() +
                "\n"
    )


    return file
}


fun appendJourneyPoint(
    file: File?,
    point: JourneyPoint
) {

    if (
        file == null
    ) {

        return
    }


    val record =

        JSONObject().apply {

            put(
                "record_type",
                "gps_point"
            )

            put(
                "timestamp_ms",
                point.timestamp
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
                "speed_available",
                point.speedAvailable
            )

            if (
                point.speedAvailable
            ) {

                put(
                    "speed_mps",
                    point.speedMetresPerSecond
                )

            } else {

                put(
                    "speed_mps",
                    JSONObject.NULL
                )
            }

            put(
                "speed_accuracy_mps",
                point.speedAccuracyMetresPerSecond
                    ?: JSONObject.NULL
            )

            put(
                "elapsed_realtime_nanos",
                point.elapsedRealtimeNanos
            )

            put(
                "bearing_accuracy_deg",
                point.bearingAccuracyDegrees
                    ?: JSONObject.NULL
            )

            put(
                "vertical_accuracy_m",
                point.verticalAccuracyMetres
                    ?: JSONObject.NULL
            )

            put(
                "bearing_deg",
                point.bearingDegrees
            )


            if (
                point.altitudeMetres !=
                null
            ) {

                put(
                    "altitude_m",
                    point.altitudeMetres
                )

            } else {

                put(
                    "altitude_m",
                    JSONObject.NULL
                )
            }
        }


    file.appendText(
        record.toString() +
                "\n"
    )
}


fun finishJourneyFile(
    file: File?,
    pointCount: Int
) {

    if (
        file == null
    ) {

        return
    }


    val endRecord =

        JSONObject().apply {

            put(
                "record_type",
                "session_end"
            )

            put(
                "ended_at_ms",
                System.currentTimeMillis()
            )

            put(
                "point_count",
                pointCount
            )
        }


    file.appendText(
        endRecord.toString() +
                "\n"
    )
}


fun countSavedJourneys(
    context: Context
): Int {

    val directory =

        File(
            context.filesDir,
            "journeys"
        )


    if (
        !directory.exists()
    ) {

        return 0
    }


    return directory
        .listFiles { file ->

            file.isFile &&
                    file.name
                        .endsWith(
                            ".jsonl"
                        )
        }
        ?.size
        ?: 0
}


/*
 * ---------------------------------------------------------
 * DEVICE FUNCTIONS
 * ---------------------------------------------------------
 */


fun saveRegisteredDevice(
    context: Context,
    device: RegisteredDevice
) {

    val preferences =

        context.getSharedPreferences(
            "sectioniq_preferences",
            Context.MODE_PRIVATE
        )


    val deviceName =

        "SectionIQ Device " +
                device.deviceNumber


    preferences
        .edit()

        .putString(
            "device_name",
            deviceName
        )

        .putString(
            "cloud_device_id",
            device.id
        )

        .putLong(
            "cloud_device_number",
            device.deviceNumber
        )

        .apply()
}


fun getDeviceName(
    context: Context
): String {

    val preferences =

        context.getSharedPreferences(
            "sectioniq_preferences",
            Context.MODE_PRIVATE
        )


    return preferences
        .getString(
            "device_name",
            null
        )
        ?: "Registering device..."
}


fun getCloudDeviceId(
    context: Context
): String? {

    val preferences =

        context.getSharedPreferences(
            "sectioniq_preferences",
            Context.MODE_PRIVATE
        )


    return preferences
        .getString(
            "cloud_device_id",
            null
        )
}


fun savePathfinderAvailability(
    context: Context,
    enabled: Boolean
) {

    val preferences =

        context.getSharedPreferences(
            "sectioniq_preferences",
            Context.MODE_PRIVATE
        )


    preferences
        .edit()
        .putBoolean(
            "pathfinder_available",
            enabled
        )
        .apply()
}


fun getCachedPathfinderAvailability(
    context: Context
): Boolean {

    val preferences =

        context.getSharedPreferences(
            "sectioniq_preferences",
            Context.MODE_PRIVATE
        )


    return preferences
        .getBoolean(
            "pathfinder_available",
            false
        )
}


/*
 * ---------------------------------------------------------
 * GENERAL
 * ---------------------------------------------------------
 */


fun formatElapsedTime(
    totalSeconds: Long
): String {

    val hours =
        totalSeconds /
                3600


    val minutes =
        (
                totalSeconds %
                        3600
                ) /
                60


    val seconds =
        totalSeconds %
                60


    return String.format(
        Locale.UK,
        "%02d:%02d:%02d",
        hours,
        minutes,
        seconds
    )
}

package com.bodgejob.sectioniq

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.File
import java.time.Instant


/*
 * ---------------------------------------------------------
 * SECTIONIQ JOURNEY CLOUD UPLOADER
 * ---------------------------------------------------------
 *
 * Local JSONL remains the source of truth until Supabase
 * confirms all journey data has been stored.
 *
 * Upload sequence:
 *
 * 1. Parse completed local journey.
 * 2. Confirm/register cloud device.
 * 3. Create journey row as incomplete.
 * 4. Upload/verify GPS points.
 * 5. Upload/verify Pathfinder station marks.
 * 6. Mark journey complete.
 * 7. Write local uploaded marker.
 */


@Serializable
private data class CloudJourneyInsert(

    val id: String,

    @SerialName("device_id")
    val deviceId: String,

    @SerialName("started_at")
    val startedAt: String,

    @SerialName("ended_at")
    val endedAt: String?,

    @SerialName("point_count")
    val pointCount: Int,

    val status: String
)


@Serializable
private data class CloudJourneyRow(

    val id: String,

    @SerialName("device_id")
    val deviceId: String,

    @SerialName("started_at")
    val startedAt: String,

    @SerialName("ended_at")
    val endedAt: String? = null,

    @SerialName("point_count")
    val pointCount: Int,

    val status: String,

    @SerialName("uploaded_at")
    val uploadedAt: String
)


@Serializable
private data class CloudJourneyPointInsert(

    @SerialName("journey_id")
    val journeyId: String,

    @SerialName("sequence_number")
    val sequenceNumber: Int,

    @SerialName("recorded_at")
    val recordedAt: String,

    val latitude: Double,

    val longitude: Double,

    @SerialName("accuracy_m")
    val accuracyMetres: Float?,

    @SerialName("speed_mps")
    val speedMetresPerSecond: Float?,

    @SerialName("speed_available")
    val speedAvailable: Boolean?,

    @SerialName("speed_accuracy_mps")
    val speedAccuracyMetresPerSecond: Float?,

    @SerialName("elapsed_realtime_nanos")
    val elapsedRealtimeNanos: Long?,

    @SerialName("bearing_accuracy_deg")
    val bearingAccuracyDegrees: Float?,

    @SerialName("vertical_accuracy_m")
    val verticalAccuracyMetres: Float?,

    @SerialName("bearing_deg")
    val bearingDegrees: Float?,

    @SerialName("altitude_m")
    val altitudeMetres: Double?
)


@Serializable
private data class CloudJourneyPointRow(

    val id: Long,

    @SerialName("journey_id")
    val journeyId: String,

    @SerialName("sequence_number")
    val sequenceNumber: Int,

    @SerialName("recorded_at")
    val recordedAt: String,

    val latitude: Double,

    val longitude: Double,

    @SerialName("accuracy_m")
    val accuracyMetres: Float? = null,

    @SerialName("speed_mps")
    val speedMetresPerSecond: Float? = null,

    @SerialName("speed_available")
    val speedAvailable: Boolean? = null,

    @SerialName("speed_accuracy_mps")
    val speedAccuracyMetresPerSecond: Float? = null,

    @SerialName("elapsed_realtime_nanos")
    val elapsedRealtimeNanos: Long? = null,

    @SerialName("bearing_accuracy_deg")
    val bearingAccuracyDegrees: Float? = null,

    @SerialName("vertical_accuracy_m")
    val verticalAccuracyMetres: Float? = null,

    @SerialName("bearing_deg")
    val bearingDegrees: Float? = null,

    @SerialName("altitude_m")
    val altitudeMetres: Double? = null
)


/*
 * ---------------------------------------------------------
 * MANAGER OBSERVATION CLOUD MODELS
 * ---------------------------------------------------------
 */


@Serializable
private data class CloudJourneyObservationInsert(

    val id: String,

    @SerialName("journey_id")
    val journeyId: String,

    @SerialName("device_id")
    val deviceId: String,

    @SerialName("observed_at")
    val observedAt: String,

    val latitude: Double?,

    val longitude: Double?,

    @SerialName("accuracy_m")
    val accuracyMetres: Float?,

    val source: String,

    @SerialName("entry_status")
    val entryStatus: String,

    @SerialName("event_kind")
    val eventKind: String,

    @SerialName("evidence_source")
    val evidenceSource: String
)


@Serializable
private data class CloudJourneyObservationRow(

    val id: String,

    @SerialName("journey_id")
    val journeyId: String,

    @SerialName("device_id")
    val deviceId: String
)

/*
 * ---------------------------------------------------------
 * PATHFINDER CLOUD MODELS
 * ---------------------------------------------------------
 */


@Serializable
private data class CloudPathfinderMarkInsert(

    val id: String,

    @SerialName("device_id")
    val deviceId: String,

    @SerialName("journey_id")
    val journeyId: String,

    @SerialName("station_id")
    val stationId: Long?,

    @SerialName("recorded_at")
    val recordedAt: String,

    @SerialName("sequence_number")
    val sequenceNumber: Int?,

    val latitude: Double,

    val longitude: Double,

    @SerialName("accuracy_m")
    val accuracyMetres: Float?,

    @SerialName("speed_mps")
    val speedMetresPerSecond: Float?,

    @SerialName("train_position")
    val trainPosition: String,

    @SerialName("confirmation_status")
    val confirmationStatus: String
)


@Serializable
private data class CloudPathfinderMarkRow(

    val id: String,

    @SerialName("device_id")
    val deviceId: String,

    @SerialName("journey_id")
    val journeyId: String,

    @SerialName("station_id")
    val stationId: Long? = null,

    @SerialName("recorded_at")
    val recordedAt: String,

    @SerialName("sequence_number")
    val sequenceNumber: Int? = null,

    val latitude: Double,

    val longitude: Double,

    @SerialName("accuracy_m")
    val accuracyMetres: Float? = null,

    @SerialName("speed_mps")
    val speedMetresPerSecond: Float? = null,

    @SerialName("train_position")
    val trainPosition: String,

    @SerialName("confirmation_status")
    val confirmationStatus: String,

    @SerialName("created_at")
    val createdAt: String,

    @SerialName("reviewed_at")
    val reviewedAt: String? = null,

    @SerialName("reviewed_by")
    val reviewedBy: String? = null
)


/*
 * ---------------------------------------------------------
 * LOCAL PARSED MODELS
 * ---------------------------------------------------------
 */


private data class ParsedJourney(

    val sessionId: String,

    val startedAtMs: Long,

    val endedAtMs: Long,

    val points:
    List<ParsedJourneyPoint>,

    val observations:
    List<ParsedJourneyObservation>,

    val pathfinderMarks:
    List<ParsedPathfinderMark>
)


private data class ParsedJourneyPoint(

    val timestampMs: Long,

    val latitude: Double,

    val longitude: Double,

    val accuracyMetres: Float?,

    val speedMetresPerSecond: Float?,

    val speedAvailable: Boolean?,

    val speedAccuracyMetresPerSecond: Float?,

    val elapsedRealtimeNanos: Long?,

    val bearingAccuracyDegrees: Float?,

    val verticalAccuracyMetres: Float?,

    val bearingDegrees: Float?,

    val altitudeMetres: Double?
)


private data class ParsedJourneyObservation(

    val id: String,

    val sessionId: String,

    val timestampMs: Long,

    val latitude: Double?,

    val longitude: Double?,

    val accuracyMetres: Float?,

    val source: String,

    val entryStatus: String,

    val eventKind: String,

    val evidenceSource: String
)

private data class ParsedPathfinderMark(

    val id: String,

    val sessionId: String,

    val timestampMs: Long,

    val sequenceNumber: Int?,

    val latitude: Double,

    val longitude: Double,

    val accuracyMetres: Float?,

    val speedMetresPerSecond: Float?,

    val stationId: Long?,

    val trainPosition: String
)


object JourneyUploader {


    /*
     * ---------------------------------------------------------
     * UPLOAD ONE COMPLETED JOURNEY
     * ---------------------------------------------------------
     */

    suspend fun uploadJourneyFile(
        context: Context,
        file: File
    ): Boolean {

        if (
            hasUploadedMarker(
                file
            )
        ) {

            Log.d(
                "SectionIQCloud",
                "Journey already uploaded: ${file.name}"
            )

            return true
        }


        /*
         * Parse local JSONL first.
         */
        val parsedJourney =

            parseCompletedJourney(
                file
            )


        /*
         * Confirm cloud device.
         */
        val device =

            SectionIQSupabase
                .registerDevice()


        val client =
            SectionIQSupabase.client


        /*
         * -----------------------------------------------------
         * EXISTING JOURNEY?
         * -----------------------------------------------------
         */

        val existingJourney =

            client.postgrest[
                "journeys"
            ]
                .select {

                    filter {

                        eq(
                            "id",
                            parsedJourney.sessionId
                        )
                    }
                }
                .decodeList<
                        CloudJourneyRow
                        >()
                .firstOrNull()


        /*
         * -----------------------------------------------------
         * CREATE JOURNEY
         * -----------------------------------------------------
         */

        if (
            existingJourney ==
            null
        ) {

            val journeyInsert =

                CloudJourneyInsert(

                    id =
                        parsedJourney.sessionId,

                    deviceId =
                        device.id,

                    startedAt =
                        instantString(
                            parsedJourney
                                .startedAtMs
                        ),

                    endedAt =
                        instantString(
                            parsedJourney
                                .endedAtMs
                        ),

                    pointCount =
                        parsedJourney
                            .points
                            .size,

                    status =
                        "incomplete"
                )


            client.postgrest[
                "journeys"
            ]
                .insert(
                    journeyInsert
                )


            Log.d(
                "SectionIQCloud",
                "Created cloud journey " +
                        parsedJourney.sessionId
            )


        } else {

            /*
             * Journey UUIDs must never move between devices.
             */
            if (
                existingJourney.deviceId !=
                device.id
            ) {

                error(
                    "Journey belongs to a different SectionIQ device"
                )
            }
        }


        /*
         * -----------------------------------------------------
         * GPS POINTS
         * -----------------------------------------------------
         */

        val existingPoints =

            client.postgrest[
                "journey_points"
            ]
                .select {

                    filter {

                        eq(
                            "journey_id",
                            parsedJourney.sessionId
                        )
                    }
                }
                .decodeList<
                        CloudJourneyPointRow
                        >()


        if (
            existingPoints.isEmpty()
        ) {

            val cloudPoints =

                parsedJourney
                    .points
                    .mapIndexed {
                            index,
                            point ->


                        CloudJourneyPointInsert(

                            journeyId =
                                parsedJourney
                                    .sessionId,

                            sequenceNumber =
                                index,

                            recordedAt =
                                instantString(
                                    point.timestampMs
                                ),

                            latitude =
                                point.latitude,

                            longitude =
                                point.longitude,

                            accuracyMetres =
                                point.accuracyMetres,

                            speedMetresPerSecond =
                                point
                                    .speedMetresPerSecond,

                            speedAvailable =
                                point.speedAvailable,

                            speedAccuracyMetresPerSecond =
                                point.speedAccuracyMetresPerSecond,

                            elapsedRealtimeNanos =
                                point.elapsedRealtimeNanos,

                            bearingAccuracyDegrees =
                                point.bearingAccuracyDegrees,

                            verticalAccuracyMetres =
                                point.verticalAccuracyMetres,

                            bearingDegrees =
                                point.bearingDegrees,

                            altitudeMetres =
                                point.altitudeMetres
                        )
                    }


            if (
                cloudPoints.isNotEmpty()
            ) {

                client.postgrest[
                    "journey_points"
                ]
                    .insert(
                        cloudPoints
                    )
            }


            Log.d(
                "SectionIQCloud",
                "Uploaded ${cloudPoints.size} GPS points"
            )


        } else {

            /*
             * Verify any previous partial/retried upload.
             */
            if (
                existingPoints.size !=
                parsedJourney.points.size
            ) {

                error(
                    "Cloud journey contains " +
                            "${existingPoints.size} of " +
                            "${parsedJourney.points.size} " +
                            "expected GPS points"
                )
            }


            val sequenceNumbers =

                existingPoints
                    .map {
                        it.sequenceNumber
                    }
                    .sorted()


            val expectedSequenceNumbers =

                parsedJourney
                    .points
                    .indices
                    .toList()


            if (
                sequenceNumbers !=
                expectedSequenceNumbers
            ) {

                error(
                    "Cloud journey GPS point sequence is incomplete"
                )
            }


            Log.d(
                "SectionIQCloud",
                "Cloud already contains " +
                        "${existingPoints.size} GPS points"
            )
        }


        /*
         * -----------------------------------------------------
         * MANAGER OBSERVATIONS
         * -----------------------------------------------------
         */

        if (
            parsedJourney
                .observations
                .isNotEmpty()
        ) {

            val existingObservations =

                client.postgrest[
                    "journey_observations"
                ]
                    .select {

                        filter {

                            eq(
                                "journey_id",
                                parsedJourney.sessionId
                            )
                        }
                    }
                    .decodeList<
                            CloudJourneyObservationRow
                            >()


            /*
             * A journey and all its observations must remain
             * associated with the same registered device.
             */
            existingObservations
                .forEach { observation ->

                    if (
                        observation.deviceId !=
                        device.id
                    ) {

                        error(
                            "Journey observation belongs to a different device"
                        )
                    }
                }


            val existingIds =

                existingObservations
                    .map {
                        it.id
                    }
                    .toSet()


            val missingObservations =

                parsedJourney
                    .observations
                    .filter {
                        it.id !in existingIds
                    }


            val cloudObservations =

                missingObservations
                    .map { observation ->

                        CloudJourneyObservationInsert(

                            id =
                                observation.id,

                            journeyId =
                                parsedJourney.sessionId,

                            deviceId =
                                device.id,

                            observedAt =
                                instantString(
                                    observation.timestampMs
                                ),

                            latitude =
                                observation.latitude,

                            longitude =
                                observation.longitude,

                            accuracyMetres =
                                observation.accuracyMetres,

                            source =
                                observation.source,

                            entryStatus =
                                observation.entryStatus,

                            eventKind =
                                observation.eventKind,

                            evidenceSource =
                                observation.evidenceSource
                        )
                    }


            if (
                cloudObservations.isNotEmpty()
            ) {

                client.postgrest[
                    "journey_observations"
                ]
                    .insert(
                        cloudObservations
                    )
            }


            /*
             * Read back before considering the journey fully
             * synced. This makes retries safe and prevents a
             * lost observation being hidden by an uploaded
             * marker on the local journey file.
             */
            val finalCloudObservations =

                client.postgrest[
                    "journey_observations"
                ]
                    .select {

                        filter {

                            eq(
                                "journey_id",
                                parsedJourney.sessionId
                            )
                        }
                    }
                    .decodeList<
                            CloudJourneyObservationRow
                            >()


            val expectedObservationIds =

                parsedJourney
                    .observations
                    .map {
                        it.id
                    }
                    .toSet()


            val finalObservationIds =

                finalCloudObservations
                    .map {
                        it.id
                    }
                    .toSet()


            if (
                !finalObservationIds.containsAll(
                    expectedObservationIds
                )
            ) {

                error(
                    "Not all journey observations reached the cloud"
                )
            }


            Log.d(
                "SectionIQCloud",
                "Journey observations verified: " +
                        "${expectedObservationIds.size}"
            )
        }

        /*
         * -----------------------------------------------------
         * PATHFINDER STATION MARKS
         * -----------------------------------------------------
         */

        if (
            parsedJourney
                .pathfinderMarks
                .isNotEmpty()
        ) {

            val existingMarks =

                client.postgrest[
                    "pathfinder_station_marks"
                ]
                    .select {

                        filter {

                            eq(
                                "journey_id",
                                parsedJourney.sessionId
                            )
                        }
                    }
                    .decodeList<
                            CloudPathfinderMarkRow
                            >()


            /*
             * Reject unexpected ownership.
             */
            existingMarks.forEach { mark ->

                if (
                    mark.deviceId !=
                    device.id
                ) {

                    error(
                        "Pathfinder mark belongs to a different device"
                    )
                }
            }


            val existingIds =

                existingMarks
                    .map {
                        it.id
                    }
                    .toSet()


            val missingMarks =

                parsedJourney
                    .pathfinderMarks
                    .filter {
                        it.id !in
                                existingIds
                    }


            val cloudMarks =

                missingMarks
                    .map { mark ->

                        CloudPathfinderMarkInsert(

                            id =
                                mark.id,

                            deviceId =
                                device.id,

                            journeyId =
                                parsedJourney
                                    .sessionId,

                            stationId =
                                mark.stationId,

                            recordedAt =
                                instantString(
                                    mark.timestampMs
                                ),

                            sequenceNumber =
                                mark.sequenceNumber,

                            latitude =
                                mark.latitude,

                            longitude =
                                mark.longitude,

                            accuracyMetres =
                                mark.accuracyMetres,

                            speedMetresPerSecond =
                                mark
                                    .speedMetresPerSecond,

                            trainPosition =
                                mark.trainPosition,

                            /*
                             * Android collectors can create
                             * candidates only.
                             */
                            confirmationStatus =
                                "candidate"
                        )
                    }


            if (
                cloudMarks.isNotEmpty()
            ) {

                client.postgrest[
                    "pathfinder_station_marks"
                ]
                    .insert(
                        cloudMarks
                    )
            }


            /*
             * Read back the journey's marks before considering
             * the journey upload complete.
             */
            val finalCloudMarks =

                client.postgrest[
                    "pathfinder_station_marks"
                ]
                    .select {

                        filter {

                            eq(
                                "journey_id",
                                parsedJourney.sessionId
                            )
                        }
                    }
                    .decodeList<
                            CloudPathfinderMarkRow
                            >()


            val expectedIds =

                parsedJourney
                    .pathfinderMarks
                    .map {
                        it.id
                    }
                    .toSet()


            val finalIds =

                finalCloudMarks
                    .map {
                        it.id
                    }
                    .toSet()


            if (
                !finalIds.containsAll(
                    expectedIds
                )
            ) {

                error(
                    "Not all Pathfinder station marks reached the cloud"
                )
            }


            Log.d(
                "SectionIQCloud",
                "Pathfinder marks verified: " +
                        "${expectedIds.size}"
            )
        }


        /*
         * -----------------------------------------------------
         * MARK JOURNEY COMPLETE
         * -----------------------------------------------------
         */

        client.postgrest[
            "journeys"
        ]
            .update(
                {

                    set(
                        "ended_at",
                        instantString(
                            parsedJourney
                                .endedAtMs
                        )
                    )

                    set(
                        "point_count",
                        parsedJourney
                            .points
                            .size
                    )

                    set(
                        "status",
                        "complete"
                    )
                }
            ) {

                filter {

                    eq(
                        "id",
                        parsedJourney.sessionId
                    )
                }
            }


        /*
         * Only now is the local file considered fully synced.
         */
        createUploadedMarker(
            file
        )


        Log.d(
            "SectionIQCloud",
            "Journey uploaded successfully: " +
                    parsedJourney.sessionId
        )


        return true
    }


    /*
     * ---------------------------------------------------------
     * UPLOAD ALL PENDING JOURNEYS
     * ---------------------------------------------------------
     */

    suspend fun uploadPendingJourneys(
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


        val recordingPreferences =

            context.getSharedPreferences(
                JourneyRecordingService
                    .PREFS_NAME,
                Context.MODE_PRIVATE
            )


        val currentlyRecording =

            recordingPreferences
                .getBoolean(
                    JourneyRecordingService
                        .KEY_RECORDING,
                    false
                )


        val currentSessionId =

            if (
                currentlyRecording
            ) {

                recordingPreferences
                    .getString(
                        JourneyRecordingService
                            .KEY_SESSION_ID,
                        null
                    )

            } else {

                null
            }


        val journeyFiles =

            directory
                .listFiles { file ->

                    file.isFile &&
                            file.name
                                .endsWith(
                                    ".jsonl"
                                ) &&
                            !hasUploadedMarker(
                                file
                            )
                }
                ?.sortedBy {
                    it.lastModified()
                }
                ?: emptyList()


        var uploadedCount =
            0


        for (
        file in journeyFiles
        ) {

            if (
                currentSessionId != null &&
                file.name ==
                "sectioniq_$currentSessionId.jsonl"
            ) {

                Log.d(
                    "SectionIQCloud",
                    "Skipping active journey: ${file.name}"
                )

                continue
            }


            try {

                uploadJourneyFile(
                    context =
                        context,
                    file =
                        file
                )


                uploadedCount++


            } catch (
                e: Exception
            ) {

                Log.e(
                    "SectionIQCloud",
                    "Pending journey upload failed: ${file.name}",
                    e
                )
            }
        }


        Log.d(
            "SectionIQCloud",
            "Pending journey sync complete: " +
                    "$uploadedCount uploaded"
        )


        return uploadedCount
    }


    /*
     * ---------------------------------------------------------
     * PARSE COMPLETED JOURNEY
     * ---------------------------------------------------------
     */

    private fun parseCompletedJourney(
        file: File
    ): ParsedJourney {

        require(
            file.exists()
        ) {

            "Journey file does not exist"
        }


        var sessionId:
                String? =
            null


        var startedAtMs:
                Long? =
            null


        var endedAtMs:
                Long? =
            null


        var recordedPointCount:
                Int? =
            null


        val points =

            mutableListOf<
                    ParsedJourneyPoint
                    >()


        val observations =

            mutableListOf<
                    ParsedJourneyObservation
                    >()


        val pathfinderMarks =

            mutableListOf<
                    ParsedPathfinderMark
                    >()

        file.forEachLine { line ->

            if (
                line.isBlank()
            ) {

                return@forEachLine
            }


            val json =

                JSONObject(
                    line
                )


            when (
                json.optString(
                    "record_type"
                )
            ) {

                /*
                 * ---------------------------------------------
                 * SESSION START
                 * ---------------------------------------------
                 */

                "session_start" -> {

                    sessionId =

                        json.getString(
                            "session_id"
                        )


                    startedAtMs =

                        json.getLong(
                            "started_at_ms"
                        )
                }


                /*
                 * ---------------------------------------------
                 * GPS POINT
                 * ---------------------------------------------
                 */

                "gps_point" -> {

                    val altitude =

                        optionalDouble(
                            json,
                            "altitude_m"
                        )


                    val accuracy =

                        optionalFloat(
                            json,
                            "accuracy_m"
                        )


                    val speed =

                        optionalFloat(
                            json,
                            "speed_mps"
                        )


                    val speedAvailable =

                        optionalBoolean(
                            json,
                            "speed_available"
                        )


                    val speedAccuracy =

                        optionalFloat(
                            json,
                            "speed_accuracy_mps"
                        )


                    val elapsedRealtimeNanos =

                        optionalLong(
                            json,
                            "elapsed_realtime_nanos"
                        )


                    val bearingAccuracy =

                        optionalFloat(
                            json,
                            "bearing_accuracy_deg"
                        )


                    val verticalAccuracy =

                        optionalFloat(
                            json,
                            "vertical_accuracy_m"
                        )


                    val bearing =

                        optionalFloat(
                            json,
                            "bearing_deg"
                        )


                    points +=

                        ParsedJourneyPoint(

                            timestampMs =
                                json.getLong(
                                    "timestamp_ms"
                                ),

                            latitude =
                                json.getDouble(
                                    "latitude"
                                ),

                            longitude =
                                json.getDouble(
                                    "longitude"
                                ),

                            accuracyMetres =
                                accuracy,

                            speedMetresPerSecond =
                                speed,

                            speedAvailable =
                                speedAvailable,

                            speedAccuracyMetresPerSecond =
                                speedAccuracy,

                            elapsedRealtimeNanos =
                                elapsedRealtimeNanos,

                            bearingAccuracyDegrees =
                                bearingAccuracy,

                            verticalAccuracyMetres =
                                verticalAccuracy,

                            bearingDegrees =
                                bearing,

                            altitudeMetres =
                                altitude
                        )
                }


                /*
                 * ---------------------------------------------
                 * MANAGER OBSERVATION MARK
                 * ---------------------------------------------
                 */

                "journey_observation_mark" -> {

                    observations +=

                        ParsedJourneyObservation(

                            id =
                                json.getString(
                                    "id"
                                ),

                            sessionId =
                                json.getString(
                                    "session_id"
                                ),

                            timestampMs =
                                json.getLong(
                                    "timestamp_ms"
                                ),

                            latitude =
                                optionalDouble(
                                    json,
                                    "latitude"
                                ),

                            longitude =
                                optionalDouble(
                                    json,
                                    "longitude"
                                ),

                            accuracyMetres =
                                optionalFloat(
                                    json,
                                    "accuracy_m"
                                ),

                            source =
                                json.optString(
                                    "source",
                                    "manager_collector"
                                ),

                            entryStatus =
                                json.optString(
                                    "entry_status",
                                    "marked"
                                ),

                            eventKind =
                                json.optString(
                                    "event_kind",
                                    "other"
                                ),

                            evidenceSource =
                                json.optString(
                                    "evidence_source",
                                    "direct_observation"
                                )
                        )
                }

                /*
                 * ---------------------------------------------
                 * PATHFINDER STATION MARK
                 * ---------------------------------------------
                 */

                "pathfinder_station_mark" -> {

                    val stationId =

                        optionalLong(
                            json,
                            "station_id"
                        )


                    val sequenceNumber =

                        optionalInt(
                            json,
                            "sequence_number"
                        )


                    pathfinderMarks +=

                        ParsedPathfinderMark(

                            id =
                                json.getString(
                                    "id"
                                ),

                            sessionId =
                                json.getString(
                                    "session_id"
                                ),

                            timestampMs =
                                json.getLong(
                                    "timestamp_ms"
                                ),

                            sequenceNumber =
                                sequenceNumber,

                            latitude =
                                json.getDouble(
                                    "latitude"
                                ),

                            longitude =
                                json.getDouble(
                                    "longitude"
                                ),

                            accuracyMetres =
                                optionalFloat(
                                    json,
                                    "accuracy_m"
                                ),

                            speedMetresPerSecond =
                                optionalFloat(
                                    json,
                                    "speed_mps"
                                ),

                            stationId =
                                stationId,

                            trainPosition =
                                json.optString(
                                    "train_position",
                                    "front"
                                )
                        )
                }


                /*
                 * ---------------------------------------------
                 * SESSION END
                 * ---------------------------------------------
                 */

                "session_end" -> {

                    endedAtMs =

                        json.getLong(
                            "ended_at_ms"
                        )


                    recordedPointCount =

                        json.getInt(
                            "point_count"
                        )
                }
            }
        }


        /*
         * -----------------------------------------------------
         * VALIDATION
         * -----------------------------------------------------
         */

        val finalSessionId =

            sessionId
                ?: error(
                    "Journey has no session_start record"
                )


        val finalStartedAt =

            startedAtMs
                ?: error(
                    "Journey has no start time"
                )


        val finalEndedAt =

            endedAtMs
                ?: error(
                    "Journey is not complete"
                )


        if (
            recordedPointCount != null &&
            recordedPointCount !=
            points.size
        ) {

            error(
                "Journey point count mismatch. " +
                        "File contains ${points.size}, " +
                        "session_end reports " +
                        "$recordedPointCount"
            )
        }


        /*
         * Pathfinder marks in this JSONL must belong to this
         * journey.
         */
        pathfinderMarks
            .forEach { mark ->

                if (
                    mark.sessionId !=
                    finalSessionId
                ) {

                    error(
                        "Pathfinder mark belongs to another session"
                    )
                }
            }


        /*
         * Mark UUIDs must be unique locally.
         */
        val markIds =

            pathfinderMarks
                .map {
                    it.id
                }


        if (
            markIds.distinct().size !=
            markIds.size
        ) {

            error(
                "Journey contains duplicate Pathfinder mark IDs"
            )
        }


        return ParsedJourney(

            sessionId =
                finalSessionId,

            startedAtMs =
                finalStartedAt,

            endedAtMs =
                finalEndedAt,

            points =
                points,

            observations =
                observations,
            pathfinderMarks =
                pathfinderMarks
        )
    }


    /*
     * ---------------------------------------------------------
     * JSON OPTIONAL HELPERS
     * ---------------------------------------------------------
     */

    private fun optionalBoolean(
        json: JSONObject,
        key: String
    ): Boolean? {

        return if (
            json.has(key) &&
            !json.isNull(key)
        ) {
            json.getBoolean(key)
        } else {
            null
        }
    }


    private fun optionalFloat(
        json: JSONObject,
        key: String
    ): Float? {

        return if (
            json.has(
                key
            ) &&
            !json.isNull(
                key
            )
        ) {

            json.getDouble(
                key
            ).toFloat()

        } else {

            null
        }
    }


    private fun optionalDouble(
        json: JSONObject,
        key: String
    ): Double? {

        return if (
            json.has(
                key
            ) &&
            !json.isNull(
                key
            )
        ) {

            json.getDouble(
                key
            )

        } else {

            null
        }
    }


    private fun optionalLong(
        json: JSONObject,
        key: String
    ): Long? {

        return if (
            json.has(
                key
            ) &&
            !json.isNull(
                key
            )
        ) {

            json.getLong(
                key
            )

        } else {

            null
        }
    }


    private fun optionalInt(
        json: JSONObject,
        key: String
    ): Int? {

        return if (
            json.has(
                key
            ) &&
            !json.isNull(
                key
            )
        ) {

            json.getInt(
                key
            )

        } else {

            null
        }
    }


    /*
     * ---------------------------------------------------------
     * UPLOAD MARKERS
     * ---------------------------------------------------------
     */

    fun hasUploadedMarker(
        file: File
    ): Boolean {

        return uploadedMarker(
            file
        ).exists()
    }


    private fun createUploadedMarker(
        file: File
    ) {

        uploadedMarker(
            file
        ).writeText(
            System.currentTimeMillis()
                .toString()
        )
    }


    private fun uploadedMarker(
        file: File
    ): File {

        return File(
            file.parentFile,
            "${file.name}.uploaded"
        )
    }


    /*
     * ---------------------------------------------------------
     * TIME
     * ---------------------------------------------------------
     */

    private fun instantString(
        epochMilliseconds: Long
    ): String {

        return Instant
            .ofEpochMilli(
                epochMilliseconds
            )
            .toString()
    }
}
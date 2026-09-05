package com.bodgejob.sectioniq

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class RegisteredDevice(

    val id: String,

    @SerialName("device_number")
    val deviceNumber: Long
)


@Serializable
data class PathfinderDeviceEntitlement(

    @SerialName("device_id")
    val deviceId: String,

    val enabled: Boolean
)


@Serializable
data class JourneyObservation(

    val id: String,

    @SerialName("journey_id")
    val journeyId: String,

    @SerialName("device_id")
    val deviceId: String,

    @SerialName("observed_at")
    val observedAt: String,

    val latitude: Double? = null,

    val longitude: Double? = null,

    @SerialName("accuracy_m")
    val accuracyMetres: Float? = null,

    @SerialName("free_text")
    val freeText: String? = null,

    @SerialName("entry_status")
    val entryStatus: String,

    @SerialName("event_kind")
    val eventKind: String,

    @SerialName("evidence_source")
    val evidenceSource: String,

    val source: String
)

object SectionIQSupabase {

    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    ) {

        install(Auth)
        install(Postgrest)
    }


    suspend fun ensureAuthenticated(): String {

        /*
         * Wait for any existing Supabase session stored on this
         * Android installation to be restored.
         */
        client.auth.awaitInitialization()


        if (
            client.auth.currentSessionOrNull() ==
            null
        ) {

            client.auth.signInAnonymously()
        }


        return client.auth
            .currentSessionOrNull()
            ?.user
            ?.id
            ?: error(
                "Supabase authentication failed"
            )
    }


    suspend fun registerDevice(): RegisteredDevice {

        /*
         * register_device() requires an authenticated user.
         */
        ensureAuthenticated()


        /*
         * PostgreSQL returns:
         *
         * id
         * device_number
         */
        val devices =

            client.postgrest
                .rpc(
                    "register_device"
                )
                .decodeList<RegisteredDevice>()


        return devices
            .singleOrNull()
            ?: error(
                "Supabase did not return a registered device"
            )
    }


    /*
     * ---------------------------------------------------------
     * PENDING MANAGER OBSERVATIONS
     * ---------------------------------------------------------
     */

    suspend fun completeObservation(
        observationId: String,
        eventKind: String,
        freeText: String
    ) {

        val device =
            registerDevice()


        client.postgrest[
            "journey_observations"
        ]
            .update(
                {

                    set(
                        "event_kind",
                        eventKind
                    )

                    set(
                        "free_text",
                        freeText
                            .trim()
                            .ifBlank {
                                null
                            }
                    )

                    set(
                        "entry_status",
                        "complete"
                    )

                    set(
                        "completed_at",
                        java.time.Instant
                            .now()
                            .toString()
                    )
                }
            ) {

                filter {

                    eq(
                        "id",
                        observationId
                    )

                    eq(
                        "device_id",
                        device.id
                    )

                    eq(
                        "entry_status",
                        "marked"
                    )
                }
            }
    }

    suspend fun getMarkedObservations():
        List<JourneyObservation> {

        val device =
            registerDevice()


        val rows =

            client.postgrest[
                "journey_observations"
            ]
                .select {

                    filter {

                        eq(
                            "device_id",
                            device.id
                        )

                        eq(
                            "entry_status",
                            "marked"
                        )
                    }
                }
                .decodeList<
                    JourneyObservation
                    >()


        return rows
            .sortedBy {
                it.observedAt
            }
    }

    /*
     * ---------------------------------------------------------
     * PATHFINDER ENTITLEMENT
     * ---------------------------------------------------------
     *
     * Pathfinder is controlled centrally through Supabase.
     *
     * A device that does not have an enabled row in
     * pathfinder_devices cannot use Pathfinder.
     */

    suspend fun isPathfinderEnabled(
        deviceId: String
    ): Boolean {

        ensureAuthenticated()


        val rows =

            client.postgrest[
                "pathfinder_devices"
            ]
                .select {

                    filter {

                        eq(
                            "device_id",
                            deviceId
                        )
                    }
                }
                .decodeList<
                    PathfinderDeviceEntitlement
                    >()


        return rows
            .firstOrNull()
            ?.enabled
            ?: false
    }
}
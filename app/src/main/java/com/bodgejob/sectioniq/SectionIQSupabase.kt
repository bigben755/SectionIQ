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
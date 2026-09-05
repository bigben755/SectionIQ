from pathlib import Path

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Expected anchor not found for {label}; refusing to modify files")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# MainActivity.kt
# -----------------------------------------------------------------------------
main_path = ROOT / 'app/src/main/java/com/bodgejob/sectioniq/MainActivity.kt'
main = main_path.read_text(encoding='utf-8')

ui_anchor = '''        /*
         * -----------------------------------------------------
         * START / STOP JOURNEY
         * -----------------------------------------------------
         */

        Button(
'''

ui_replacement = '''        /*
         * -----------------------------------------------------
         * HEADCODE / SIGNALLING ID
         * -----------------------------------------------------
         */

        if (
            !isRecording
        ) {

            OutlinedTextField(
                value =
                    headcode,

                onValueChange = { value ->

                    val cleaned =
                        value
                            .uppercase(
                                Locale.UK
                            )
                            .filter {
                                it.isLetterOrDigit()
                            }
                            .take(
                                4
                            )

                    headcode =
                        cleaned

                    sectionPreferences
                        .edit()
                        .putString(
                            "last_headcode",
                            cleaned
                        )
                        .apply()
                },

                label = {
                    Text(
                        "Headcode / signalling ID"
                    )
                },

                placeholder = {
                    Text(
                        "e.g. 1N52"
                    )
                },

                supportingText = {
                    Text(
                        if (
                            headcode.isEmpty() ||
                            headcodeValid
                        ) {
                            "Enter the four-character operational headcode"
                        } else {
                            "Use a format such as 1N52"
                        }
                    )
                },

                isError =
                    headcode.isNotEmpty() &&
                            !headcodeValid,

                singleLine =
                    true,

                modifier =
                    Modifier.fillMaxWidth()
            )


            Spacer(
                modifier =
                    Modifier.height(
                        18.dp
                    )
            )
        }


        /*
         * -----------------------------------------------------
         * START / STOP JOURNEY
         * -----------------------------------------------------
         */

        Button(
'''

if 'Headcode / signalling ID"' not in main[main.find('fun SectionIQHomeScreen'):]:
    main = replace_once(main, ui_anchor, ui_replacement, 'headcode input UI')

start_anchor = '''                if (
                    !isRecording
                ) {

                    /*
                     * Freeze the selected mode into this journey.
                     */
'''

start_replacement = '''                if (
                    !isRecording
                ) {

                    /*
                     * Freeze the tester-entered operational headcode into
                     * this journey before the recording service starts.
                     */
                    recordingPreferences
                        .edit()
                        .putString(
                            JourneyRecordingService
                                .KEY_HEADCODE,
                            headcode
                        )
                        .apply()


                    /*
                     * Freeze the selected mode into this journey.
                     */
'''

if '.KEY_HEADCODE,' not in main:
    main = replace_once(main, start_anchor, start_replacement, 'headcode freeze on start')

enabled_anchor = '''                } else {

                    gpsReady
                },
'''

enabled_replacement = '''                } else {

                    gpsReady &&
                            headcodeValid
                },
'''

if 'gpsReady &&\n                            headcodeValid' not in main:
    main = replace_once(main, enabled_anchor, enabled_replacement, 'start button headcode validation')

status_anchor = '''                    gpsReady ->

                        "Ready to record"
'''

status_replacement = '''                    gpsReady &&
                            !headcodeValid ->

                        "Enter a valid four-character headcode"

                    gpsReady ->

                        "Ready to record"
'''

if 'Enter a valid four-character headcode' not in main:
    main = replace_once(main, status_anchor, status_replacement, 'headcode readiness status')

function_anchor = '''fun createJourneyFile(
    context: Context,
    sessionId: String,
    deviceName: String
): File {
'''

function_replacement = '''fun createJourneyFile(
    context: Context,
    sessionId: String,
    deviceName: String,
    headcode: String?
): File {
'''

if 'deviceName: String,\n    headcode: String?' not in main:
    main = replace_once(main, function_anchor, function_replacement, 'createJourneyFile headcode parameter')

json_anchor = '''            put(
                "device_name",
                deviceName
            )

            put(
                "started_at_ms",
'''

json_replacement = '''            put(
                "device_name",
                deviceName
            )

            put(
                "entered_headcode",
                headcode
                    ?: JSONObject.NULL
            )

            put(
                "started_at_ms",
'''

if '"entered_headcode"' not in main:
    main = replace_once(main, json_anchor, json_replacement, 'session_start headcode JSON')

main_path.write_text(main, encoding='utf-8')


# -----------------------------------------------------------------------------
# JourneyRecordingService.kt
# -----------------------------------------------------------------------------
service_path = ROOT / 'app/src/main/java/com/bodgejob/sectioniq/JourneyRecordingService.kt'
service = service_path.read_text(encoding='utf-8')

key_anchor = '''        const val KEY_SESSION_ID =
            "session_id"

        const val KEY_POINT_COUNT =
'''

key_replacement = '''        const val KEY_SESSION_ID =
            "session_id"

        const val KEY_HEADCODE =
            "headcode"

        const val KEY_POINT_COUNT =
'''

if 'const val KEY_HEADCODE' not in service:
    service = replace_once(service, key_anchor, key_replacement, 'recording headcode preference key')

call_anchor = '''                createJourneyFile(
                    context = this,
                    sessionId = sessionId,
                    deviceName =
                        getDeviceName(
                            this
                        )
                )
'''

call_replacement = '''                createJourneyFile(
                    context = this,
                    sessionId = sessionId,
                    deviceName =
                        getDeviceName(
                            this
                        ),
                    headcode =
                        recordingPreferences
                            .getString(
                                KEY_HEADCODE,
                                null
                            )
                )
'''

if 'headcode =\n                        recordingPreferences' not in service:
    service = replace_once(service, call_anchor, call_replacement, 'pass headcode into journey file')

service_path.write_text(service, encoding='utf-8')


# -----------------------------------------------------------------------------
# JourneyUploader.kt
# -----------------------------------------------------------------------------
uploader_path = ROOT / 'app/src/main/java/com/bodgejob/sectioniq/JourneyUploader.kt'
uploader = uploader_path.read_text(encoding='utf-8')

insert_anchor = '''    @SerialName("point_count")
    val pointCount: Int,

    val status: String
)
'''

insert_replacement = '''    @SerialName("point_count")
    val pointCount: Int,

    @SerialName("entered_headcode")
    val enteredHeadcode: String?,

    val status: String
)
'''

if '@SerialName("entered_headcode")\n    val enteredHeadcode: String?,' not in uploader:
    uploader = replace_once(uploader, insert_anchor, insert_replacement, 'cloud journey insert headcode')

row_anchor = '''    @SerialName("point_count")
    val pointCount: Int,

    val status: String,

    @SerialName("uploaded_at")
'''

row_replacement = '''    @SerialName("point_count")
    val pointCount: Int,

    @SerialName("entered_headcode")
    val enteredHeadcode: String? = null,

    val status: String,

    @SerialName("uploaded_at")
'''

if '@SerialName("entered_headcode")\n    val enteredHeadcode: String? = null,' not in uploader:
    uploader = replace_once(uploader, row_anchor, row_replacement, 'cloud journey row headcode')

parsed_anchor = '''private data class ParsedJourney(

    val sessionId: String,

    val startedAtMs: Long,

    val endedAtMs: Long,
'''

parsed_replacement = '''private data class ParsedJourney(

    val sessionId: String,

    val startedAtMs: Long,

    val enteredHeadcode: String?,

    val endedAtMs: Long,
'''

if 'val enteredHeadcode: String?' not in uploader[uploader.find('private data class ParsedJourney'):uploader.find('private data class ParsedJourneyPoint')]:
    uploader = replace_once(uploader, parsed_anchor, parsed_replacement, 'parsed journey headcode')

insert_use_anchor = '''                    pointCount =
                        parsedJourney
                            .points
                            .size,

                    status =
                        "incomplete"
'''

insert_use_replacement = '''                    pointCount =
                        parsedJourney
                            .points
                            .size,

                    enteredHeadcode =
                        parsedJourney
                            .enteredHeadcode,

                    status =
                        "incomplete"
'''

if 'enteredHeadcode =\n                        parsedJourney\n                            .enteredHeadcode' not in uploader:
    uploader = replace_once(uploader, insert_use_anchor, insert_use_replacement, 'upload entered headcode')

parse_var_anchor = '''        var startedAtMs:
                Long? =
            null


        var endedAtMs:
'''

parse_var_replacement = '''        var startedAtMs:
                Long? =
            null


        var enteredHeadcode:
                String? =
            null


        var endedAtMs:
'''

if 'var enteredHeadcode:' not in uploader:
    uploader = replace_once(uploader, parse_var_anchor, parse_var_replacement, 'parser headcode variable')

session_anchor = '''                    startedAtMs =

                        json.getLong(
                            "started_at_ms"
                        )
                }
'''

session_replacement = '''                    startedAtMs =

                        json.getLong(
                            "started_at_ms"
                        )


                    enteredHeadcode =

                        if (
                            json.has(
                                "entered_headcode"
                            ) &&
                            !json.isNull(
                                "entered_headcode"
                            )
                        ) {

                            json.getString(
                                "entered_headcode"
                            )
                                .trim()
                                .uppercase()
                                .takeIf {
                                    it.matches(
                                        Regex(
                                            "^[0-9][A-Z][0-9]{2}$"
                                        )
                                    )
                                }

                        } else {

                            null
                        }
                }
'''

if 'json.has(\n                                "entered_headcode"' not in uploader:
    uploader = replace_once(uploader, session_anchor, session_replacement, 'parse session headcode')

return_anchor = '''            startedAtMs =
                finalStartedAt,

            endedAtMs =
                finalEndedAt,
'''

return_replacement = '''            startedAtMs =
                finalStartedAt,

            enteredHeadcode =
                enteredHeadcode,

            endedAtMs =
                finalEndedAt,
'''

if 'enteredHeadcode =\n                enteredHeadcode' not in uploader:
    uploader = replace_once(uploader, return_anchor, return_replacement, 'return parsed headcode')

uploader_path.write_text(uploader, encoding='utf-8')

print('Headcode capture, local persistence and cloud upload patch applied.')

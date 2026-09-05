from pathlib import Path

path = Path("app/src/main/java/com/bodgejob/sectioniq/MainActivity.kt")
text = path.read_text(encoding="utf-8")

if "var headcode by remember" in text:
    print("Headcode state already present; no change needed.")
    raise SystemExit(0)

anchor = '''    /*
     * ---------------------------------------------------------
     * DEVICE
     * ---------------------------------------------------------
     */

    var deviceName by remember {
'''

replacement = '''    /*
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
'''

if anchor not in text:
    raise SystemExit("Expected DEVICE anchor not found; refusing to modify MainActivity.kt")

updated = text.replace(anchor, replacement, 1)
path.write_text(updated, encoding="utf-8")
print("Inserted headcode state and validation into MainActivity.kt")

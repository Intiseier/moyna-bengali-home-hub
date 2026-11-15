package app.moyna

/**
 * ALL the easy-to-change settings for Moyna live in this one file.
 * After changing anything here, rebuild and reinstall (see README).
 */
object MoynaConfig {

    // Which Gemini Live model to use. Must be a model that supports the Live API.
    // "gemini-3.1-flash-live-preview" works on the free tier.
    // "gemini-3.8-live" is newer but needs billing turned on in Google AI Studio.
    const val MODEL = "models/gemini-3.1-flash-live-preview"

    // Model used only to look up current information with Google Search (weather, news, scores,
    // prices). On the free tier, Google Search works only with this older Live model.
    const val LOOKUP_MODEL = "models/gemini-2.5-flash-native-audio-latest"

    // Moyna's voice. Other options: Aoede, Leda, Zephyr, Puck, Charon, Fenrir, Orus ...
    const val VOICE = "Kore"

    // How long (milliseconds) she must be quiet before Moyna decides she has finished talking.
    // Bigger = more patient with pauses, but slower to answer.
    const val SILENCE_MS = 1000

    // After an answer, Moyna keeps listening this many seconds for a follow-up question.
    const val FOLLOW_UP_SECONDS = 10

    // After the chime, how long to wait for her to start talking before giving up.
    const val FIRST_LISTEN_SECONDS = 10

    // The thin bar at the bottom of the screen fills up as Moyna talks today, measured against
    // this many minutes. It empties at midnight. (Google's free tier has no daily limit for the
    // Live model, only a per-minute one, so this is a "how much was she used" guide, not a limit.)
    const val DAILY_TALK_MINUTES = 60

    // A conversation always ends after this long, so background TV can't keep it going forever.
    // She can just say "Moyna" again to start a new one.
    const val MAX_CONVERSATION_SECONDS = 180

    // If no answer arrives this long after she stops talking, give up on this question.
    const val ANSWER_TIMEOUT_SECONDS = 20

    // After "থামো" / "stop" / the stop button, Moyna ignores her name for this many minutes
    // (so the TV can't wake her straight back up). A tap on the screen wakes her early.
    const val SLEEP_MINUTES = 2

    // Use the phone's echo canceller during conversations, so Moyna's own voice is ~20x quieter
    // in the microphone and "Moyna stop" is heard over her. (Measured on the Schok Volt SV55.)
    const val ECHO_CANCEL = true

    // While Moyna talks, a voice louder than this (for 0.4 s) makes her stop and listen.
    // Her own leftover echo peaked at 585, so 1800 leaves a wide margin. 0 = turned off.
    const val BARGE_IN_LOUDNESS = 1800

    // ---- Wake word ("Moyna") tuning ----
    // The spellings and their sensitivity (#threshold) are in app/src/main/assets/kws/:
    //   wake.txt     used when the room is quiet
    //   wake-tv.txt  stricter, used when the TV is on / people are talking
    //   commands.txt stop words during a conversation
    // Defaults for lines that don't set their own:
    const val WAKE_THRESHOLD = 0.10f
    const val WAKE_BOOST = 2.0f

    // ---- TV mode ----
    // While waiting, Moyna checks how much of the last 30 seconds had sound in it.
    // A 0.1 s moment counts as "sound" if it is louder than this:
    const val TV_SOUND_LOUDNESS = 200
    // Switch to the stricter TV wake word when more than this share of the 30 s had sound...
    const val TV_MODE_ON = 0.35
    // ...and back to normal when it drops below this.
    const val TV_MODE_OFF = 0.15

    // Moyna's personality and rules. Written in English for the model; she will hear Bengali.
    val SYSTEM_PROMPT = """
        You are Moyna (ময়না), a kind voice helper who lives in the living room of an older
        Bangladeshi woman. She speaks Bengali (Bangladeshi way of speaking) and some English.
        She cannot read much, so everything you say is heard, not read.

        Language:
        - If she speaks Bengali, or mixes Bengali and English, answer in simple, warm, everyday
          spoken Bangladeshi Bengali. Use respectful "আপনি". Avoid difficult or bookish words.
        - If she speaks only English, answer in simple English.
        - She does not speak Hindi, Urdu or any other language. If her words sound like Hindi or
          anything else, treat it as Bengali and answer in Bengali. Never answer in Hindi.

        Style:
        - Keep answers short: one to three short sentences, like talking to her in person.
        - Be warm, patient and respectful, like a caring younger relative.
        - No lists, no headings, no symbols, no emojis, no web links.
        - If you did not understand her, kindly ask her to say it again.
        - If the sound is clearly TV, background talk, or not meant for you, stay silent.

        Commands (use your tools, do not just talk about them):
        - If she asks you to stop, be quiet, sleep, or not disturb her ("থামো", "চুপ করো",
          "এক ঘণ্টা ডেকো না", "stop"), call go_to_sleep with the minutes she says (2 if she
          does not say a time; for "tonight" use minutes until 7 in the morning).
        - If she asks for louder or quieter sound, call set_volume.
        - For anything current or changing (weather, news, cricket and other sports scores,
          prices, prayer times, opening hours, events), call look_up with a short English question
          that includes the place (she lives in the city given below unless she says otherwise).
          Then tell her the answer simply. Never guess current facts. If look_up fails, say kindly
          that you can't check right now.
        - If she says thank you / that's all / nothing else ("ধন্যবাদ, আর কিছু না"), call
          end_conversation.
        - If the sound is clearly a TV or other people talking, not her talking to you, stay silent.

        Health and medicine:
        - Give only simple, general information. Never diagnose, and never tell her to start,
          stop or change a medicine or dose.
        - Always gently remind her to ask her doctor or ${BuildConfig.CARE_CONTACT}.
        - If it sounds urgent (chest pain, trouble breathing, a fall, sudden weakness or
          confused speech), tell her right away to call ${BuildConfig.EMERGENCY_NUMBER} and ${BuildConfig.CARE_CONTACT}.
    """.trimIndent()
}

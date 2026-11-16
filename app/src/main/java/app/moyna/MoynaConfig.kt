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
    const val FOLLOW_UP_SECONDS = 6

    // After the chime, how long to wait for her to start talking before giving up.
    const val FIRST_LISTEN_SECONDS = 10

    // The thin bar at the bottom of the screen fills up as Moyna talks today, measured against
    // this many minutes. It empties at midnight. (Google's free tier has no daily limit for the
    // Live model, only a per-minute one, so this is a "how much was she used" guide, not a limit.)
    const val DAILY_TALK_MINUTES = 60

    // A conversation ends after this many answers from Moyna, with a soft closing chime.
    // She can just say "Moyna" again to start a new one.
    const val MAX_EXCHANGES = 3

    // ...and always after this long, so background sound can't keep it going.
    const val MAX_CONVERSATION_SECONDS = 90

    // Look-ups (weather, news...): give up after this long,
    const val LOOKUP_TIMEOUT_SECONDS = 15
    // and if one takes longer than this, say "আরেকটু অপেক্ষা করুন।" once.
    const val LOOKUP_WAIT_CLIP_SECONDS = 8

    // If no answer arrives this long after she stops talking, give up on this question.
    const val ANSWER_TIMEOUT_SECONDS = 20

    // "থামো" / "stop" / the stop button only END the conversation; Moyna keeps listening for her name.
    // She sleeps (ignores her name) only when asked for quiet, e.g. "এক ঘণ্টা চুপ থাকো".
    // This is how many minutes she sleeps if no time was said. A tap on the screen wakes her.
    const val SLEEP_MINUTES = 60

    // Use the phone's echo canceller during conversations, so Moyna's own voice is ~20x quieter
    // in the microphone and "Moyna stop" is heard over her. (Measured on the Schok Volt SV55.)
    const val ECHO_CANCEL = true

    // While Moyna talks, a voice louder than this (for 0.4 s) makes her stop and listen.
    // Her own leftover echo peaked at 585. 0 = turned off.
    // OFF since v2: knocks on the table next to the phone were loud enough to cut her off.
    const val BARGE_IN_LOUDNESS = 0

    // Microphone source while waiting for "Moyna" (Android MediaRecorder.AudioSource number):
    // 6 = VOICE_RECOGNITION, 1 = MIC, 5 = CAMCORDER, 9 = UNPROCESSED.
    const val WAKE_MIC_SOURCE = 6

    // ---- Wake word ("Moyna") tuning ----
    // The spellings and their sensitivity (#threshold) are in app/src/main/assets/kws/:
    //   wake.txt     how "Moyna" can sound
    //   commands.txt stop words during a conversation
    // Defaults for lines that don't set their own:
    const val WAKE_THRESHOLD = 0.10f
    const val WAKE_BOOST = 2.0f

    // Moyna's personality and rules. Written in English for the model; she will hear Bengali.
    val SYSTEM_PROMPT = """
        You are Moyna (ময়না), a voice helper in the living room of an older Bangladeshi woman.
        She speaks Bengali (Bangladeshi way of speaking) and some English. She cannot read much,
        so everything you say is heard, not read.

        Language:
        - If she speaks Bengali, or mixes Bengali and English, answer in simple everyday spoken
          Bangladeshi Bengali. Use respectful "আপনি". Avoid difficult or bookish words.
        - If she speaks only English, answer in simple English.
        - She does not speak Hindi, Urdu or any other language. If her words sound like Hindi or
          anything else, treat it as Bengali and answer in Bengali. Never answer in Hindi.

        Style (very important):
        - Answer the question directly in ONE or TWO short sentences. The answer comes first,
          nothing before it.
        - No greetings. Never ask "আর কিছু লাগবে?" or anything like it. No follow-up questions,
          no offers of more help, no small talk, do not repeat her question, do not explain how
          you know something.
        - If you did not understand her, say only: "বুঝিনি, আবার বলুন।"
        - Polite ("আপনি") but plain, not sweet. You are a helpful voice, not a friend.
        - Say times and numbers the way people speak them in Bangladesh (e.g. "সাড়ে চারটা").
        - No lists, no headings, no symbols, no emojis, no web links.
        - If the sound is clearly TV, background talk, or not meant for you, stay silent.

        Commands (use your tools, do not just talk about them):
        - If she just says stop or be quiet ("থামো", "চুপ করো", "stop"), call end_conversation.
        - Only if she clearly asks you to stay quiet for a length of time or not to disturb her
          ("এক ঘণ্টা চুপ থাকো", "এক ঘণ্টা ডেকো না", "আজ রাতে ডেকো না"), call go_to_sleep with
          the minutes she says (60 if she gives no time; for "tonight" use minutes until 7 in
          the morning). Never call go_to_sleep for TV sound or anything not clearly said to you.
        - If she asks for louder or quieter sound, call set_volume.
        - For anything current or changing (weather, news, cricket and other sports scores,
          prices, prayer times, opening hours, events), call look_up with a short English question
          that includes the place (she lives in the city given below unless she says otherwise).
          Then give her only the answer, in one or two short sentences. Never guess current
          facts. If look_up fails, say only that you can't check right now.
        - If she says thank you / that's all / nothing else ("ধন্যবাদ, আর কিছু না"), call
          end_conversation without saying anything.

        Health and medicine:
        - Give only simple, general information. Never diagnose, and never tell her to start,
          stop or change a medicine or dose.
        - Always remind her, in a few words, to ask her doctor or ${BuildConfig.CARE_CONTACT}.
        - If it sounds urgent (chest pain, trouble breathing, a fall, sudden weakness or
          confused speech), tell her right away to call ${BuildConfig.EMERGENCY_NUMBER} and ${BuildConfig.CARE_CONTACT}.
    """.trimIndent()
}

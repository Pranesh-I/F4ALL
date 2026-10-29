"""What each notification says, in the four languages the app ships.

A push notification is shown by the phone's operating system, so its text has
to come from the server — unlike every other screen, where the app translates
a code. These templates are written to say what happened and what, if
anything, the athlete should do next, in one line each.

A flagged result is described as "being reviewed", never as what was flagged:
explaining a cheat check to the person it checks is how it stops working.

Test names match the app's own translations; ``tests/test_notifications.py``
reads the app's string files to keep them in step.
"""

# A translation table: each message stays on one line so translators can read it whole.
# The multiplication sign in the shuttle run's name is deliberate: it matches the app.
# ruff: noqa: E501, RUF001

from __future__ import annotations

from dataclasses import dataclass

LANGUAGES = ("en", "hi", "ta", "bn")


class Event:
    SESSION_OPENED = "session_opened"
    SESSION_ENDING_SOON = "session_ending_soon"
    SUBMISSION_RECEIVED = "submission_received"
    VERIFICATION_COMPLETED = "verification_completed"
    SUBMISSION_FLAGGED = "submission_flagged"
    SUBMISSION_APPROVED = "submission_approved"
    SUBMISSION_REJECTED = "submission_rejected"
    RESUBMISSION_REQUESTED = "resubmission_requested"

    ALL = (
        SESSION_OPENED,
        SESSION_ENDING_SOON,
        SUBMISSION_RECEIVED,
        VERIFICATION_COMPLETED,
        SUBMISSION_FLAGGED,
        SUBMISSION_APPROVED,
        SUBMISSION_REJECTED,
        RESUBMISSION_REQUESTED,
    )


TEST_NAMES: dict[str, dict[str, str]] = {
    "en": {
        "SQUATS": "Squats",
        "PUSH_UPS": "Push-ups",
        "BICEP_CURLS": "Bicep curls",
        "LUNGES": "Lunges",
        "SIT_UPS": "Sit-ups",
        "VERTICAL_JUMP": "Vertical jump",
        "SHUTTLE_RUN": "Shuttle run (4 × 10 m)",
        "ENDURANCE_RUN": "Endurance run (600 m)",
    },
    "hi": {
        "SQUATS": "स्क्वैट",
        "PUSH_UPS": "पुश-अप",
        "BICEP_CURLS": "बाइसेप कर्ल",
        "LUNGES": "लंज",
        "SIT_UPS": "सिट-अप",
        "VERTICAL_JUMP": "ऊँची छलांग",
        "SHUTTLE_RUN": "शटल दौड़ (4 × 10 मी)",
        "ENDURANCE_RUN": "सहनशक्ति दौड़ (600 मी)",
    },
    "ta": {
        "SQUATS": "ஸ்குவாட்",
        "PUSH_UPS": "புஷ்-அப்",
        "BICEP_CURLS": "பைசெப் கர்ல்",
        "LUNGES": "லஞ்ச்",
        "SIT_UPS": "சிட்-அப்",
        "VERTICAL_JUMP": "செங்குத்துத் தாவல்",
        "SHUTTLE_RUN": "ஷட்டில் ஓட்டம் (4 × 10 மீ)",
        "ENDURANCE_RUN": "நீடித்த ஓட்டம் (600 மீ)",
    },
    "bn": {
        "SQUATS": "স্কোয়াট",
        "PUSH_UPS": "পুশ-আপ",
        "BICEP_CURLS": "বাইসেপ কার্ল",
        "LUNGES": "লাঞ্জ",
        "SIT_UPS": "সিট-আপ",
        "VERTICAL_JUMP": "উলম্ব লাফ",
        "SHUTTLE_RUN": "শাটল দৌড় (4 × 10 মি)",
        "ENDURANCE_RUN": "সহনশীলতা দৌড় (600 মি)",
    },
}

# (title, body) per event and language. {test} is a translated test name and
# {session} the session's name as SAI wrote it.
TEMPLATES: dict[str, dict[str, tuple[str, str]]] = {
    Event.SESSION_OPENED: {
        "en": (
            "Session open: {session}",
            "Your official tests are open now. Record them before the session closes.",
        ),
        "hi": (
            "सत्र खुला: {session}",
            "आपके आधिकारिक टेस्ट अब खुले हैं। सत्र बंद होने से पहले रिकॉर्ड करें।",
        ),
        "ta": (
            "அமர்வு திறந்தது: {session}",
            "உங்கள் அதிகாரப்பூர்வ சோதனைகள் இப்போது திறந்துள்ளன. அமர்வு முடியும் முன் பதிவு செய்யவும்.",
        ),
        "bn": (
            "সেশন খোলা: {session}",
            "আপনার অফিসিয়াল টেস্ট এখন খোলা। সেশন বন্ধ হওয়ার আগে রেকর্ড করুন।",
        ),
    },
    Event.SESSION_ENDING_SOON: {
        "en": (
            "Closing soon: {session}",
            "You still have tests to record in this session. It closes soon.",
        ),
        "hi": (
            "जल्द बंद होगा: {session}",
            "इस सत्र में आपके कुछ टेस्ट अभी बाकी हैं। यह जल्द बंद होगा।",
        ),
        "ta": (
            "விரைவில் முடிகிறது: {session}",
            "இந்த அமர்வில் நீங்கள் பதிவு செய்ய வேண்டிய சோதனைகள் இன்னும் உள்ளன. விரைவில் முடிகிறது.",
        ),
        "bn": (
            "শীঘ্রই বন্ধ হবে: {session}",
            "এই সেশনে আপনার কিছু টেস্ট এখনও বাকি। এটি শীঘ্রই বন্ধ হবে।",
        ),
    },
    Event.SUBMISSION_RECEIVED: {
        "en": ("{test}: received", "SAI has your test and is checking the video."),
        "hi": ("{test}: मिल गया", "आपका टेस्ट SAI को मिल गया है और वीडियो की जाँच हो रही है।"),
        "ta": (
            "{test}: கிடைத்தது",
            "உங்கள் சோதனை SAI-க்குக் கிடைத்தது; வீடியோ சரிபார்க்கப்படுகிறது.",
        ),
        "bn": ("{test}: পাওয়া গেছে", "আপনার টেস্ট SAI পেয়েছে এবং ভিডিও যাচাই করছে।"),
    },
    Event.VERIFICATION_COMPLETED: {
        "en": (
            "{test}: checked by SAI",
            "Your score has been checked. An official will approve it next.",
        ),
        "hi": (
            "{test}: SAI ने जाँच की",
            "आपके स्कोर की जाँच हो गई है। अब एक अधिकारी इसे मंज़ूर करेंगे।",
        ),
        "ta": (
            "{test}: SAI சரிபார்த்தது",
            "உங்கள் மதிப்பெண் சரிபார்க்கப்பட்டது. அடுத்து ஒரு அதிகாரி ஒப்புதல் அளிப்பார்.",
        ),
        "bn": (
            "{test}: SAI যাচাই করেছে",
            "আপনার স্কোর যাচাই হয়েছে। এরপর একজন আধিকারিক অনুমোদন করবেন।",
        ),
    },
    Event.SUBMISSION_FLAGGED: {
        "en": (
            "{test}: being reviewed",
            "An SAI official will look at your test. We will tell you when it is decided.",
        ),
        "hi": (
            "{test}: समीक्षा हो रही है",
            "एक SAI अधिकारी आपका टेस्ट देखेंगे। निर्णय होने पर हम आपको बताएँगे।",
        ),
        "ta": (
            "{test}: மதிப்பாய்வில் உள்ளது",
            "ஒரு SAI அதிகாரி உங்கள் சோதனையைப் பார்ப்பார். முடிவானதும் தெரிவிப்போம்.",
        ),
        "bn": (
            "{test}: পর্যালোচনা চলছে",
            "একজন SAI আধিকারিক আপনার টেস্ট দেখবেন। সিদ্ধান্ত হলে আমরা জানাব।",
        ),
    },
    Event.SUBMISSION_APPROVED: {
        "en": (
            "{test}: approved",
            "Your result is now official. Open the app to see it.",
        ),
        "hi": ("{test}: मंज़ूर", "आपका परिणाम अब आधिकारिक है। देखने के लिए ऐप खोलें।"),
        "ta": (
            "{test}: ஒப்புதல் பெற்றது",
            "உங்கள் முடிவு இப்போது அதிகாரப்பூர்வமானது. பார்க்க ஆப்பைத் திறக்கவும்.",
        ),
        "bn": ("{test}: অনুমোদিত", "আপনার ফলাফল এখন অফিসিয়াল। দেখতে অ্যাপটি খুলুন।"),
    },
    Event.SUBMISSION_REJECTED: {
        "en": (
            "{test}: not accepted",
            "This result was not accepted. Open the app to see why.",
        ),
        "hi": (
            "{test}: स्वीकार नहीं",
            "यह परिणाम स्वीकार नहीं किया गया। कारण देखने के लिए ऐप खोलें।",
        ),
        "ta": (
            "{test}: ஏற்கப்படவில்லை",
            "இந்த முடிவு ஏற்கப்படவில்லை. காரணத்தைப் பார்க்க ஆப்பைத் திறக்கவும்.",
        ),
        "bn": ("{test}: গ্রহণ করা হয়নি", "এই ফলাফল গ্রহণ করা হয়নি। কারণ দেখতে অ্যাপটি খুলুন।"),
    },
    Event.RESUBMISSION_REQUESTED: {
        "en": (
            "{test}: please record again",
            "An SAI official asked you to record this test again.",
        ),
        "hi": (
            "{test}: फिर से रिकॉर्ड करें",
            "एक SAI अधिकारी ने यह टेस्ट फिर से रिकॉर्ड करने को कहा है।",
        ),
        "ta": (
            "{test}: மீண்டும் பதிவு செய்யவும்",
            "இந்தச் சோதனையை மீண்டும் பதிவு செய்ய ஒரு SAI அதிகாரி கேட்டுள்ளார்.",
        ),
        "bn": (
            "{test}: আবার রেকর্ড করুন",
            "একজন SAI আধিকারিক এই টেস্টটি আবার রেকর্ড করতে বলেছেন।",
        ),
    },
}


@dataclass(frozen=True)
class Message:
    title: str
    body: str
    language: str


def render(
    event: str,
    language: str | None,
    *,
    test_code: str | None = None,
    session: str | None = None,
) -> Message:
    """The notification text for ``event`` in ``language``, English if unsupported."""
    language = language if language in LANGUAGES else "en"
    title, body = TEMPLATES[event][language]
    test = TEST_NAMES[language].get(test_code or "", test_code or "")
    values = {"test": test, "session": session or ""}
    return Message(
        title=title.format(**values), body=body.format(**values), language=language
    )

package com.sai.sports.gesture

import com.sai.sports.analyzer.TestType
import kotlin.random.Random

/**
 * A pose the athlete makes to prove they are present, in frame and ready
 * before the recording starts.
 *
 * [facingCamera] gestures name a side. They only make sense when the athlete
 * faces the phone, and they double as a check that left and right have not
 * been swapped anywhere between the camera and the pose model — a mirrored
 * pipeline fails "raise your LEFT hand" every time.
 */
enum class Gesture(val facingCamera: Boolean) {
    /** Either hand above the head. Works side-on, where the far arm may be hidden. */
    RAISE_HAND(facingCamera = false),
    RAISE_LEFT_HAND(facingCamera = true),
    RAISE_RIGHT_HAND(facingCamera = true),
    BOTH_HANDS_UP(facingCamera = true)
}

/**
 * Which gestures each test uses.
 *
 * Side-on tests can only ask for "a hand up": seen from the side, the far arm
 * is behind the body and naming it would fail honest athletes. Curls are
 * filmed facing the camera, so they get a gesture picked at random from
 * several — an athlete replaying a recorded video cannot know in advance which
 * one will be asked for.
 */
object GesturePlan {

    fun choicesFor(testType: TestType): List<Gesture> = when (testType) {
        TestType.BICEP_CURLS -> listOf(Gesture.RAISE_LEFT_HAND, Gesture.RAISE_RIGHT_HAND, Gesture.BOTH_HANDS_UP)
        TestType.SQUATS,
        TestType.PUSH_UPS,
        TestType.LUNGES,
        TestType.VERTICAL_JUMP,
        TestType.SIT_UPS -> listOf(Gesture.RAISE_HAND)
    }

    fun pick(testType: TestType, random: Random = Random.Default): Gesture =
        choicesFor(testType).random(random)
}

package com.smartcampus.door.core.state

/** One thing to do, short enough to read while moving. */
data class EmergencyAction(val title: String, val detail: String)

/** The order to follow (the big words) and the steps of one kind of emergency. */
data class EmergencyPresentation(val headline: String, val steps: List<EmergencyAction>)

/**
 * The order and the three steps of each kind of emergency, the same words as the Faculty screens show (`emergencyPresentation` in
 * their local contract). This is what people read while moving, so it is fixed here and not taken from free text. A door unit does
 * not know the site's assembly point, so the step says to go to it without naming it. An unknown kind from a newer server
 * gets the evacuation, which is still the safe thing to say.
 */
fun emergencyPresentation(kind: String): EmergencyPresentation = when (kind) {
    "biohazard" -> EmergencyPresentation(
        "STAY INSIDE",
        listOf(
            EmergencyAction("Close doors and windows", "Seal the room if you can."),
            EmergencyAction("Stay where you are", "Do not leave until told to."),
            EmergencyAction("Touch nothing suspicious", "Wait for staff instructions."),
        ),
    )
    "nuclear" -> EmergencyPresentation(
        "SHELTER NOW",
        listOf(
            EmergencyAction("Go inside, stay inside", "Move away from outer walls."),
            EmergencyAction("Close doors and windows", "Switch off ventilation."),
            EmergencyAction("Wait for instructions", "Follow official guidance only."),
        ),
    )
    "bomb" -> EmergencyPresentation(
        "LEAVE CALMLY",
        listOf(
            EmergencyAction("Use the nearest exit", "Do not run. Help others."),
            EmergencyAction("Move well away", "Keep clear of the building."),
            EmergencyAction("Touch nothing unknown", "No phones near the building."),
        ),
    )
    "seismic" -> EmergencyPresentation(
        "DROP, COVER, HOLD",
        listOf(
            EmergencyAction("Take cover", "Under a sturdy table, away from windows."),
            EmergencyAction("Do not run outside", "Wait until the shaking stops."),
            EmergencyAction("Then evacuate", "Go to the assembly point."),
        ),
    )
    "power" -> EmergencyPresentation(
        "STAY CALM",
        listOf(
            EmergencyAction("Emergency lights on", "Stay where you are."),
            EmergencyAction("Do not use lifts", "Use the stairs if you must move."),
            EmergencyAction("Follow staff", "Wait for instructions."),
        ),
    )
    else -> EmergencyPresentation(
        "EVACUATE NOW",
        listOf(
            EmergencyAction("Use the stairs", "Do not use elevators."),
            EmergencyAction("Leave belongings", "Go to the nearest exit now."),
            EmergencyAction("Assemble outside", "Go to the assembly point."),
        ),
    )
}

/** Kinds where people stay inside and wait; for the others they leave and are told not to go back in. */
private val STAY_INSIDE = setOf("biohazard", "nuclear", "power")

/**
 * The five lines of the alert, always five and each short enough for one line, the same as the Faculty screens: the three steps
 * of the kind, what to do until it is over, and a closing line (a plain one for a test, so nobody acts on it).
 */
fun emergencySteps(kind: String, testMode: Boolean): List<String> {
    val untilOver = if (kind in STAY_INSIDE) "Wait for staff to give the all clear." else "Do not re-enter until staff allow it."
    val closing = if (testMode) "This is a test. No action is needed." else "Follow staff. Help others if it is safe."
    return emergencyPresentation(kind).steps.map { "${it.title}. ${it.detail}" } + untilOver + closing
}

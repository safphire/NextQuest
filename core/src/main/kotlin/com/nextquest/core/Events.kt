package com.nextquest.core

/** EventLog entry types. The EventLog is the source of truth for all stats. */
enum class EventType {
    CREATED,
    STEP_DONE,
    SUBQUEST_DONE,
    QUEST_DONE,
    TRACKED,
    UNTRACKED,
}

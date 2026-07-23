package com.nextquest.core

/**
 * Minimal quest shape the next-step invariant needs. The app layer maps its
 * Room entities into this; core never sees the database.
 */
data class QuestNode(
    val id: String,
    val title: String,
    val nextStep: String? = null,
    val done: Boolean = false,
    val focusedChildId: String? = null,
    val children: List<QuestNode> = emptyList(),
)

sealed interface NextStep {
    /** The quest id the step belongs to — the root itself or one subquest. */
    val questId: String

    data class Defined(override val questId: String, val text: String) : NextStep
    /** Amber state: this quest needs its next step defined before it's real. */
    data class Undefined(override val questId: String) : NextStep
}

object NextSteps {
    /**
     * The next-step invariant: every tracked quest surfaces exactly one
     * current next step, regardless of hierarchy. With incomplete subquests
     * the step belongs to the focused child (defaulting to the first
     * incomplete one); otherwise to the root itself.
     */
    fun resolve(root: QuestNode): NextStep {
        val target = focusTarget(root)
        val text = target.nextStep?.trim().takeUnless { it.isNullOrEmpty() }
        return if (text != null) NextStep.Defined(target.id, text) else NextStep.Undefined(target.id)
    }

    /** The node whose next step is current: focused incomplete child, first incomplete child, or the root. */
    fun focusTarget(root: QuestNode): QuestNode {
        val open = root.children.filter { !it.done }
        if (open.isEmpty()) return root
        return open.firstOrNull { it.id == root.focusedChildId } ?: open.first()
    }
}

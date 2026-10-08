// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

/**
 * Plans the "What's next?" sheet for completing a step: after the step (and optionally
 * its quest) is done, which quest will own the root's visible next step, and must the
 * user type one? Used by the app's completion flow and by proposal acceptance, so both
 * build the same atomic `step.completed { next }` event.
 */
object Completion {
    data class Plan(
        val stepId: String,
        /** The quest (or subquest) the step belongs to. */
        val ownerId: String,
        val rootId: String,
        /** Where a new next step goes: the newly focused subquest, or the root. Null when the root is finished. */
        val nextOwnerId: String?,
        /** True when skipping the next step would leave a tracked quest without one. */
        val nextRequired: Boolean,
        /** Completing this step can also finish its owner ("Complete quest · +100 XP" / "Complete subquest · +40 XP"). */
        val canFinishOwner: Boolean,
        val ownerIsRoot: Boolean,
    )

    fun plan(state: QuestState, stepId: String, finishOwner: Boolean): Plan {
        val step = state.steps[stepId] ?: throw IllegalArgumentException("unknown step $stepId")
        val owner = state.quests[step.questId] ?: throw IllegalArgumentException("unknown quest ${step.questId}")
        val rootId = state.rootOf(owner.id) ?: owner.id
        val root = state.quests.getValue(rootId)

        // A practice never finishes, and its next session appears by itself (D-1110).
        if (owner.isPractice) {
            return Plan(stepId, owner.id, rootId, owner.id, nextRequired = false, canFinishOwner = false, ownerIsRoot = true)
        }
        if (finishOwner && owner.id == rootId) {
            return Plan(stepId, owner.id, rootId, null, nextRequired = false, canFinishOwner = true, ownerIsRoot = true)
        }
        // Who is focused once this completes?
        val focusedAfter: String? = if (finishOwner && root.focusedChildId == owner.id) {
            val open = state.children(rootId).filter { it.id != owner.id && it.status.isOpen }
            (open.firstOrNull { it.order > owner.order } ?: open.firstOrNull())?.id
        } else {
            root.focusedChildId?.takeIf { id -> state.quests[id]?.status?.isOpen == true && !(finishOwner && id == owner.id) }
        }
        val effectiveOwner = focusedAfter ?: rootId
        val effectiveOwnerStep = state.quests[effectiveOwner]?.openStepId
        val stepAfter = if (effectiveOwnerStep == stepId) null else effectiveOwnerStep
        return Plan(
            stepId = stepId,
            ownerId = owner.id,
            rootId = rootId,
            nextOwnerId = effectiveOwner,
            nextRequired = root.status == QuestStatus.TRACKED && stepAfter == null,
            canFinishOwner = owner.status.isOpen,
            ownerIsRoot = owner.id == rootId,
        )
    }

    data class LeavePlan(
        val rootId: String,
        /** Who owns the root's visible step afterwards; null when the root itself is leaving. */
        val nextOwnerId: String?,
        val nextRequired: Boolean,
    )

    /** Plans completing or dropping [questId] (a quest or a subquest) from the checklist or menu. */
    fun planLeave(state: QuestState, questId: String): LeavePlan {
        val q = state.quests[questId] ?: throw IllegalArgumentException("unknown quest $questId")
        val rootId = state.rootOf(questId) ?: questId
        if (rootId == questId) return LeavePlan(rootId, null, nextRequired = false)
        val root = state.quests.getValue(rootId)
        val focusedAfter = if (root.focusedChildId == questId) {
            val open = state.children(rootId).filter { it.id != questId && it.status.isOpen }
            (open.firstOrNull { it.order > q.order } ?: open.firstOrNull())?.id
        } else {
            root.focusedChildId?.takeIf { state.quests[it]?.status?.isOpen == true }
        }
        val owner = focusedAfter ?: rootId
        return LeavePlan(rootId, owner, root.status == QuestStatus.TRACKED && state.quests[owner]?.openStepId == null)
    }
}

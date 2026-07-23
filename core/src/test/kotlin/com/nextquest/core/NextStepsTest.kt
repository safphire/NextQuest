package com.nextquest.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NextStepsTest {

    private fun quest(
        id: String = "root",
        nextStep: String? = null,
        done: Boolean = false,
        focusedChildId: String? = null,
        children: List<QuestNode> = emptyList(),
    ) = QuestNode(id, "Quest $id", nextStep, done, focusedChildId, children)

    @Test
    fun `leaf quest surfaces its own next step`() {
        val q = quest(nextStep = "Draft the layer split")
        assertEquals(NextStep.Defined("root", "Draft the layer split"), NextSteps.resolve(q))
    }

    @Test
    fun `missing or blank next step is the amber undefined state`() {
        assertEquals(NextStep.Undefined("root"), NextSteps.resolve(quest(nextStep = null)))
        assertEquals(NextStep.Undefined("root"), NextSteps.resolve(quest(nextStep = "   ")))
    }

    @Test
    fun `with subquests the step comes from the first incomplete child by default`() {
        val q = quest(
            nextStep = "root step is not surfaced",
            children = listOf(
                quest(id = "a", done = true, nextStep = "done child"),
                quest(id = "b", nextStep = "improve prompts"),
                quest(id = "c", nextStep = "code it"),
            ),
        )
        assertEquals(NextStep.Defined("b", "improve prompts"), NextSteps.resolve(q))
    }

    @Test
    fun `focused child wins over first incomplete`() {
        val q = quest(
            focusedChildId = "c",
            children = listOf(
                quest(id = "b", nextStep = "improve prompts"),
                quest(id = "c", nextStep = "code it"),
            ),
        )
        assertEquals(NextStep.Defined("c", "code it"), NextSteps.resolve(q))
    }

    @Test
    fun `completed focused child falls back to first incomplete`() {
        val q = quest(
            focusedChildId = "a",
            children = listOf(
                quest(id = "a", done = true),
                quest(id = "b", nextStep = "approve memory design"),
            ),
        )
        assertEquals(NextStep.Defined("b", "approve memory design"), NextSteps.resolve(q))
    }

    @Test
    fun `all children done hands the step back to the root`() {
        val q = quest(
            nextStep = "wrap up",
            children = listOf(quest(id = "a", done = true), quest(id = "b", done = true)),
        )
        assertEquals(NextStep.Defined("root", "wrap up"), NextSteps.resolve(q))
    }

    @Test
    fun `incomplete focused child without a step is undefined on that child`() {
        val q = quest(
            focusedChildId = "b",
            children = listOf(quest(id = "b"), quest(id = "c", nextStep = "has one")),
        )
        assertEquals(NextStep.Undefined("b"), NextSteps.resolve(q))
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui

import android.Manifest
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.dreamweave.quest.core.Xp
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.presence.Notifications
import dev.dreamweave.quest.presence.ReminderPlan
import dev.dreamweave.quest.ui.focus.FocusEndPanel
import dev.dreamweave.quest.ui.focus.FocusStartSheet
import dev.dreamweave.quest.ui.focus.FocusView
import dev.dreamweave.quest.ui.reset.ResetMode
import dev.dreamweave.quest.ui.reset.ResetSheet
import dev.dreamweave.quest.ui.sheets.UndoBar
import dev.dreamweave.quest.util.ShareAi
import dev.dreamweave.quest.ui.board.BoardScreen
import dev.dreamweave.quest.ui.calendar.CalendarScreen
import dev.dreamweave.quest.ui.onboarding.OnboardingScreen
import dev.dreamweave.quest.ui.proposals.ProposalsScreen
import dev.dreamweave.quest.ui.quest.QuestDetailScreen
import dev.dreamweave.quest.ui.settings.SettingsScreen
import dev.dreamweave.quest.ui.sheets.CaptureSheet
import dev.dreamweave.quest.ui.sheets.QuestCompleteCelebration
import dev.dreamweave.quest.ui.sheets.ToastHost
import dev.dreamweave.quest.ui.sheets.WhatsNextSheet
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.ui.today.TodayScreen
import dev.dreamweave.quest.ui.trophies.TrophiesScreen
import kotlinx.coroutines.delay

object Routes {
    const val TODAY = "today"
    const val BOARD = "board"
    const val CALENDAR = "calendar"
    const val TROPHIES = "trophies"
    const val SETTINGS = "settings"
    const val PROPOSALS = "proposals"
    const val QUEST = "quest/{id}"
    fun quest(id: String) = "quest/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.TODAY, "Today", Icons.Rounded.Today),
    Tab(Routes.BOARD, "Board", Icons.Rounded.Dashboard),
    Tab(Routes.CALENDAR, "Calendar", Icons.Rounded.CalendarMonth),
    Tab(Routes.TROPHIES, "Trophies", Icons.Rounded.EmojiEvents),
)

/**
 * Things that can start a flow from outside the UI (share sheet, notification, widget,
 * pairing link). MainActivity sets it; the root consumes it once.
 */
sealed interface ExternalRequest {
    data class Capture(val text: String?) : ExternalRequest
    data class OpenQuest(val questId: String) : ExternalRequest
    data object OpenProposals : ExternalRequest
    data class Pair(val serverUrl: String, val code: String) : ExternalRequest

    /** The focus timer's notification: open the full-screen view. */
    data object OpenFocus : ExternalRequest

    /** "Done" on the end-of-block notification: the normal completion flow for its step. */
    data object FocusDone : ExternalRequest

    /** The Breathe notification: back to the guide. */
    data object OpenBreathe : ExternalRequest
}

/** Shared UI actions every screen may trigger. */
class RootActions(
    val openQuest: (String) -> Unit,
    val openProposals: () -> Unit,
    val openSettings: () -> Unit,
    val openBoard: () -> Unit,
    /** Done on a step: haptic + XP toast, then the "What's next?" sheet. */
    val done: (stepId: String) -> Unit,
    val back: () -> Unit,
    /** A reminder or alarm was just set: the one moment we ask for notification permission. */
    val reminderSet: () -> Unit = {},
    /** Focus on a step (B3): [minutes] starts at once; null first asks 10 / 25 / 50. */
    val focus: (stepId: String, minutes: Int?) -> Unit = { _, _ -> },
    /** The Reset sheet (C1), optionally opened on one of its parts and quest. */
    val reset: (mode: ResetMode, questId: String?) -> Unit = { _, _ -> },
    /** Share a ready "break this down" prompt with Claude or ChatGPT (B5). */
    val askAi: (questId: String) -> Unit = {},
)

@Composable
fun AppRoot(vm: QuestViewModel, external: ExternalRequest?, onExternalHandled: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val c = Quest.colors
    // Collected before any early return, so onboarding messages and achievements show too.
    val toasts = remember { mutableStateListOf<UiEvent>() }
    var undoOffer by remember { mutableStateOf<UiEvent.UndoOffer?>(null) }
    LaunchedEffect(vm) {
        vm.events.collect { if (it is UiEvent.UndoOffer) undoOffer = it else toasts.add(it) }
    }
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        delay(10_000)
        if (undoOffer === offer) undoOffer = null
    }
    val current = toasts.firstOrNull()
    LaunchedEffect(current) {
        if (current != null && current !is UiEvent.QuestCompleted) {
            delay(if (current is UiEvent.Message && current.action != null) 5_000 else 2_200)
            if (toasts.firstOrNull() === current) toasts.removeAt(0)
        }
    }
    val s = state
    val set = settings
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    if (s == null || set == null) {
        Box(Modifier.fillMaxSize().background(c.background).padding(24.dp), contentAlignment = Alignment.Center) {
            val error = loadError
            if (error == null) {
                CircularProgressIndicator(color = c.violet)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, style = MaterialTheme.typography.titleMedium, color = c.text, textAlign = TextAlign.Center)
                    Text(
                        "Your vault copies and snapshots are untouched.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { vm.loadLog() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reload") }
                }
            }
        }
        return
    }
    if (!set.onboardingDone) {
        Box(Modifier.fillMaxSize()) {
            OnboardingScreen(vm, s)
            ToastHost(current, onDismiss = { if (toasts.isNotEmpty()) toasts.removeAt(0) }, modifier = Modifier.align(Alignment.TopCenter))
        }
        return
    }

    val nav = rememberNavController()
    val view = LocalView.current
    val context = LocalContext.current
    val currentSettings by rememberUpdatedState(set)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.updateSettings { it.copy(notificationPermissionAsked = true) }
        if (!granted) vm.message("Reminders stay silent until notifications are allowed (Settings → Reminders).")
    }
    var showCapture by rememberSaveable { mutableStateOf(false) }
    var captureSeed by rememberSaveable { mutableStateOf("") }
    var completingStep by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFocusDone by rememberSaveable { mutableStateOf<String?>(null) }
    var pairRequest by remember { mutableStateOf<ExternalRequest.Pair?>(null) }
    val container = (context.applicationContext as QuestApp).container
    val focusSession by container.focus.session.collectAsStateWithLifecycle()
    val focusEndXp by container.focus.endXp.collectAsStateWithLifecycle()
    val breatheRun by container.breathe.run.collectAsStateWithLifecycle()
    var focusStartStep by rememberSaveable { mutableStateOf<String?>(null) }
    var focusOpen by rememberSaveable { mutableStateOf(false) }
    var resetModeName by rememberSaveable { mutableStateOf<String?>(null) }
    var resetQuestId by rememberSaveable { mutableStateOf<String?>(null) }
    // A block that ended while the app was away is noticed as soon as the app is back.
    LaunchedEffect(Unit) { container.focus.checkEnd() }


    LaunchedEffect(external) {
        when (external) {
            is ExternalRequest.Capture -> {
                captureSeed = external.text.orEmpty()
                showCapture = true
            }
            is ExternalRequest.OpenQuest -> nav.navigate(Routes.quest(external.questId))
            ExternalRequest.OpenProposals -> nav.navigate(Routes.PROPOSALS)
            is ExternalRequest.Pair -> {
                pairRequest = external
                nav.navigate(Routes.SETTINGS)
            }
            ExternalRequest.OpenFocus -> focusOpen = true
            ExternalRequest.FocusDone -> container.focus.session.value?.let { session ->
                container.focus.answered()
                focusOpen = false
                pendingFocusDone = session.stepId
            }
            ExternalRequest.OpenBreathe -> resetModeName = ResetMode.BREATHE.name
            null -> Unit
        }
        if (external != null) onExternalHandled()
    }

    val actions = remember(nav) {
        RootActions(
            openQuest = { nav.navigate(Routes.quest(it)) },
            openProposals = { nav.navigate(Routes.PROPOSALS) },
            openSettings = { nav.navigate(Routes.SETTINGS) },
            openBoard = { nav.navigateTab(Routes.BOARD) },
            done = { stepId ->
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
                )
                val st = vm.state.value
                val practice = st?.steps?.get(stepId)?.let { st.quests[it.questId] }?.isPractice == true
                if (practice) {
                    // A practice session: logged at once; the next session appears by itself (D-1110).
                    vm.run { completeStep(stepId, null, finishOwner = false) }
                } else {
                    toasts.add(UiEvent.XpGained(Xp.STEP.toLong()))
                    completingStep = stepId
                }
            },
            back = { nav.popBackStack() },
            reminderSet = {
                if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(context) && !currentSettings.notificationPermissionAsked) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            focus = { stepId, minutes ->
                val step = vm.state.value?.steps?.get(stepId)
                if (minutes == null || step == null) {
                    focusStartStep = stepId
                } else {
                    if (!container.focus.start(step.questId, step.id, step.text, minutes)) {
                        vm.message(context.getString(R.string.focus_already_running))
                    }
                    focusOpen = true
                }
            },
            reset = { mode, questId ->
                resetQuestId = questId
                resetModeName = mode.name
            },
            askAi = { questId ->
                val st = vm.state.value
                if (st == null || !ShareAi.shareBreakdown(context, st, questId, paired = currentSettings.serverUrl != null)) {
                    vm.message(context.getString(R.string.ask_ai_none))
                }
            },
        )
    }
    LaunchedEffect(pendingFocusDone) {
        val stepId = pendingFocusDone ?: return@LaunchedEffect
        pendingFocusDone = null
        if (vm.state.value?.let { ReminderPlan.isOpenStep(it, stepId) } == true) actions.done(stepId)
    }

    // The digest and streak guard are on by default: they are the first reminders, so this is
    // when Android 13+ asks for notifications (once; reminderSet() remembers it asked).
    LaunchedEffect(Unit) {
        if (currentSettings.digestEnabled || currentSettings.streakGuardEnabled) {
            delay(1_500)
            actions.reminderSet()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Box(Modifier.fillMaxSize().background(c.background)) {
        Scaffold(
            containerColor = c.background,
            bottomBar = {
                if (route in TABS.map { it.route } || route == null) {
                    NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { nav.navigateTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = c.violet, selectedTextColor = c.violet,
                                    indicatorColor = c.violetTint,
                                    unselectedIconColor = c.textSecondary, unselectedTextColor = c.textSecondary,
                                ),
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        captureSeed = ""
                        showCapture = true
                    },
                    containerColor = c.lime,
                    contentColor = c.onLime,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.semantics { contentDescription = "Capture" },
                ) { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(28.dp)) }
            },
        ) { padding ->
            NavHost(nav, startDestination = Routes.TODAY, modifier = Modifier.padding(padding)) {
                composable(Routes.TODAY) { TodayScreen(vm, s, set, actions) }
                composable(Routes.BOARD) { BoardScreen(vm, s, actions) }
                composable(Routes.CALENDAR) { CalendarScreen(vm, s, actions) }
                composable(Routes.TROPHIES) { TrophiesScreen(vm, s) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm, s, set, actions, pairRequest = pairRequest, onPairHandled = { pairRequest = null })
                }
                composable(Routes.PROPOSALS) { ProposalsScreen(vm, s, set, actions) }
                composable(Routes.QUEST, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    QuestDetailScreen(vm, s, entry.arguments?.getString("id").orEmpty(), actions)
                }
            }
        }

        ToastHost(
            event = current,
            onDismiss = { if (toasts.isNotEmpty()) toasts.removeAt(0) },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        undoOffer?.let { offer ->
            UndoBar(
                label = offer.label,
                onUndo = {
                    undoOffer = null
                    vm.undo(offer.eventId)
                },
                // Above the navigation bar, clear of the capture button.
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, end = 88.dp, bottom = 96.dp),
            )
        }

        val session = focusSession
        if (focusOpen && session != null) {
            FocusView(
                session = session,
                endXp = focusEndXp,
                onClose = { focusOpen = false },
                onPause = { container.focus.pause() },
                onResume = { container.focus.resume() },
                onStop = {
                    container.focus.stop()
                    focusOpen = false
                    vm.message(context.getString(R.string.focus_stopped))
                },
                onDone = {
                    container.focus.answered()
                    focusOpen = false
                    pendingFocusDone = session.stepId
                },
                onKeepGoing = { container.focus.keepGoing() },
                onNotYet = {
                    container.focus.answered()
                    focusOpen = false
                },
            )
        }
    }

    val session = focusSession
    if (session != null && session.ended && !focusOpen) {
        FocusEndPanel(
            session = session,
            endXp = focusEndXp,
            onDone = {
                container.focus.answered()
                pendingFocusDone = session.stepId
            },
            onKeepGoing = {
                container.focus.keepGoing()
                focusOpen = true
            },
            onNotYet = { container.focus.answered() },
        )
    }

    focusStartStep?.let { stepId ->
        val step = s.steps[stepId]
        if (step == null) {
            LaunchedEffect(stepId) { focusStartStep = null }
        } else {
            FocusStartSheet(
                stepText = step.text,
                initialMinutes = set.focusMinutes,
                onDismiss = { focusStartStep = null },
                onStart = { minutes ->
                    focusStartStep = null
                    vm.updateSettings { it.copy(focusMinutes = minutes) }
                    if (!container.focus.start(step.questId, step.id, step.text, minutes)) {
                        vm.message(context.getString(R.string.focus_already_running))
                    }
                    actions.reminderSet() // the chime is a notification: ask once, in context
                    focusOpen = true
                },
            )
        }
    }

    resetModeName?.let { name ->
        ResetSheet(
            state = s,
            initialMode = ResetMode.valueOf(name),
            breathing = breatheRun,
            shrinkQuestId = resetQuestId,
            onDismiss = {
                resetModeName = null
                resetQuestId = null
            },
            onBreathe = { minutes -> container.breathe.start(minutes) },
            onStopBreathing = { container.breathe.stop() },
            onShrink = { stepId, text ->
                resetModeName = null
                vm.run(onOk = { vm.message(context.getString(R.string.shrink_saved)) }) { editStepText(stepId, text) }
            },
            onFreshStart = { keep, park ->
                resetModeName = null
                vm.run(onOk = {
                    vm.message(context.getString(R.string.fresh_done))
                    vm.updateSettings {
                        it.copy(freshStartOfferDay = null, freshStartDismissals = 0, freshStartPausedUntil = null, resumeDismissedAt = System.currentTimeMillis())
                    }
                }) { freshStart(keep, park) }
            },
        )
    }

    if (current is UiEvent.QuestCompleted) {
        QuestCompleteCelebration(current, onDismiss = { if (toasts.isNotEmpty()) toasts.removeAt(0) })
    }

    if (showCapture) {
        CaptureSheet(
            initial = captureSeed,
            onDismiss = { showCapture = false },
            onCapture = { title -> vm.run { capture(title) } },
            onCaptureList = { text -> vm.run(onOk = { vm.message("Saved to your inbox, one item per line.") }) { captureList(text) } },
            onCapturePractice = { title, rhythm -> vm.run { capture(title, rhythm) } },
        )
    }

    completingStep?.let { stepId ->
        WhatsNextSheet(
            state = s,
            stepId = stepId,
            now = System.currentTimeMillis(),
            zone = vm.zone,
            onCancel = {
                completingStep = null
                // The +XP toast already showed (spec order: Done → XP → What's next?); say it didn't count.
                vm.message("Step kept open — no XP yet.")
            },
            onConfirm = { next, finish, dueAt, cue ->
                completingStep = null
                if (dueAt != null) actions.reminderSet()
                vm.run(xpAlreadyShown = Xp.STEP.toLong()) { completeStep(stepId, next, finish, dueAt, cue) }
            },
        )
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.TODAY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

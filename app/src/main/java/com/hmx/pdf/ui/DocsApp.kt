package com.hmx.pdf.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hmx.pdf.R
import com.hmx.pdf.data.repository.AppSettingsManager
import com.hmx.pdf.data.repository.GitHubStarPromptManager
import com.hmx.pdf.data.repository.OnboardingManager
import com.hmx.pdf.data.repository.SaveRecovery
import com.hmx.pdf.ui.components.DocsBottomTabs
import com.hmx.pdf.ui.components.LiquidButton
import com.hmx.pdf.ui.components.liquidGlassPanel
import com.hmx.pdf.ui.navigation.DocsNavGraph
import com.hmx.pdf.ui.navigation.ROUTE_ONBOARDING
import com.hmx.pdf.ui.theme.LocalIsDarkMode
import com.hmx.pdf.ui.theme.LocalReducedGlassMotion
import com.hmx.pdf.ui.utils.StarPromptEventBus
import com.hmx.pdf.ui.utils.rememberUISensor
import com.hmx.pdf.ui.components.GlassDialog
import com.hmx.pdf.ui.components.GlassDialogAction
import com.hmx.pdf.ui.components.GlassOverlayHost
import com.hmx.pdf.ui.components.GlassOverlayLayer
import com.hmx.pdf.ui.components.LocalGlassOverlayHost
import com.hmx.pdf.ui.components.LocalBackToLibraryAction
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Walk the ContextWrapper chain to the hosting Activity (for locale-change recreate). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Root composable for the Docs app.
 * Provides the wallpaper backdrop, floating bottom tabs, and navigation host.
 */
@Composable
fun DocsApp(shortcutRoute: String? = null, incomingPdfUri: android.net.Uri? = null, documentWindow: Boolean = false) {
    val context = LocalContext.current
    var selectedLocale by rememberSaveable { mutableStateOf<String>(OnboardingManager.getSelectedLocale(context)) }
    val localizedContext = remember(context, selectedLocale) {
        com.hmx.pdf.ui.utils.LocaleHelper.getLocalizedContext(context, selectedLocale)
    }

    // Read ONCE. `setOnboardingComplete()` flips this mid-session, and re-reading it would swap the
    // NavHost's start destination underneath a live back stack. A first launch that arrives via a
    // share or a shortcut skips the tour and goes straight to the document — the flag is left unset,
    // so onboarding still appears on the next ordinary cold start rather than being lost.
    val needsOnboarding = remember {
        // Shows on first launch and again after each app update (version-aware).
        OnboardingManager.shouldShowOnboarding(context) &&
            shortcutRoute == null && incomingPdfUri == null
    }
    // The locale the Activity actually booted with. Onboarding changes `selectedLocale` in place for
    // the flow's own strings, but the rest of the app was built by `attachBaseContext` against this
    // one, so only a difference from *this* value justifies a restart at the end.
    val bootLocale = remember { selectedLocale }

    var themeMode by rememberSaveable { mutableIntStateOf(AppSettingsManager.getThemeMode(context)) }
    var showWallpaper by rememberSaveable { mutableStateOf(AppSettingsManager.getShowWallpaper(context)) }
    var reduceGlassMotion by rememberSaveable { mutableStateOf(AppSettingsManager.getReduceGlassMotion(context)) }
    var customWallpaper by rememberSaveable { mutableStateOf(AppSettingsManager.getCustomWallpaper(context)) }
    // Decode the user's chosen background off the main thread (downsampled to the screen).
    var customWallpaperBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(customWallpaper) {
        customWallpaperBitmap = customWallpaper?.let { uriStr ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    context.contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    var showStarPrompt by rememberSaveable { mutableStateOf(false) }
    // Switching into or out of a right-to-left language (Arabic) can't flip the layout in place;
    // the app offers a restart instead.
    var restartForDirection by rememberSaveable { mutableStateOf(false) }

    // A save interrupted by process death leaves a durable journal in noBackupFilesDir. On the
    // next normal launch, offer the recovered copy without ever overwriting the destination.
    var recoveryEntry by remember { mutableStateOf<SaveRecovery.Entry?>(null) }
    var recoveryChecked by remember { mutableStateOf(false) }
    val recoveryScope = rememberCoroutineScope()
    val refreshRecovery: () -> Unit = {
        recoveryScope.launch {
            recoveryEntry = runCatching {
                withContext(Dispatchers.IO) { SaveRecovery.pending(context) }
            }.getOrDefault(emptyList<SaveRecovery.Entry>()).firstOrNull()
        }
    }
    val recoveryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val entry = recoveryEntry
        val target = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || entry == null || target == null) {
            return@rememberLauncherForActivityResult
        }
        recoveryScope.launch {
            val saved = withContext(Dispatchers.IO) {
                SaveRecovery.copyRecovered(context, entry, target)
            }
            Toast.makeText(
                context,
                if (saved) R.string.save_recovery_saved else R.string.save_recovery_failed,
                Toast.LENGTH_LONG
            ).show()
            if (saved) refreshRecovery()
        }
    }
    LaunchedEffect(needsOnboarding, documentWindow, currentRoute) {
        // Do not stack the recovery prompt on top of What's New / onboarding. Once the user lands
        // on a normal screen, pick up any journal that survived an interrupted save. Only check
        // once per launch so tapping "Later" is not undone by a tab change.
        if (recoveryChecked) return@LaunchedEffect
        if (!needsOnboarding && !documentWindow && currentRoute != null &&
            currentRoute != com.hmx.pdf.ui.navigation.ROUTE_WHATS_NEW &&
            currentRoute != com.hmx.pdf.ui.navigation.ROUTE_ONBOARDING
        ) {
            recoveryEntry = runCatching {
                withContext(Dispatchers.IO) { SaveRecovery.pending(context) }
            }.getOrDefault(emptyList<SaveRecovery.Entry>()).firstOrNull()
            recoveryChecked = true
        }
    }
    val systemDark = isSystemInDarkTheme()
    val isDarkMode = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    // Once per update, the app opens on the What's New page: what changed since the version this
    // user last saw. Never alongside the first-run tour (that already covers everything), and, like
    // the tour, never when a share or shortcut is waiting. It also takes the star prompt's turn.
    // Read once, for the same start-destination reason as `needsOnboarding`.
    val showWhatsNew = remember {
        !needsOnboarding && shortcutRoute == null && incomingPdfUri == null &&
            OnboardingManager.shouldShowWhatsNew(context)
    }

    // A finished task only reports that a good moment happened; the ask itself waits for a calm
    // screen (below). It no longer pops up at launch.
    var starMoment by remember { mutableIntStateOf(0) }
    LaunchedEffect(context) {
        StarPromptEventBus.promptRequests.collect { starMoment++ }
    }

    val openRepo = remember(context) {
        {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GitHubStarPromptManager.REPO_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    // ── Locale-switch choreography ──────────────────────────────────────────────────────────────
    // The switch still restarts the Activity (see LocaleHelper.markLocaleFadePending), but the two
    // halves are animated so it reads as one deliberate cross-fade instead of a hard flash.
    var localeSwitching by remember { mutableStateOf(false) }
    val exitProgress by animateFloatAsState(
        targetValue = if (localeSwitching) 0f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "localeExit"
    )
    val fadeInPending = remember { com.hmx.pdf.ui.utils.LocaleHelper.consumeLocaleFadePending(context) }
    var entered by remember { mutableStateOf(!fadeInPending) }
    LaunchedEffect(Unit) { entered = true }
    val enterProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "localeEnter"
    )
    LaunchedEffect(localeSwitching) {
        if (localeSwitching) {
            // Restart once the fade has actually landed, not while it is still running.
            kotlinx.coroutines.delay(210)
            context.findActivity()?.let { activity ->
                activity.recreate()
                com.hmx.pdf.ui.utils.LocaleHelper.suppressActivityTransition(activity)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Root-level only. The transform composites the finished frame, so the glass inside is
            // never re-sampled — it does not violate the "don't move glass" rule.
            .graphicsLayer {
                alpha = exitProgress * enterProgress
                val s = lerp(0.98f, 1f, exitProgress) * lerp(1.02f, 1f, enterProgress)
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.TopCenter
    ) {
        val backdrop = rememberLayerBackdrop()
        val navController = rememberNavController()
        // A document window (#29) closes once its document does: back from the viewer lands on
        // Home, and instead of showing Home inside the document's window we close the window,
        // returning to the app that opened the file.
        if (documentWindow) {
            val activity = LocalContext.current as? android.app.Activity
            LaunchedEffect(navController) {
                var shownDocument = false
                navController.currentBackStackEntryFlow.collect { entry ->
                    val route = entry.destination.route
                    if (route != null && route != "home") shownDocument = true
                    else if (route == "home" && shownDocument) activity?.finishAndRemoveTask()
                }
            }
        }
        val backToLibrary: () -> Unit = remember(navController) {
            {
                if (!navController.popBackStack("home", inclusive = false)) {
                    navController.navigate("home") { launchSingleTop = true }
                }
            }
        }
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        val showBottomTabs = currentRoute == "home" || currentRoute == "tools" ||
            (currentRoute == "settings" && !com.hmx.pdf.ui.screen.SettingsSubPage.open)
        val onBottomTabSelected: (Int) -> Unit = remember(navController) {
            { index ->
                selectedTab = index
                when (index) {
                    0 -> navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                        launchSingleTop = true
                    }

                    1 -> navController.navigate("tools") {
                        popUpTo("home")
                        launchSingleTop = true
                    }

                    2 -> navController.navigate("settings") {
                        popUpTo("home")
                        launchSingleTop = true
                    }
                }
            }
        }

        // Handle app shortcut deep links. Guarded on the gate so a shortcut can never fire on top of
        // the tour — though `needsOnboarding` is already false whenever a shortcut is present.
        LaunchedEffect(shortcutRoute) {
            if (shortcutRoute != null && !needsOnboarding) {
                navController.navigate(shortcutRoute) { launchSingleTop = true }
            }
        }

        // The star ask, at a calm moment only: back on Home or Tools after something went right in
        // this launch (GitHubStarPromptManager holds the rest: earned use, a returning user, no
        // recent failure, backing off). Never mid-task, never in a document opened from another
        // app, never on a launch that showed the tour or What's New. Leaving the tab cancels it.
        LaunchedEffect(currentRoute, starMoment, recoveryEntry) {
            if (showWhatsNew || needsOnboarding || documentWindow || recoveryEntry != null) {
                return@LaunchedEffect
            }
            if (currentRoute != "home" && currentRoute != "tools") return@LaunchedEffect
            kotlinx.coroutines.delay(900)
            if (GitHubStarPromptManager.shouldShowPrompt(context)) {
                GitHubStarPromptManager.onPromptShown()
                showStarPrompt = true
            }
        }

        // Keep tab highlight synced even when navigation occurs from cards/deep links.
        LaunchedEffect(currentRoute) {
            selectedTab = when (currentRoute) {
                "home" -> 0
                "tools" -> 1
                "settings" -> 2
                else -> selectedTab
            }
        }

        val contentBackdrop = rememberLayerBackdrop()
        // With the wallpaper off, `backdrop` records nothing but one solid colour (the Box below), so
        // every glass surface sampling it can draw that colour directly instead of blurring it.
        val flatWallpaperColor = if (!isDarkMode) Color(0xFFE9E9EE) else Color(0xFF1C1C1E)
        val flatBackdrop = remember(showWallpaper, flatWallpaperColor, backdrop) {
            if (showWallpaper) null else com.hmx.pdf.ui.components.FlatBackdrop(backdrop, flatWallpaperColor)
        }
        // Floating glass (dropdown menus, contextual bars) from any depth renders here, above every
        // screen, refracting the live screen — see GlassOverlayHost.
        val overlayHost = remember { GlassOverlayHost() }
        remember(context) {
            com.hmx.pdf.ui.components.GlassSettings.load(context)
            com.hmx.pdf.ui.components.MotionSettings.load(context)
            GitHubStarPromptManager.recordActiveDay(context)
            true
        }
        val platformHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val haptics = remember(platformHaptics) { com.hmx.pdf.ui.utils.ThrottledHaptics(platformHaptics) }

        CompositionLocalProvider(
            LocalResources provides localizedContext.resources,
            LocalIsDarkMode provides isDarkMode,
            LocalReducedGlassMotion provides reduceGlassMotion,
            LocalBackToLibraryAction provides backToLibrary,
            com.hmx.pdf.ui.components.LocalFlatBackdrop provides flatBackdrop,
            LocalGlassOverlayHost provides overlayHost,
            androidx.compose.ui.platform.LocalHapticFeedback provides haptics
        ) {
            // Per-icon ink in the document viewers (each control samples what's behind it).
            com.hmx.pdf.ui.components.ProvideScreenInk {
            Box(Modifier.fillMaxSize()) {
                // Captured layer = wallpaper + the live screen. The floating tab bar
                // (below) samples THIS, so it reflects real content scrolling under it
                // instead of the static wallpaper PNG. Per-screen glass keeps sampling
                // the wallpaper-only `backdrop`; it lives INSIDE this layer but samples a
                // different backdrop, so there is no glass-on-glass feedback loop.
                Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
                    if (showWallpaper) {
                        val customBmp = customWallpaperBitmap
                        if (customBmp != null) {
                            Image(
                                bitmap = customBmp,
                                contentDescription = null,
                                modifier = Modifier.layerBackdrop(backdrop).fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Image(
                                painterResource(if (!isDarkMode) R.drawable.wallpaper_light else R.drawable.wallpaper_dark),
                                contentDescription = null,
                                modifier = Modifier.layerBackdrop(backdrop).fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        // Wallpaper off: fall back to a NEUTRAL GREY base (not pure white/black).
                        // Apple never puts glass on pure #FFF or #000 — the translucent surfaces
                        // would vanish. A light grey (#E9E9EE) / elevated dark grey (#1C1C1E) keeps
                        // the liquid-glass panels and buttons legible with real depth.
                        // Must stay a single solid fill: LocalFlatBackdrop (above) promises glass that
                        // this layer contains exactly `flatWallpaperColor` and nothing else.
                        Box(
                            Modifier
                                .layerBackdrop(backdrop)
                                .fillMaxSize()
                                .background(flatWallpaperColor)
                        )
                    }
                    DocsNavGraph(
                    navController = navController,
                    backdrop = backdrop,
                    selectedTab = selectedTab,
                    onTabChanged = { selectedTab = it },
                    isDarkMode = isDarkMode,
                    onDarkModeChanged = { /* unused */ },
                    themeMode = themeMode,
                    onThemeModeChanged = {
                        themeMode = it
                        AppSettingsManager.setThemeMode(context, it)
                    },
                    showWallpaper = showWallpaper,
                    onShowWallpaperChanged = {
                        showWallpaper = it
                        AppSettingsManager.setShowWallpaper(context, it)
                    },
                    reduceGlassMotion = reduceGlassMotion,
                    onReduceGlassMotionChanged = {
                        reduceGlassMotion = it
                        AppSettingsManager.setReduceGlassMotion(context, it)
                    },
                    hasCustomWallpaper = customWallpaper != null,
                    onCustomWallpaperChanged = { customWallpaper = it },
                    selectedLocale = selectedLocale,
                    onLocaleChanged = { code ->
                        val normalized = com.hmx.pdf.ui.utils.LocaleHelper.normalizeForUi(code)
                        if (normalized != selectedLocale) {
                            if (isRtlTag(normalized) != isRtlTag(selectedLocale)) restartForDirection = true
                            // Persist, then switch IN PLACE: the hoisted locale re-provides
                            // LocalResources, so every stringResource re-resolves on the next frame
                            // (the path Onboarding uses). No fade-out + Activity recreate — that was
                            // the slow flicker after picking a language. attachBaseContext picks the
                            // persisted choice up on the next launch for non-Compose strings.
                            com.hmx.pdf.ui.utils.LocaleHelper.applyLocale(
                                context = context,
                                languageTag = normalized,
                                recreate = false,
                                updateAppCompat = false
                            )
                            OnboardingManager.setSelectedLocale(context, normalized)
                            selectedLocale = normalized
                        }
                    },
                    incomingPdfUri = incomingPdfUri,
                    startDestination = when {
                        needsOnboarding -> ROUTE_ONBOARDING
                        showWhatsNew -> com.hmx.pdf.ui.navigation.ROUTE_WHATS_NEW
                        else -> "home"
                    },
                    // In place: persist + update the hoisted state, which re-provides LocalResources
                    // above, so every `stringResource` in the flow re-resolves. No recreate, or the
                    // tour would relaunch at page one mid-flow.
                    onOnboardingLocaleSelected = { code ->
                        val normalized = com.hmx.pdf.ui.utils.LocaleHelper.normalizeForUi(code)
                        if (normalized != selectedLocale) {
                            OnboardingManager.setSelectedLocale(context, normalized)
                            selectedLocale = normalized
                        }
                    },
                    onOnboardingFinished = {
                        OnboardingManager.setOnboardingComplete(context)
                        // Only now, and only if the choice actually differs from what
                        // `attachBaseContext` built the app with, is a restart worth its cost — it
                        // is what makes non-Compose strings (toasts, notifications) follow suit.
                        if (selectedLocale != bootLocale) {
                            com.hmx.pdf.ui.utils.LocaleHelper.applyLocale(
                                context = context,
                                languageTag = selectedLocale,
                                recreate = false,
                                updateAppCompat = false
                            )
                            com.hmx.pdf.ui.utils.LocaleHelper.markLocaleFadePending(context)
                            localeSwitching = true
                        }
                    }
                )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showBottomTabs,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                    enter = androidx.compose.animation.fadeIn(
                        // EaseInOut tween: opacity arrives slightly after the slide,
                        // giving a sequential layered feel.
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 320,
                            delayMillis = 40,
                            easing = androidx.compose.animation.core.EaseInOut
                        )
                    ) + androidx.compose.animation.slideInVertically(
                        // Critically-overdamped spring = no bounce, pure fluid glide.
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { it }
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 200,
                            easing = androidx.compose.animation.core.EaseInOut
                        )
                    ) + androidx.compose.animation.slideOutVertically(
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        ),
                        targetOffsetY = { it }
                    )
                ) {
                    DocsBottomTabs(
                        selectedTab = { selectedTab },
                        onTabSelected = onBottomTabSelected,
                        backdrop = contentBackdrop,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }

                // Star prompt — in-window liquid glass over the live screen (wallpaper + content).
                // It used to be a solid card in a Dialog window, which cannot sample anything.
                run {
                    val starText = if (!isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
                    val starSub = if (!isDarkMode) Color(0xFF666666) else Color(0xFFAAAAAA)
                    val starAccent = Color(0xFFFFB300)
                    val dismissStar = {
                        GitHubStarPromptManager.onPromptDismissed(context)
                        showStarPrompt = false
                    }
                    GlassDialog(
                        visible = showStarPrompt,
                        onDismiss = dismissStar,
                        backdrop = contentBackdrop,
                        title = null,
                        actions = {
                            GlassDialogAction(stringResource(R.string.star_prompt_later), onClick = dismissStar)
                            GlassDialogAction(
                                stringResource(R.string.star_prompt_accept),
                                onClick = {
                                    GitHubStarPromptManager.onPromptAccepted(context)
                                    showStarPrompt = false
                                    openRepo()
                                },
                                primary = true,
                                tint = starAccent
                            )
                        }
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Rounded.Star, null, Modifier.size(52.dp), starAccent)
                            BasicText(
                                stringResource(R.string.star_prompt_title),
                                style = TextStyle(starText, 20.sp, FontWeight.Bold, textAlign = TextAlign.Center)
                            )
                            BasicText(
                                stringResource(R.string.star_prompt_message),
                                style = TextStyle(starSub, 14.sp, textAlign = TextAlign.Center)
                            )
                            BasicText(
                                stringResource(R.string.star_prompt_never),
                                style = TextStyle(starSub.copy(0.8f), 13.sp, FontWeight.Medium, textAlign = TextAlign.Center),
                                modifier = Modifier
                                    .clickable {
                                        GitHubStarPromptManager.setNeverShowAgain(context)
                                        showStarPrompt = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                // A new language that reads the other way round (into or out of Arabic): strings
                // switch in place, but the layout direction only flips with a fresh start.
                GlassDialog(
                    visible = restartForDirection,
                    onDismiss = { restartForDirection = false },
                    backdrop = contentBackdrop,
                    title = stringResource(R.string.restart_title),
                    actions = {
                        GlassDialogAction(stringResource(R.string.restart_later), onClick = { restartForDirection = false })
                        GlassDialogAction(
                            stringResource(R.string.restart_now),
                            onClick = { restartForDirection = false; restartApp(context) },
                            primary = true
                        )
                    }
                ) {
                    BasicText(
                        stringResource(R.string.restart_body),
                        style = TextStyle(
                            if (!isDarkMode) Color(0xFF3A3A3C) else Color(0xFFD1D1D6),
                            15.sp,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // A save interrupted before its destination was verified. The journal holds a
                // validated prepared copy and/or the original; this offers it as a new file and
                // never silently rewrites the possibly independently changed destination.
                val pendingRecovery = recoveryEntry
                GlassDialog(
                    visible = pendingRecovery != null &&
                        currentRoute != com.hmx.pdf.ui.navigation.ROUTE_WHATS_NEW &&
                        currentRoute != com.hmx.pdf.ui.navigation.ROUTE_ONBOARDING,
                    onDismiss = { recoveryEntry = null },
                    backdrop = contentBackdrop,
                    title = stringResource(R.string.save_recovery_title),
                    actions = {
                        GlassDialogAction(
                            stringResource(R.string.save_recovery_later),
                            onClick = { recoveryEntry = null }
                        )
                        GlassDialogAction(
                            stringResource(R.string.save_recovery_discard),
                            onClick = {
                                pendingRecovery?.let { entry ->
                                    recoveryScope.launch {
                                        withContext(Dispatchers.IO) { SaveRecovery.discard(entry) }
                                        refreshRecovery()
                                    }
                                }
                            }
                        )
                        GlassDialogAction(
                            stringResource(R.string.save_recovery_save_copy),
                            onClick = {
                                pendingRecovery?.let { entry ->
                                    runCatching {
                                        recoveryLauncher.launch(
                                            Intent(Intent.ACTION_CREATE_DOCUMENT)
                                                .addCategory(Intent.CATEGORY_OPENABLE)
                                                .setType(SaveRecovery.mimeType(entry))
                                                .putExtra(
                                                    Intent.EXTRA_TITLE,
                                                    SaveRecovery.suggestedRecoveredName(entry)
                                                )
                                        )
                                    }
                                }
                            },
                            primary = true
                        )
                    }
                ) {
                    BasicText(
                        stringResource(
                            R.string.save_recovery_body,
                            pendingRecovery?.displayName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.save_recovery_document)
                        ),
                        style = TextStyle(
                            if (!isDarkMode) Color(0xFF3A3A3C) else Color(0xFFD1D1D6),
                            15.sp,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Last child: above the tab bar and dialogs, outside the layer it samples.
                GlassOverlayLayer(overlayHost, contentBackdrop)
            }
            }
        }
    }
}

/** Whether [tag]'s language reads right to left (Arabic, in the app's languages). */
private fun isRtlTag(tag: String): Boolean =
    android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.forLanguageTag(tag)) == android.view.View.LAYOUT_DIRECTION_RTL

/**
 * Starts the app afresh, so a language that reads the other way round lays every screen out in its
 * direction (attachBaseContext applies the saved language on the way up).
 */
private fun restartApp(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ?: return
    context.startActivity(intent)
    context.findActivity()?.finishAffinity()
    Runtime.getRuntime().exit(0)
}

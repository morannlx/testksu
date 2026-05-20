package me.inkdye.vivoksu.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.flow.MutableStateFlow
import me.inkdye.vivoksu.Natives
import me.inkdye.vivoksu.R
import me.inkdye.vivoksu.ui.component.bottombar.BottomBar
import me.inkdye.vivoksu.ui.component.bottombar.MainPagerState
import me.inkdye.vivoksu.ui.component.bottombar.SideRail
import me.inkdye.vivoksu.ui.component.bottombar.rememberMainPagerState
import me.inkdye.vivoksu.ui.component.dialog.rememberConfirmDialog
import me.inkdye.vivoksu.ui.navigation3.HandleDeepLink
import me.inkdye.vivoksu.ui.navigation3.LocalNavigator
import me.inkdye.vivoksu.ui.navigation3.Navigator
import me.inkdye.vivoksu.ui.navigation3.Route
import me.inkdye.vivoksu.ui.navigation3.rememberNavigator
import me.inkdye.vivoksu.ui.screen.about.AboutScreen
import me.inkdye.vivoksu.ui.screen.appprofile.AppProfileScreen
import me.inkdye.vivoksu.ui.screen.colorpalette.ColorPaletteScreen
import me.inkdye.vivoksu.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import me.inkdye.vivoksu.ui.screen.flash.FlashIt
import me.inkdye.vivoksu.ui.screen.flash.FlashScreen
import me.inkdye.vivoksu.ui.screen.home.HomePager
import me.inkdye.vivoksu.ui.screen.install.InstallScreen
import me.inkdye.vivoksu.ui.screen.module.ModulePager
import me.inkdye.vivoksu.ui.screen.modulerepo.ModuleRepoDetailScreen
import me.inkdye.vivoksu.ui.screen.modulerepo.ModuleRepoScreen
import me.inkdye.vivoksu.ui.screen.settings.SettingPager
import me.inkdye.vivoksu.ui.screen.sulog.SulogScreen
import me.inkdye.vivoksu.ui.screen.superuser.SuperUserPager
import me.inkdye.vivoksu.ui.screen.template.AppProfileTemplateScreen
import me.inkdye.vivoksu.ui.screen.templateeditor.TemplateEditorScreen
import me.inkdye.vivoksu.ui.theme.KernelSUTheme
import me.inkdye.vivoksu.ui.theme.LocalColorMode
import me.inkdye.vivoksu.ui.theme.LocalEnableBlur
import me.inkdye.vivoksu.ui.theme.LocalEnableFloatingBottomBar
import me.inkdye.vivoksu.ui.theme.LocalEnableFloatingBottomBarBlur
import me.inkdye.vivoksu.ui.util.LocalSnackbarHost
import me.inkdye.vivoksu.ui.util.getFileName
import me.inkdye.vivoksu.ui.util.install
import me.inkdye.vivoksu.ui.util.rememberBlurBackdrop
import me.inkdye.vivoksu.ui.util.rememberContentReady
import me.inkdye.vivoksu.ui.util.rootAvailable
import me.inkdye.vivoksu.ui.viewmodel.MainActivityViewModel
import me.inkdye.vivoksu.ui.viewmodel.MainPagerConfig
import me.inkdye.vivoksu.ui.webui.WebUIActivity
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val intentState = MutableStateFlow(0)

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isManager = try { Natives.isManager } catch (_: Throwable) { false }
        if (isManager && !try { Natives.requireNewKernel() } catch (_: Throwable) { false }) install()

        setContent {
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val uiMode = uiState.uiMode
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val snackBarHostState = remember { SnackbarHostState() }
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalUiMode provides uiMode,
                LocalSnackbarHost provides snackBarHostState
            ) {
                KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                    HandleDeepLink(intentState = intentState.collectAsStateWithLifecycle())
                    ZipFileIntentHandler(intentState = intentState, isManager = isManager)
                    ShortcutIntentHandler(intentState = intentState)
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            initialPage = selectedMainPage,
                            onPageChanged = viewModel::setSelectedMainPage,
                        )
                    }

                    val navDisplay = @Composable {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            onBack = {
                                when (val top = navigator.current()) {
                                    is Route.TemplateEditor -> {
                                        if (!top.readOnly) {
                                            navigator.setResult("template_edit", true)
                                        } else {
                                            navigator.pop()
                                        }
                                    }

                                    else -> navigator.pop()
                                }
                            },
                            entryProvider = entryProvider {
                                entry<Route.Main> { mainScreenEntry() }
                                entry<Route.About> { AboutScreen() }
                                entry<Route.Sulog> { SulogScreen() }
                                entry<Route.ColorPalette> { ColorPaletteScreen() }
                                entry<Route.AppProfileTemplate> { AppProfileTemplateScreen() }
                                entry<Route.TemplateEditor> { key -> TemplateEditorScreen(key.template, key.readOnly) }
                                entry<Route.AppProfile> { key -> AppProfileScreen(key.uid) }
                                entry<Route.ModuleRepo> { ModuleRepoScreen() }
                                entry<Route.ModuleRepoDetail> { key -> ModuleRepoDetailScreen(key.module) }
                                entry<Route.Install> { InstallScreen() }
                                entry<Route.Flash> { key -> FlashScreen(key.flashIt) }
                                entry<Route.ExecuteModuleAction> { key -> ExecuteModuleActionScreen(key.moduleId, key.fromShortcut) }
                                entry<Route.Home> { mainScreenEntry() }
                                entry<Route.SuperUser> { mainScreenEntry() }
                                entry<Route.Module> { mainScreenEntry() }
                                entry<Route.Settings> { mainScreenEntry() }
                            }
                        )
                    }

                    when (uiMode) {
                        UiMode.Material -> androidx.compose.material3.Scaffold { navDisplay() }
                        UiMode.Miuix -> Scaffold { navDisplay() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Increment intentState to trigger LaunchedEffect re-execution
        intentState.value += 1
    }
}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(pagerState)
    val isManager = try { Natives.isManager } catch (_: Throwable) { false }
    val isFullFeatured = isManager && !try { Natives.requireNewKernel() } catch (_: Throwable) { false } && rootAvailable()
    var userScrollEnabled by remember(isFullFeatured) { mutableStateOf(isFullFeatured) }
    val uiMode = LocalUiMode.current
    val surfaceColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surface // Blur is not used in Material, this is just a placeholder
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navController)

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useNavigationRail = isLandscape && !(uiMode == UiMode.Miuix && enableFloatingBottomBar)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = if (contentReady) 3 else 0,
                    userScrollEnabled = userScrollEnabled,
                ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> if (isCurrentPage || contentReady) HomePager(navController, bottomInnerPadding, isCurrentPage)
                        1 -> if (isCurrentPage || contentReady) SuperUserPager(navController, bottomInnerPadding, isCurrentPage)
                        2 -> if (isCurrentPage || contentReady) ModulePager(bottomInnerPadding, isCurrentPage)
                        3 -> if (isCurrentPage || contentReady) SettingPager(navController, bottomInnerPadding)
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold {
                    Row {
                        SideRail(
                            blurBackdrop = blurBackdrop,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }

                UiMode.Miuix -> Scaffold { _ ->
                    Row {
                        SideRail(
                            blurBackdrop = blurBackdrop,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }

                UiMode.Miuix -> Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
            }
        }
    }
}


@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}

/**
 * Handles ZIP file installation from external apps (e.g., file managers).
 * - In normal mode: Shows a confirmation dialog before installation
 * - In safe mode: Shows a Toast notification and prevents installation
 */
@SuppressLint("StringFormatInvalid", "LocalContextGetResourceValueCall")
@Composable
private fun ZipFileIntentHandler(
    intentState: MutableStateFlow<Int>,
    isManager: Boolean,
) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    var zipUri by remember { mutableStateOf<Uri?>(null) }
    val isSafeMode = try { Natives.isSafeMode } catch (_: Throwable) { false }
    val clearZipUri = { zipUri = null }
    val navigator = LocalNavigator.current

    val installDialog = rememberConfirmDialog(
        onConfirm = {
            zipUri?.let { uri -> navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri)))) }
            clearZipUri()
        },
        onDismiss = clearZipUri
    )

    fun getDisplayName(uri: Uri): String {
        return uri.getFileName(context) ?: uri.lastPathSegment ?: "Unknown"
    }

    val intentStateValue by intentState.collectAsStateWithLifecycle()
    LaunchedEffect(intentStateValue) {
        val currentIntent = activity.intent
        val uri = currentIntent?.data ?: return@LaunchedEffect

        if (!isManager || uri.scheme != "content" || currentIntent.type != "application/zip") {
            return@LaunchedEffect
        }

        activity.intent.data = null
        activity.intent.type = null

        if (isSafeMode) {
            Toast.makeText(context, context.getString(R.string.safe_mode_module_disabled), Toast.LENGTH_SHORT).show()
        } else {
            zipUri = uri
            installDialog.showConfirm(
                title = context.getString(R.string.module),
                content = context.getString(
                    R.string.module_install_prompt_with_name,
                    "\n${getDisplayName(uri)}"
                )
            )
        }
    }
}

@Composable
private fun ShortcutIntentHandler(
    intentState: MutableStateFlow<Int>,
) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val intentStateValue by intentState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(intentStateValue) {
        val intent = activity.intent
        val type = intent?.getStringExtra("shortcut_type") ?: return@LaunchedEffect

        when (type) {
            "module_action" -> {
                val moduleId = intent.getStringExtra("module_id") ?: return@LaunchedEffect
                navigator.push(Route.ExecuteModuleAction(moduleId, fromShortcut = true))
                intent.removeExtra("shortcut_type")
                intent.removeExtra("module_id")
            }

            "module_webui" -> {
                val moduleId = intent.getStringExtra("module_id") ?: return@LaunchedEffect
                val webIntent = Intent(context, WebUIActivity::class.java)
                    .setData("kernelsu://webui/$moduleId".toUri())
                context.startActivity(webIntent)
            }

            else -> return@LaunchedEffect
        }
    }
}

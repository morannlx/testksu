package me.inkdye.vivoksu.ui.viewmodel

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.inkdye.vivoksu.BuildConfig
import me.inkdye.vivoksu.Natives
import me.inkdye.vivoksu.getKernelVersion
import me.inkdye.vivoksu.ksuApp
import me.inkdye.vivoksu.ui.screen.home.HomeUiState
import me.inkdye.vivoksu.ui.screen.home.SystemInfo
import me.inkdye.vivoksu.ui.screen.home.getManagerVersion
import me.inkdye.vivoksu.ui.util.checkNewVersion
import me.inkdye.vivoksu.ui.util.getModuleCount
import me.inkdye.vivoksu.ui.util.getSELinuxStatusRaw
import me.inkdye.vivoksu.ui.util.getSuperuserCount
import me.inkdye.vivoksu.ui.util.module.LatestVersionInfo
import me.inkdye.vivoksu.ui.util.rootAvailable

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update { baseState }
            if (baseState.checkUpdateEnabled) {
                val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            }
        }
    }

    private fun buildState(): HomeUiState {
        val kernelVersion = getKernelVersion()
        val isManager = try {
            Natives.isManager
        } catch (_: Throwable) {
            false
        }
        val ksuVersion = if (isManager) {
            try {
                Natives.version
            } catch (_: Throwable) {
                null
            }
        } else null
        val lkmMode = ksuVersion?.let {
            if (kernelVersion.isGKI()) {
                try {
                    Natives.isLkmMode
                } catch (_: Throwable) {
                    null
                }
            } else null
        }
        val isRootAvailable = try {
            rootAvailable()
        } catch (_: Throwable) {
            false
        }
        val managerVersion = getManagerVersion(ksuApp)

        return HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            lkmMode = lkmMode,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = try { Natives.isPrBuild } catch (_: Throwable) { false },
            requiresNewKernel = isManager && try { Natives.requireNewKernel() } catch (_: Throwable) { false },
            isRootAvailable = isRootAvailable,
            isSafeMode = try { Natives.isSafeMode } catch (_: Throwable) { false },
            isLateLoadMode = try { Natives.isLateLoadMode } catch (_: Throwable) { false },
            checkUpdateEnabled = ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("check_update", true),
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = managerVersion.versionCode,
            superuserCount = getSuperuserCount(),
            moduleCount = getModuleCount(),
            systemInfo = SystemInfo(
                kernelVersion = Os.uname().release,
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode})",
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = getSELinuxStatusRaw(),
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
        )
    }
}

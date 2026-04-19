package com.furkn.vpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.furkn.vpn.api.ApiClient
import com.furkn.vpn.api.BootstrapEntry
import com.furkn.vpn.api.LoginRequest
import com.furkn.vpn.api.RequestSmsRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {

    private fun loadExcludedPackagesFromPrefs(context: android.content.Context): List<String> {
        val prefs = context.getSharedPreferences("furkn_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getStringSet("excluded_packages", emptySet())
            ?.toList()
            ?.filter { it.isNotBlank() }
            ?.sorted()
            ?: emptyList()
    }

    private fun saveExcludedPackagesToPrefs(context: android.content.Context, packages: List<String>) {
        val prefs = context.getSharedPreferences("furkn_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("excluded_packages", packages.toSet())
            .apply()
    }

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun updatePhone(value: String) {
        _state.value = _state.value.copy(phone = value)
    }

    fun updateCode(value: String) {
        _state.value = _state.value.copy(code = value)
    }


    fun openTerpilyScreen(context: android.content.Context) {
        val savedExcluded = loadExcludedPackagesFromPrefs(context)

        _state.value = _state.value.copy(
            excludedPackages = savedExcluded,
            currentScreen = "terpily"
        )

        loadInstalledApps(context)
    }

    fun backToMain() {
        _state.value = _state.value.copy(currentScreen = "main")
    }

    fun addExcludedPackage(context: android.content.Context, packageName: String) {
        if (_state.value.excludedPackages.any { it.equals(packageName, ignoreCase = true) }) {
            return
        }

        val updated = (_state.value.excludedPackages + packageName).distinct().sorted()

        saveExcludedPackagesToPrefs(context, updated)

        _state.value = _state.value.copy(
            excludedPackages = updated,
            installedApps = _state.value.installedApps.map {
                if (it.packageName == packageName) it.copy(isChecked = true) else it
            }
        )
    }

    fun removeExcludedPackage(context: android.content.Context, packageName: String) {
        val updated = _state.value.excludedPackages
            .filterNot { it.equals(packageName, ignoreCase = true) }
            .sorted()

        saveExcludedPackagesToPrefs(context, updated)

        _state.value = _state.value.copy(
            excludedPackages = updated,
            installedApps = _state.value.installedApps.map {
                if (it.packageName == packageName) it.copy(isChecked = false) else it
            }
        )
    }

    fun backToLogin() {
        _state.value = _state.value.copy(
            currentScreen = "login",
            code = "",
            isLoading = false,
            message = null
        )
    }

    fun sendCode() {
        val phone = _state.value.phone.trim()

        if (phone.isBlank()) {
            _state.value = _state.value.copy(message = "Введи цифры")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                message = null
            )

            try {
                val response = ApiClient.api.requestSms(
                    RequestSmsRequest(
                        phone = phone,
                        device_id = "android-emulator-001",
                        platform = "android",
                        app_version = "1.0"
                    )
                )

                if (response.ok) {
                    _state.value = _state.value.copy(
                        currentScreen = "code",
                        isLoading = false,
                        message = null
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = response.message ?: response.error ?: "Не вышло"
                    )
                }

            } catch (e: Exception) {
                val msg = e.message ?: ""

                val userMessage = when {
                    msg.contains("429") -> "Покури пока"
                    msg.contains("timeout", ignoreCase = true) -> "Батя накосячил"
                    msg.contains("Unable to resolve host", ignoreCase = true) -> "На нас вышли пидоры"
                    else -> "Че за нах"
                }

                _state.value = _state.value.copy(
                    isLoading = false,
                    message = userMessage
                )
            }
        }
    }

    fun verifyCode() {
        val phone = _state.value.phone.trim()
        val code = _state.value.code.trim()

        if (phone.isBlank()) {
            _state.value = _state.value.copy(message = "Введи цифры")
            return
        }

        if (code.isBlank()) {
            _state.value = _state.value.copy(message = "Введи код")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                message = null
            )

            try {
                val response = ApiClient.api.login(
                    LoginRequest(
                        phone = phone,
                        code = code
                    )
                )

                if (response.ok && !response.login_token.isNullOrBlank()) {
                    _state.value = _state.value.copy(
                        currentScreen = "main",
                        loginToken = response.login_token,
                        selectedTransport = "loading bootstrap...",
                        selectedHost = null,
                        selectedPort = null,
                        selectedEntryId = null,
                        selectedAuth = null,
                        isLoading = false,
                        message = null
                    )

                    loadBootstrap()
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = response.message ?: response.error ?: "Не угадал"
                    )
                }

            } catch (e: Exception) {
                val msg = e.message ?: ""

                val userMessage = when {
                    msg.contains("429") -> "Покури пока"
                    msg.contains("timeout", ignoreCase = true) -> "Батя накосячил"
                    msg.contains("Unable to resolve host", ignoreCase = true) -> "На нас вышли пидоры"
                    else -> "Че за нах"
                }

                _state.value = _state.value.copy(
                    isLoading = false,
                    message = userMessage
                )
            }
        }
    }

    fun loadBootstrap() {
        val token = _state.value.loginToken ?: return

        viewModelScope.launch {
            try {
                val response = ApiClient.api.bootstrap(token)
                val entries = response.entries ?: emptyList()

                val selected = pickBestEntry(entries)

                _state.value = _state.value.copy(
                    bootstrapEntries = entries,
                    selectedTransport = selected?.transportCode ?: "no transports",
                    selectedHost = selected?.host,
                    selectedPort = selected?.port,
                    selectedEntryId = selected?.entryId,
                    selectedAuth = selected?.auth,
                    message = null
                )

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    message = "Bootstrap error: ${e.message}"
                )
            }
        }
    }

    private data class SelectedBootstrap(
        val entryId: String?,
        val host: String?,
        val port: Int?,
        val transportCode: String?,
        val auth: String?
    )

    private fun pickBestEntry(entries: List<BootstrapEntry>): SelectedBootstrap? {
        if (entries.isEmpty()) return null

        val priority = listOf(
            "xhttp_reality",
            "vless_reality",
            "hysteria2",
            "hy2",
            "hy2_salamander",
            "h2_salamander"
        )

        for (preferredCode in priority) {
            for (entry in entries) {
                val matchedTransport = entry.transports
                    ?.firstOrNull { it.code.equals(preferredCode, ignoreCase = true) }

                if (matchedTransport != null) {
                    return SelectedBootstrap(
                        entryId = null,
                        host = entry.host,
                        port = entry.port,
                        transportCode = matchedTransport.code,
                        auth = matchedTransport.connect_material?.auth
                    )
                }
            }
        }

        val firstEntry = entries.firstOrNull() ?: return null
        val firstTransport = firstEntry.transports?.firstOrNull()

        return SelectedBootstrap(
            entryId = null,
            host = firstEntry.host,
            port = firstEntry.port,
            transportCode = firstTransport?.code,
            auth = firstTransport?.connect_material?.auth
        )
    }

    fun loadInstalledApps(context: android.content.Context) {
        val pm = context.packageManager
        val savedExcluded = loadExcludedPackagesFromPrefs(context)

        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }

        val launchablePackages = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()

        val apps = pm.getInstalledApplications(0)
            .asSequence()
            .filter { appInfo ->
                val isSystemApp =
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                val hasLauncher = launchablePackages.contains(appInfo.packageName)

                !isSystemApp && hasLauncher
            }
            .mapNotNull { appInfo ->
                val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty()
                val packageName = appInfo.packageName

                if (label.isBlank()) {
                    null
                } else {
                    InstalledAppItem(
                        label = label,
                        packageName = packageName,
                        isChecked = savedExcluded.contains(packageName),
                        isSuggested = isSuggestedApp(packageName)
                    )
                }
            }
            .sortedBy { it.label.lowercase() }
            .toList()

        _state.value = _state.value.copy(
            excludedPackages = savedExcluded,
            installedApps = apps
        )
    }

    private fun isSuggestedApp(packageName: String): Boolean {
        val suggested = listOf(
            "ru.sberbankmobile",
            "ru.vtb24.mobilebanking.android",
            "ru.alfabank.mobile.android",
            "ru.raiffeisennews",
            "com.idamob.tinkoff.android",
            "ru.ozon.app.android",
            "ru.wildberries",
            "ru.gosuslugi.portal"
        )

        return suggested.any { it.equals(packageName, ignoreCase = true) }
    }

    fun markConnected() {
        _state.value = _state.value.copy(
            isConnected = true,
            message = null
        )
    }

    fun disconnect() {
        _state.value = _state.value.copy(
            isConnected = false,
            message = null
        )
    }

    fun logout() {
        _state.value = AppState()
    }
}
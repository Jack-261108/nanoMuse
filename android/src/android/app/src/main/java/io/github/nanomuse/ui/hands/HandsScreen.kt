package io.github.nanomuse.ui.hands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import io.github.nanomuse.hands.AdbKeyboardHelper
import io.github.nanomuse.hands.Hands
import io.github.nanomuse.shell.SharedShell
import io.github.nanomuse.shell.setup.BuiltinShellFailure
import io.github.nanomuse.shell.setup.BuiltinShellFix
import io.github.nanomuse.shell.setup.BuiltinShellManager
import io.github.nanomuse.shell.setup.BuiltinShellStatus
import io.github.nanomuse.shell.setup.DeveloperSettings
import io.github.nanomuse.ui.home.MuseTones
import kotlinx.coroutines.launch

const val ROUTE_HANDS = "nanomuse/hands"

/**
 * Settings → Hands. The switch (off by default), the three things the hands need — the
 * accessibility service, drawing over other apps, a model that sees pictures — each with
 * the button that fixes it, the screen model (a row that opens the picker of Settings →
 * Models), and the plain words on what the hands will and will not do.
 */
@Composable
fun HandsScreen(onBack: () -> Unit, onOpenProviders: () -> Unit, onOpenHandsModel: () -> Unit = onOpenProviders) {
    val context = LocalContext.current
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull
    val config = repo?.config?.collectAsState()?.value
    // Follows the preference itself, so a change made elsewhere while this page is open shows here.
    val enabled by remember(context) { Hands.enabledFlow(context) }.collectAsState(initial = Hands.enabled(context))
    var tick by remember { mutableIntStateOf(0) }
    val active by Hands.active.collectAsState()

    // Permissions are granted in other apps; re-read when the user comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val readiness = remember(tick, config) { Hands.readiness(context) }
    val visionEntries = remember(config) { Hands.visionEntries(context) }

    SettingsScaffold(title = stringResource(R.string.nm_hands_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.nm_hands_intro),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
        )

        SettingsSection(footer = stringResource(R.string.nm_hands_switch_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.nm_hands_switch),
                subtitle = when {
                    active -> stringResource(R.string.nm_hands_status_working)
                    enabled && readiness.ready -> stringResource(R.string.nm_hands_status_ready)
                    enabled -> stringResource(R.string.nm_hands_status_missing)
                    else -> stringResource(R.string.nm_hands_status_off)
                },
                checked = enabled,
                onCheckedChange = { Hands.setEnabled(context, it) },
                icon = Icons.Outlined.PhoneAndroid,
                iconColor = if (enabled) MuseTones.action else MaterialTheme.colorScheme.onSurfaceVariant,
                showDivider = active,
            )
            if (active) {
                SettingsRow(
                    title = stringResource(R.string.nm_hands_stop_run),
                    onClick = { Hands.stopCurrent() },
                    showChevron = false,
                    showDivider = false,
                    titleColor = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ── what it needs ──
        // C11: with no provider that has a model that sees, the footer is the one sentence that says which would
        SettingsSection(
            header = stringResource(R.string.nm_hands_section_needs),
            footer = if (visionEntries.isEmpty()) stringResource(R.string.nm_cap_hands_unavailable) else stringResource(R.string.nm_hands_needs_footer),
        ) {
            NeedRow(
                icon = Icons.Outlined.Accessibility,
                title = stringResource(R.string.nm_hands_need_a11y),
                ok = readiness.serviceOn,
                okText = stringResource(R.string.nm_hands_need_on),
                fixText = stringResource(R.string.nm_hands_need_a11y_fix),
                onFix = { openAccessibilitySettings(context) },
            )
            NeedRow(
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.nm_hands_need_overlay),
                ok = readiness.overlayOk,
                okText = stringResource(R.string.nm_hands_need_granted),
                fixText = stringResource(R.string.nm_hands_need_overlay_fix),
                onFix = { openOverlaySettings(context) },
            )
            NeedRow(
                icon = Icons.Outlined.Visibility,
                title = stringResource(R.string.nm_hands_need_model),
                ok = readiness.model != null,
                // the model and how it was arrived at, so "why qwen-vl-max?" has its answer here
                okText = readiness.model?.let { m -> m.label + " · " + stringResource(whyText(m.why)) } ?: "",
                fixText = stringResource(R.string.nm_hands_need_model_fix),
                onFix = onOpenProviders,
                showDivider = true,
            )
            NeedRow(
                icon = Icons.Outlined.Keyboard,
                title = stringResource(R.string.nm_hands_need_ime),
                ok = readiness.keyboardEnhanced,
                okText = stringResource(R.string.nm_hands_need_ime_ready),
                fixText = stringResource(R.string.nm_hands_need_ime_off),
                trailingText = stringResource(if (readiness.keyboardEnhanced) R.string.nm_hands_need_ok else R.string.nm_hands_need_optional),
                onFix = { AdbKeyboardHelper.guideSetup(context) },
                showDivider = !readiness.androidOk,
            )
            if (!readiness.androidOk) {
                SettingsRow(
                    title = stringResource(R.string.nm_hands_need_android),
                    subtitle = stringResource(R.string.nm_hands_need_android_sub),
                    showDivider = false,
                )
            }
        }

        // ── the screen model ── one row; the choice itself is the picker of Settings → Models
        // (0.1.41), so the hands' model has one place, not two.
        if (visionEntries.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.nm_hands_section_model), footer = stringResource(R.string.nm_hands_model_footer)) {
                SettingsRow(
                    title = stringResource(R.string.nm_models_hands_title),
                    subtitle = readiness.model?.let { m -> m.label + " · " + stringResource(whyText(m.why)) },
                    onClick = onOpenHandsModel,
                    showDivider = false,
                )
            }
        }

        // ── virtual screen ──
        val virtualScreenEnabled by remember(context) { Hands.virtualScreenEnabledFlow(context) }.collectAsState(initial = Hands.virtualScreenEnabled(context))
        val shellInstance by SharedShell.shell.collectAsState()
        val builtin = remember(context) { BuiltinShellManager.instance(context) }
        val builtinStatus by builtin.status.collectAsState()
        val scanningPorts by builtin.scanningPorts.collectAsState()
        val scope = rememberCoroutineScope()

        val virtualScreenSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val isRepairNeeded = builtinStatus is BuiltinShellStatus.Failed && (builtinStatus as BuiltinShellStatus.Failed).fix == BuiltinShellFix.RePair
        var confirmUnpair by remember { mutableStateOf(false) }
        var showPairingGuide by remember { mutableStateOf(false) }

        if (confirmUnpair) {
            AlertDialog(
                onDismissRequest = { confirmUnpair = false },
                title = { Text(stringResource(R.string.nm_hands_virtual_screen_unpair_dialog_title)) },
                text = { Text(stringResource(R.string.nm_hands_virtual_screen_unpair_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUnpair = false
                        scope.launch { builtin.unpair() }
                    }) {
                        Text(stringResource(R.string.nm_hands_virtual_screen_unpair_confirm), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUnpair = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        val guideToastText = stringResource(R.string.nm_hands_virtual_screen_pair_guide_toast)
        if (showPairingGuide) {
            AlertDialog(
                onDismissRequest = { showPairingGuide = false },
                title = { Text(stringResource(R.string.nm_hands_virtual_screen_pair_guide_title)) },
                text = { Text(stringResource(R.string.nm_hands_virtual_screen_pair_guide_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showPairingGuide = false
                        if (isRepairNeeded) {
                            builtin.coordinator.startRepair()
                        }
                        builtin.coordinator.begin()
                        android.widget.Toast.makeText(context, guideToastText, android.widget.Toast.LENGTH_LONG).show()
                        val intents = DeveloperSettings.wirelessDebuggingPage()
                        for (intent in intents) {
                            if (runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                                break
                            }
                        }
                    }) {
                        Text(stringResource(R.string.nm_hands_virtual_screen_pair_guide_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPairingGuide = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        SettingsSection(
            header = stringResource(R.string.nm_hands_section_virtual_screen),
            footer = stringResource(R.string.nm_hands_virtual_screen_footer),
        ) {
            val status = builtinStatus
            val failedReason = (status as? BuiltinShellStatus.Failed)?.reason
            val isNoWifi = failedReason == BuiltinShellFailure.NoWifi
            val isOff = status is BuiltinShellStatus.WirelessDebuggingOff || failedReason == BuiltinShellFailure.WifiNotAllowed
            val isRetry = status is BuiltinShellStatus.Failed && status.fix == BuiltinShellFix.Retry
            val isCleanupPending = failedReason == BuiltinShellFailure.CleanupPending
            val failureText = when (failedReason) {
                BuiltinShellFailure.CleanupPending -> R.string.nm_hands_virtual_screen_cleanup_pending
                BuiltinShellFailure.BackendUnavailable -> R.string.nm_hands_virtual_screen_backend_unavailable
                BuiltinShellFailure.RootDenied -> R.string.nm_hands_virtual_screen_root_denied
                else -> null
            }

            SettingsSwitchRow(
                title = stringResource(R.string.nm_hands_virtual_screen_title),
                subtitle = when {
                    !virtualScreenSupported -> stringResource(R.string.nm_hands_virtual_screen_requires_android12)
                    failureText != null -> stringResource(failureText)
                    shellInstance != null -> stringResource(R.string.nm_hands_virtual_screen_ready)
                    status is BuiltinShellStatus.Connecting -> stringResource(if (scanningPorts) R.string.nm_hands_virtual_screen_scanning_port else R.string.nm_hands_virtual_screen_connecting)
                    isOff -> stringResource(R.string.nm_hands_virtual_screen_wireless_off)
                    isRepairNeeded -> stringResource(R.string.nm_hands_virtual_screen_repair)
                    isNoWifi -> stringResource(R.string.nm_hands_virtual_screen_no_wifi)
                    isRetry -> stringResource(R.string.nm_hands_virtual_screen_retry_helper)
                    else -> stringResource(R.string.nm_hands_virtual_screen_not_started)
                },
                checked = virtualScreenEnabled,
                enabled = !isCleanupPending && (virtualScreenSupported || virtualScreenEnabled),
                onCheckedChange = {
                    if (!it || virtualScreenSupported) Hands.setVirtualScreenEnabled(context, it)
                },
                icon = Icons.Outlined.Layers,
                iconColor = if (virtualScreenEnabled && shellInstance != null) MuseTones.action else MaterialTheme.colorScheme.onSurfaceVariant,
                showDivider = true,
            )

            if (virtualScreenSupported && isCleanupPending) {
                SettingsRow(
                    title = stringResource(R.string.nm_hands_virtual_screen_retry_helper),
                    subtitle = stringResource(R.string.nm_hands_virtual_screen_cleanup_pending),
                    onClick = { scope.launch { builtin.retry() } },
                    showDivider = false,
                )
            } else if (virtualScreenSupported && shellInstance == null) {
                val shizukuSnapshot by ShizukuManager.snapshot.collectAsState()
                if (!builtin.paired || shizukuSnapshot.state == ShizukuManager.State.READY) {
                    SettingsRow(
                        title = stringResource(R.string.nm_hands_virtual_screen_start_helper),
                        subtitle = stringResource(R.string.nm_hands_virtual_screen_start_sub),
                        onClick = {
                            scope.launch { builtin.retry() }
                        },
                        showDivider = true,
                    )
                }

                if (!builtin.paired || isRepairNeeded) {
                    SettingsRow(
                        title = stringResource(if (isRepairNeeded) R.string.nm_hands_virtual_screen_repair else R.string.nm_hands_virtual_screen_pair_wireless),
                        subtitle = stringResource(if (isRepairNeeded) R.string.nm_hands_virtual_screen_repair_sub else R.string.nm_hands_virtual_screen_pair_wireless_sub),
                        onClick = {
                            showPairingGuide = true
                        },
                        showDivider = true,
                    )
                } else {
                    SettingsRow(
                        title = when {
                            isNoWifi -> stringResource(R.string.nm_hands_virtual_screen_no_wifi)
                            isOff -> stringResource(R.string.nm_hands_virtual_screen_wireless_off)
                            isRetry -> stringResource(R.string.nm_hands_virtual_screen_retry_helper)
                            status is BuiltinShellStatus.Connecting -> stringResource(if (scanningPorts) R.string.nm_hands_virtual_screen_scanning_port else R.string.nm_hands_virtual_screen_connecting)
                            else -> stringResource(R.string.nm_hands_virtual_screen_start_helper)
                        },
                        subtitle = when {
                            isNoWifi -> stringResource(R.string.nm_hands_virtual_screen_no_wifi_sub)
                            isOff -> stringResource(R.string.nm_hands_virtual_screen_wireless_off_sub)
                            failureText != null -> stringResource(failureText)
                            isRetry -> stringResource(R.string.nm_hands_virtual_screen_start_wireless_sub)
                            else -> stringResource(R.string.nm_hands_virtual_screen_start_wireless_sub)
                        },
                        onClick = {
                            when {
                                isNoWifi -> {
                                    val intents = DeveloperSettings.wifiPage()
                                    for (intent in intents) {
                                        if (runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                                            break
                                        }
                                    }
                                }
                                isOff -> {
                                    val intents = DeveloperSettings.wirelessDebuggingPage()
                                    for (intent in intents) {
                                        if (runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                                            break
                                        }
                                    }
                                    scope.launch { builtin.retry() }
                                }
                                else -> {
                                    scope.launch { builtin.retry() }
                                }
                            }
                        },
                        showDivider = true,
                    )
                }

                SettingsRow(
                    title = stringResource(R.string.nm_hands_virtual_screen_copy_cmd),
                    subtitle = stringResource(R.string.nm_hands_virtual_screen_copy_cmd_sub),
                    onClick = {
                        val cmd = SharedShell.getAdbCommand(context)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb_cmd", cmd))
                        android.widget.Toast.makeText(context, R.string.nm_sysfile_copied, android.widget.Toast.LENGTH_LONG).show()
                    },
                    showDivider = builtin.paired,
                )

                if (builtin.paired) {
                    SettingsRow(
                        title = stringResource(R.string.nm_hands_virtual_screen_unpair),
                        subtitle = stringResource(R.string.nm_hands_virtual_screen_auto_recovery_sub),
                        onClick = {
                            confirmUnpair = true
                        },
                        showDivider = false,
                    )
                }
            } else if (virtualScreenSupported) {
                if (builtin.paired) {
                    SettingsRow(
                        title = stringResource(R.string.nm_hands_virtual_screen_unpair),
                        subtitle = stringResource(R.string.nm_hands_virtual_screen_auto_recovery_sub),
                        onClick = {
                            confirmUnpair = true
                        },
                        showDivider = false,
                    )
                } else {
                    SettingsRow(
                        title = stringResource(R.string.nm_hands_virtual_screen_stop_helper),
                        subtitle = stringResource(R.string.nm_hands_virtual_screen_stop_sub),
                        onClick = {
                            scope.launch { builtin.stop() }
                        },
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.nm_hands_virtual_screen_pair_wireless),
                        subtitle = stringResource(R.string.nm_hands_virtual_screen_pair_wireless_sub),
                        onClick = {
                            showPairingGuide = true
                        },
                        showDivider = false,
                    )
                }
            }
        }

        // ── the rules ──
        SettingsSection(header = stringResource(R.string.nm_hands_section_rules)) {
            RuleRow(stringResource(R.string.nm_hands_rule_ladder))
            RuleRow(stringResource(R.string.nm_hands_rule_secrets))
            RuleRow(stringResource(R.string.nm_hands_rule_approvals))
            RuleRow(stringResource(R.string.nm_hands_rule_stop))
            RuleRow(stringResource(R.string.nm_hands_rule_screenshots), last = true)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NeedRow(
    icon: ImageVector,
    title: String,
    ok: Boolean,
    okText: String,
    fixText: String,
    onFix: () -> Unit,
    showDivider: Boolean = true,
    trailingText: String? = null,
) {
    SettingsRow(
        title = title,
        subtitle = if (ok) okText else fixText,
        icon = icon,
        iconColor = if (ok) MuseTones.action else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = if (ok) null else onFix,
        showChevron = false,
        showDivider = showDivider,
        minHeight = 64.dp,
        trailing = {
            Text(
                text = trailingText ?: stringResource(if (ok) R.string.nm_hands_need_ok else R.string.nm_hands_need_set_up),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (ok || trailingText != null) MaterialTheme.colorScheme.onSurfaceVariant else MuseTones.action,
            )
        },
    )
}

@Composable
private fun RuleRow(text: String, last: Boolean = false) {
    SettingsRow(title = text, showDivider = !last, minHeight = 48.dp)
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The sentence for how the screen model was arrived at ([Hands.screenModel]). */
private fun whyText(why: Hands.Why): Int = when (why) {
    Hands.Why.CHOSEN -> R.string.nm_hands_why_chosen
    Hands.Why.CHAT_PROVIDER -> R.string.nm_hands_why_chat_provider
    Hands.Why.DEFAULT -> R.string.nm_hands_why_default
    Hands.Why.CHAT -> R.string.nm_hands_why_chat
    Hands.Why.GROUP -> R.string.nm_hands_why_group
    Hands.Why.MENU -> R.string.nm_hands_why_menu
    Hands.Why.VISION_GROUP -> R.string.nm_hands_why_vision_group
    Hands.Why.ANY -> R.string.nm_hands_why_any
}

private fun openOverlaySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Throwable) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

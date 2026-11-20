package io.github.nanomuse.ui.connectors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.email.EmailAuth
import io.github.nanomuse.email.EmailConfig
import io.github.nanomuse.email.EmailConnector
import io.github.nanomuse.email.EmailSaveResult
import io.github.nanomuse.email.EmailStore
import io.github.nanomuse.email.EmailTokens
import io.github.nanomuse.email.MailHelpKind
import io.github.nanomuse.email.MailPreset
import io.github.nanomuse.email.MailPresets
import io.github.nanomuse.email.MicrosoftOAuth
import io.github.nanomuse.oauth.OAuthCallbackRescue
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.muse.MuseCaption
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseGap
import io.github.nanomuse.ui.muse.MuseRow
import io.github.nanomuse.ui.muse.MuseRowDivider
import io.github.nanomuse.ui.muse.MuseSectionLabel
import io.github.nanomuse.ui.muse.MuseTopAppBar
import io.github.nanomuse.ui.muse.SecretEye
import io.github.nanomuse.ui.muse.secretTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

const val ROUTE_EMAIL_CONNECTOR = "nanomuse/connectors/email"

/**
 * Settings → Connectors → Email: the mailboxes the native agent reads and sends from.
 *
 * The list holds one row per mailbox, with a switch to pause or resume verified ones; tapping
 * a row opens the form. "Save and test" writes the account, then logs in to IMAP and SMTP for
 * real; only a successful test turns that mailbox on. Every authorization code or OAuth token
 * goes to EncryptedSharedPreferences and nowhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailConnectorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { EmailStore.forContext(context) }
    val storeVersion by EmailStore.version.collectAsState()
    val accounts = remember(storeVersion) { store.accounts() }

    var editing by remember { mutableStateOf<EmailConfig?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { MicrosoftOAuth.cancel() }
    }

    val editingId = editing?.id.orEmpty()
    var formKey by remember { mutableStateOf(0) }
    var accountId by remember(formKey) {
        mutableStateOf(editing?.id?.ifBlank { UUID.randomUUID().toString() } ?: UUID.randomUUID().toString())
    }
    var label by remember(formKey) { mutableStateOf(editing?.label.orEmpty()) }
    var address by remember(formKey) { mutableStateOf(editing?.address.orEmpty()) }
    var code by remember(formKey) { mutableStateOf("") }
    var showCode by remember(formKey) { mutableStateOf(false) }
    var imapHost by remember(formKey) { mutableStateOf(editing?.imapHost.orEmpty()) }
    var imapPort by remember(formKey) { mutableStateOf((editing?.imapPort ?: 993).toString()) }
    var smtpHost by remember(formKey) { mutableStateOf(editing?.smtpHost.orEmpty()) }
    var smtpPort by remember(formKey) {
        mutableStateOf((editing?.smtpPort ?: if (editing?.smtpStarttls != false) 587 else 465).toString())
    }
    var starttls by remember(formKey) { mutableStateOf(editing?.smtpStarttls ?: true) }
    var hasCode by remember(formKey) { mutableStateOf(editing?.hasCode == true) }
    val isOutlookAccount = editing?.imapHost.orEmpty().equals("outlook.office365.com", ignoreCase = true) ||
        MailPresets.forAddress(editing?.address.orEmpty())?.auth == EmailAuth.MICROSOFT
    val initialAuth = if (isOutlookAccount) {
        EmailAuth.MICROSOFT
    } else {
        editing?.auth ?: EmailAuth.PASSWORD
    }
    var auth by remember(formKey) { mutableStateOf(initialAuth) }
    var clientId by remember(formKey) {
        mutableStateOf(
            editing?.clientId?.ifBlank {
                if (initialAuth == EmailAuth.MICROSOFT) MicrosoftOAuth.DEFAULT_CLIENT_ID else ""
            }?.ifEmpty {
                if (initialAuth == EmailAuth.MICROSOFT) MicrosoftOAuth.DEFAULT_CLIENT_ID else ""
            } ?: if (initialAuth == EmailAuth.MICROSOFT) MicrosoftOAuth.DEFAULT_CLIENT_ID else "",
        )
    }
    var pendingTokens by remember(formKey) { mutableStateOf<EmailTokens?>(null) }
    var signedIn by remember(formKey) { mutableStateOf(editing?.hasToken == true) }
    var autoMatchedPresetId by remember(formKey) {
        val matched = MailPresets.forAddress(editing?.address.orEmpty())
        mutableStateOf(if (matched != null && matched.imapHost.equals(editing?.imapHost.orEmpty(), ignoreCase = true)) matched.id else null)
    }
    var signingIn by remember { mutableStateOf(false) }

    fun applyPresetFields(preset: MailPreset, fromAddressTyping: Boolean) {
        imapHost = preset.imapHost
        imapPort = preset.imapPort.toString()
        smtpHost = preset.smtpHost
        smtpPort = preset.smtpPort.toString()
        starttls = preset.smtpStarttls
        auth = preset.auth
        if (preset.auth == EmailAuth.MICROSOFT && clientId.isBlank()) {
            clientId = MicrosoftOAuth.DEFAULT_CLIENT_ID
        }
        autoMatchedPresetId = if (fromAddressTyping) preset.id else null
    }

    fun open(account: EmailConfig) {
        MicrosoftOAuth.cancel()
        signingIn = false
        message = null
        editing = account
        formKey++
    }

    fun closeEditor() {
        MicrosoftOAuth.cancel()
        signingIn = false
        editing = null
        message = null
    }

    // The system back key returns from the form to the list before it leaves the screen.
    BackHandler(enabled = editing != null) {
        closeEditor()
    }

    fun applyPreset(id: String) {
        val preset = MailPresets.all.firstOrNull { it.id == id } ?: return
        applyPresetFields(preset, fromAddressTyping = false)
    }

    fun onAddressChanged(next: String) {
        val trimmed = next.trim()
        address = trimmed
        val matched = MailPresets.forAddress(trimmed)
        if (matched != null) {
            val currentMatchesPreset = MailPresets.all.any { it.imapHost.equals(imapHost, ignoreCase = true) }
            val canAutoFill = (imapHost.isBlank() && smtpHost.isBlank()) || autoMatchedPresetId != null || currentMatchesPreset
            if (canAutoFill && (matched.id != autoMatchedPresetId || auth != matched.auth)) {
                applyPresetFields(matched, fromAddressTyping = true)
            }
        }
    }

    fun signIn() {
        if (signingIn || busy) return
        val id = clientId.trim().ifBlank { MicrosoftOAuth.DEFAULT_CLIENT_ID }
        if (id.isEmpty()) {
            messageOk = false
            message = context.getString(R.string.nm_connectors_email_microsoft_client_id_first)
            return
        }
        signingIn = true
        message = null
        scope.launch {
            val result = MicrosoftOAuth.authorize(context, id)
            signingIn = false
            val tokens = result.tokens
            if (!result.ok || tokens == null) {
                messageOk = false
                message = context.getString(R.string.nm_connectors_email_microsoft_failed, result.message)
                return@launch
            }
            pendingTokens = tokens
            signedIn = true
            if (address.isBlank() && !result.preferredAddress.isNullOrBlank()) {
                onAddressChanged(result.preferredAddress)
            }
            messageOk = true
            message = context.getString(R.string.nm_connectors_email_microsoft_signed_in)
        }
    }

    fun saveAndTest() {
        val base = editing ?: return
        if (busy) return
        if (auth == EmailAuth.MICROSOFT && !signedIn) {
            messageOk = false
            message = context.getString(R.string.nm_connectors_email_microsoft_sign_in_first)
            return
        }
        val account = base.copy(
            id = accountId,
            label = label,
            enabled = true,
            verified = false,
            address = address,
            imapHost = imapHost,
            imapPort = imapPort.toIntOrNull() ?: 993,
            smtpHost = smtpHost,
            smtpPort = smtpPort.toIntOrNull() ?: if (starttls) 587 else 465,
            smtpStarttls = starttls,
            auth = auth,
            clientId = clientId,
            hasCode = auth == EmailAuth.PASSWORD && (hasCode || code.isNotBlank()),
            hasToken = auth == EmailAuth.MICROSOFT && (signedIn || pendingTokens != null),
        )
        if (account.issues().isNotEmpty()) {
            messageOk = false
            message = context.getString(R.string.nm_connectors_email_required)
            return
        }
        busy = true
        message = null
        scope.launch {
            val codeToSave = if (auth == EmailAuth.PASSWORD) code.ifBlank { null } else null
            val tokensToSave = if (auth == EmailAuth.MICROSOFT) pendingTokens else null
            val saved = withContext(Dispatchers.IO) { store.save(account, codeToSave, tokensToSave) }
            if (saved == EmailSaveResult.ENCRYPTION_UNAVAILABLE) {
                busy = false
                messageOk = false
                message = context.getString(R.string.nm_connectors_email_encryption_unavailable)
                return@launch
            }
            pendingTokens = null
            if (auth == EmailAuth.PASSWORD && codeToSave != null) {
                hasCode = true
            }
            val result = withContext(Dispatchers.IO) { EmailConnector.test(store, account.id) }
            store.setVerified(account.id, result.ok)
            busy = false
            messageOk = result.ok
            if (result.ok) {
                if (auth == EmailAuth.PASSWORD) {
                    code = ""
                }
                editing = null
                message = context.getString(R.string.nm_connectors_email_test_ok)
            } else {
                // Keep the saved draft attached to editing so retrying or disconnecting works cleanly.
                editing = store.account(account.id)
                message = context.getString(R.string.nm_connectors_email_test_failed, result.message)
            }
        }
    }

    fun remove() {
        MicrosoftOAuth.cancel()
        signingIn = false
        val id = editing?.id?.ifBlank { null }
        if (id != null) {
            store.remove(id)
        }
        pendingTokens = null
        editing = null
        messageOk = true
        message = context.getString(R.string.nm_connectors_email_disconnected)
    }

    val title = when {
        editing == null -> stringResource(R.string.nm_connectors_email_title)
        editingId.isBlank() -> stringResource(R.string.nm_connectors_email_add)
        else -> editing?.displayLabel ?: stringResource(R.string.nm_connectors_email_title)
    }
    val goBack = {
        if (editing != null) {
            closeEditor()
        } else {
            MicrosoftOAuth.cancel()
            onBack()
        }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            if (editing == null) {
                MuseCaption(stringResource(R.string.nm_connectors_email_intro))
                MuseCard {
                    accounts.forEachIndexed { index, account ->
                        if (index > 0) MuseRowDivider(inset = 52.dp)
                        AccountRow(
                            account = account,
                            onClick = { open(account) },
                            onToggle = { enabled -> store.setEnabled(account.id, enabled) },
                        )
                    }
                    if (accounts.isNotEmpty()) MuseRowDivider(inset = 16.dp)
                    MuseRow(
                        title = stringResource(R.string.nm_connectors_email_add),
                        titleColor = MuseTones.action,
                        chevron = true,
                        onClick = { open(EmailConfig()) },
                    )
                }
            } else {
                MuseCaption(stringResource(R.string.nm_connectors_email_intro))
                MuseSectionLabel(stringResource(R.string.nm_connectors_email_presets))
                MuseCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        MailPresets.all.forEachIndexed { index, preset ->
                            if (index > 0) Spacer(Modifier.width(8.dp))
                            val selected = imapHost.equals(preset.imapHost, ignoreCase = true) && auth == preset.auth
                            OutlinedButton(
                                onClick = { applyPreset(preset.id) },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) MuseTones.action else MuseTones.hairline,
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) MuseTones.fill else Color.Transparent,
                                    contentColor = if (selected) {
                                        MuseTones.action
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                ),
                            ) { Text(presetDisplayLabel(preset), fontSize = 14.sp) }
                        }
                    }
                }
                when (MailPresets.helpFor(imapHost, auth)) {
                    MailHelpKind.QQ -> MuseCaption(stringResource(R.string.nm_connectors_email_qq_help))
                    MailHelpKind.NETEASE -> MuseCaption(stringResource(R.string.nm_connectors_email_netease_help))
                    MailHelpKind.GMAIL -> MuseCaption(stringResource(R.string.nm_connectors_email_gmail_help))
                    MailHelpKind.APP_PASSWORD ->
                        MuseCaption(stringResource(R.string.nm_connectors_email_app_password_help))
                    MailHelpKind.MICROSOFT -> Unit
                }

                MuseCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text(stringResource(R.string.nm_connectors_email_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseGap(10.dp)
                        OutlinedTextField(
                            value = address,
                            onValueChange = ::onAddressChanged,
                            label = { Text(stringResource(R.string.nm_connectors_email_address)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseGap(10.dp)
                        if (auth == EmailAuth.PASSWORD) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it },
                                label = { Text(stringResource(R.string.nm_connectors_email_code)) },
                                supportingText = { Text(stringResource(R.string.nm_connectors_email_code_hint)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                visualTransformation = secretTransformation(showCode),
                                trailingIcon = { SecretEye(showCode) { showCode = !showCode } },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Next,
                                ),
                                colors = fieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            OutlinedTextField(
                                value = clientId,
                                onValueChange = { value ->
                                    if (value.trim() != clientId.trim()) {
                                        signedIn = false
                                        pendingTokens = null
                                    }
                                    clientId = value
                                },
                                label = { Text(stringResource(R.string.nm_connectors_email_microsoft_client_id)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                colors = fieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            MuseGap(10.dp)
                            Button(
                                onClick = { if (signingIn) MicrosoftOAuth.cancel() else signIn() },
                                enabled = !busy,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action),
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Text(
                                    if (signingIn) {
                                        stringResource(R.string.cancel)
                                    } else {
                                        stringResource(R.string.nm_connectors_email_microsoft_sign_in)
                                    },
                                )
                            }
                            OAuthCallbackRescue(
                                active = signingIn,
                                target = MicrosoftOAuth.LOOPBACK_TARGET,
                            )
                            if (signedIn) {
                                Text(
                                    stringResource(R.string.nm_connectors_email_microsoft_signed_in),
                                    fontSize = 13.sp,
                                    color = MuseTones.action,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            Text(
                                stringResource(
                                    R.string.nm_connectors_email_microsoft_help,
                                    MicrosoftOAuth.REDIRECT_URI,
                                ),
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    MuseRowDivider(inset = 16.dp)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = imapHost,
                                onValueChange = {
                                    autoMatchedPresetId = null
                                    val nextHost = it.trim()
                                    imapHost = nextHost
                                    if (nextHost.equals("outlook.office365.com", ignoreCase = true) && auth != EmailAuth.MICROSOFT) {
                                        auth = EmailAuth.MICROSOFT
                                        if (clientId.isBlank()) {
                                            clientId = MicrosoftOAuth.DEFAULT_CLIENT_ID
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.nm_connectors_email_imap_host)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                colors = fieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            OutlinedTextField(
                                value = imapPort,
                                onValueChange = {
                                    autoMatchedPresetId = null
                                    imapPort = it.filter(Char::isDigit).take(5)
                                },
                                label = { Text(stringResource(R.string.nm_connectors_email_imap_port)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = fieldColors(),
                                modifier = Modifier.width(110.dp),
                            )
                        }
                        MuseGap(10.dp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = smtpHost,
                                onValueChange = {
                                    autoMatchedPresetId = null
                                    smtpHost = it.trim()
                                },
                                label = { Text(stringResource(R.string.nm_connectors_email_smtp_host)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                colors = fieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            OutlinedTextField(
                                value = smtpPort,
                                onValueChange = {
                                    autoMatchedPresetId = null
                                    smtpPort = it.filter(Char::isDigit).take(5)
                                },
                                label = { Text(stringResource(R.string.nm_connectors_email_smtp_port)) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = fieldColors(),
                                modifier = Modifier.width(110.dp),
                            )
                        }
                    }
                    MuseRowDivider(inset = 16.dp)
                    MuseRow(
                        title = stringResource(R.string.nm_connectors_email_starttls),
                        chevron = false,
                        onClick = {
                            autoMatchedPresetId = null
                            starttls = !starttls
                            smtpPort = if (starttls) "587" else "465"
                        },
                        trailing = {
                            Switch(
                                checked = starttls,
                                onCheckedChange = {
                                    autoMatchedPresetId = null
                                    starttls = it
                                    smtpPort = if (it) "587" else "465"
                                },
                            )
                        },
                    )
                    Text(
                        stringResource(R.string.nm_connectors_email_starttls_hint),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { saveAndTest() },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.nm_connectors_email_save_test))
                    }
                }
                if (editingId.isNotBlank()) {
                    MuseGap(12.dp)
                    MuseCard {
                        MuseRow(
                            title = stringResource(R.string.nm_connectors_email_disconnect),
                            chevron = false,
                            titleColor = MaterialTheme.colorScheme.error,
                            onClick = { remove() },
                        )
                    }
                }
                MuseCaption(stringResource(R.string.nm_connectors_email_direct))
            }

            message?.let { text ->
                Text(
                    text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = if (messageOk) MuseTones.action else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun presetDisplayLabel(preset: MailPreset): String = when (preset.id) {
    "exmail" -> stringResource(R.string.nm_connectors_email_preset_exmail)
    "aliyun" -> stringResource(R.string.nm_connectors_email_preset_aliyun)
    "aliqiye" -> stringResource(R.string.nm_connectors_email_preset_aliqiye)
    "feishu" -> stringResource(R.string.nm_connectors_email_preset_feishu)
    "sina" -> stringResource(R.string.nm_connectors_email_preset_sina)
    else -> preset.label
}

@Composable
private fun AccountRow(
    account: EmailConfig,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val canSwitch = account.verified && account.complete
    val statusText = when {
        canSwitch && account.enabled -> stringResource(R.string.nm_connectors_connected)
        canSwitch -> stringResource(R.string.nm_connectors_email_paused)
        else -> stringResource(R.string.nm_connectors_email_off)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Email,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account.displayLabel,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.label.isNotBlank() && account.address.isNotBlank()) {
                Text(
                    account.address,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            statusText,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canSwitch) {
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = account.enabled,
                onCheckedChange = onToggle,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MuseTones.action,
    unfocusedBorderColor = MuseTones.hairline,
)

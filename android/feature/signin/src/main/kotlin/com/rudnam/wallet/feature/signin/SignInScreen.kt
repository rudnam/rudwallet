package com.rudnam.wallet.feature.signin

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.ui.serverText

@Composable
internal fun SignInDestination(viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SignInScreen(
        state = state,
        actions = viewModel,
        onGoogle = {
            // Google refuses sign-in inside an app's own views: the phone's browser, in a Custom Tab where it can.
            val url = viewModel.googleUrl().toUri()
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url)
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent(Intent.ACTION_VIEW, url))
            }
        },
        language = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'),
        onLanguage = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) },
    )
}

/**
 * Signing in (docs/design, "sign in"): the brand, Google, email and
 * password, and the ways to an account, a forgotten password or the demo.
 */
@Composable
internal fun SignInScreen(
    state: SignInUiState,
    actions: SignInActions,
    onGoogle: () -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
) {
    if (state.startingDemo) {
        Column(
            Modifier.fillMaxSize().background(WalletTheme.colors.ground),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.feature_signin_demo_loading), color = WalletTheme.colors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        return
    }
    Column(
        Modifier.fillMaxSize()
            .background(WalletTheme.colors.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Brand()
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.mode == SignInMode.Forgot) Forgot(state, actions) else Form(state, actions, onGoogle)
        }
        Languages(language, onLanguage)
    }
}

@Composable
private fun Brand() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(
            painterResource(com.rudnam.wallet.core.designsystem.R.drawable.core_designsystem_logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
        )
        Text("RudWallet", style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.feature_signin_tagline), color = WalletTheme.colors.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Notice(notice: SignInNotice?) {
    val (text, good) = when (notice) {
        null -> return
        is SignInNotice.Server -> (notice.message.ifEmpty { null }?.let { serverText(it) } ?: stringResource(R.string.feature_signin_refused)) to false
        SignInNotice.Offline -> stringResource(R.string.feature_signin_offline) to false
        is SignInNotice.Google -> stringResource(
            when (notice.reason) {
                "cancelled" -> R.string.feature_signin_google_cancelled
                "expired" -> R.string.feature_signin_google_expired
                "closed" -> R.string.feature_signin_google_closed
                "unverified" -> R.string.feature_signin_google_unverified
                "linked" -> R.string.feature_signin_google_linked
                "busy" -> R.string.feature_signin_google_busy
                else -> R.string.feature_signin_google_failed
            },
        ) to false
        SignInNotice.Deleted -> stringResource(R.string.feature_signin_deleted) to true
        SignInNotice.ResetSent -> stringResource(R.string.feature_signin_reset_sent) to true
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth()
            .background(if (good) WalletTheme.colors.sunken else WalletTheme.colors.attentionSoft, MaterialTheme.shapes.small)
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = if (good) WalletTheme.colors.text else WalletTheme.colors.attention,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Form(state: SignInUiState, actions: SignInActions, onGoogle: () -> Unit) {
    val signingIn = state.mode == SignInMode.SignIn
    var shown by rememberSaveable { mutableStateOf(false) }
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(if (signingIn) R.string.feature_signin_sign_in else R.string.feature_signin_create_an_account),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Notice(state.notice)
            if (state.options.google) {
                OutlinedButton(onClick = onGoogle, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    GoogleMark()
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.feature_signin_google), color = WalletTheme.colors.text)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = WalletTheme.colors.border)
                    Text(stringResource(R.string.feature_signin_or), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
                    HorizontalDivider(Modifier.weight(1f), color = WalletTheme.colors.border)
                }
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = actions::setEmail,
                label = { Text(stringResource(R.string.feature_signin_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress + ContentType.Username },
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = actions::setPassword,
                label = { Text(stringResource(R.string.feature_signin_password)) },
                supportingText = if (signingIn) null else ({ Text(stringResource(R.string.feature_signin_password_rule)) }),
                singleLine = true,
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { shown = !shown }) {
                        LineIcon(
                            if (shown) WalletIcons.eyeOff else WalletIcons.eye,
                            contentDescription = stringResource(if (shown) R.string.feature_signin_hide_password else R.string.feature_signin_show_password),
                            size = 20.dp,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.submit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = if (signingIn) ContentType.Password else ContentType.NewPassword },
            )
            PrimaryButton(
                stringResource(if (signingIn) R.string.feature_signin_sign_in else R.string.feature_signin_create_account),
                busy = state.busy,
                enabled = state.ready,
                onClick = actions::submit,
            )
            if (signingIn && state.options.reset) {
                TextButton(onClick = { actions.setMode(SignInMode.Forgot) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.feature_signin_forgot))
                }
            }
            TextButton(
                onClick = { actions.setMode(if (signingIn) SignInMode.SignUp else SignInMode.SignIn) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(if (signingIn) R.string.feature_signin_to_sign_up else R.string.feature_signin_to_sign_in)) }
        }
    }
    if (state.options.demo) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.feature_signin_just_looking), color = WalletTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = actions::startDemo, enabled = !state.busy) { Text(stringResource(R.string.feature_signin_try_demo)) }
        }
    }
}

@Composable
private fun Forgot(state: SignInUiState, actions: SignInActions) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.feature_signin_reset_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (state.notice == null) Text(stringResource(R.string.feature_signin_reset_intro), color = WalletTheme.colors.muted)
            Notice(state.notice)
            OutlinedTextField(
                value = state.email,
                onValueChange = actions::setEmail,
                label = { Text(stringResource(R.string.feature_signin_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { actions.submit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
            )
            PrimaryButton(
                stringResource(if (state.notice == SignInNotice.ResetSent) R.string.feature_signin_send_again else R.string.feature_signin_send_link),
                busy = state.busy,
                enabled = state.ready,
                onClick = actions::submit,
            )
            TextButton(onClick = { actions.setMode(SignInMode.SignIn) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.feature_signin_back_to_sign_in))
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = WalletTheme.colors.onAction) else Text(text)
    }
}

/** English or 日本語, each in its own language; the app follows the phone until one is chosen. */
@Composable
private fun Languages(current: String, onChoose: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        LineIcon(WalletIcons.globe, contentDescription = stringResource(R.string.feature_signin_language), tint = WalletTheme.colors.muted, size = 18.dp)
        for ((code, name) in listOf("en" to "English", "ja" to "日本語")) {
            TextButton(onClick = { onChoose(code) }, enabled = current != code) {
                Text(name, color = if (current == code) WalletTheme.colors.text else WalletTheme.colors.muted)
            }
        }
    }
}

/** Google's "G", in its own colours, as Google's branding rules ask. */
@Composable
private fun GoogleMark() {
    Box(Modifier.size(18.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width * 0.2f
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            val topLeft = Offset(inset, inset)
            drawArc(Color(0xFFEA4335), startAngle = -150f, sweepAngle = 105f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(w))
            drawArc(Color(0xFFFBBC05), startAngle = 150f, sweepAngle = 60f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(w))
            drawArc(Color(0xFF34A853), startAngle = 60f, sweepAngle = 90f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(w))
            drawArc(Color(0xFF4285F4), startAngle = -15f, sweepAngle = 75f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(w))
            drawRect(Color(0xFF4285F4), topLeft = Offset(size.width / 2, size.height / 2 - w / 2), size = Size(size.width / 2 - inset / 2, w))
        }
    }
}

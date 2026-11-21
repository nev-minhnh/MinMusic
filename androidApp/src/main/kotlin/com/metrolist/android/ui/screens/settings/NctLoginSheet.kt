package com.metrolist.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.metrolist.android.source.credentials.SourceAccounts
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_login
import com.metrolist.shared.generated.resources.password
import com.metrolist.shared.generated.resources.source_experimental
import com.metrolist.shared.generated.resources.source_hide_password
import com.metrolist.shared.generated.resources.source_nct_login
import com.metrolist.shared.generated.resources.source_nct_login_error
import com.metrolist.shared.generated.resources.source_nct_login_failed
import com.metrolist.shared.generated.resources.source_nct_login_network_error
import com.metrolist.shared.generated.resources.source_nct_login_subtitle
import com.metrolist.shared.generated.resources.source_nct_privacy_password
import com.metrolist.shared.generated.resources.source_nct_privacy_title
import com.metrolist.shared.generated.resources.source_nct_privacy_token
import com.metrolist.shared.generated.resources.source_nct_use_free
import com.metrolist.shared.generated.resources.source_nct_username
import com.metrolist.shared.generated.resources.source_show_password
import com.metrolist.shared.source.nct.NctException
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.TagBadge
import com.metrolist.shared.ui.components.rememberModalSheetState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.utils.BlockScreenshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

// A half-height stop would leave the Log in button below the screen, so the sheet only opens fully.
@OptIn(ExperimentalMaterial3Api::class)
private val FullSheetOnly = setOf(SheetValue.Hidden, SheetValue.Expanded)

/**
 * NhacCuaTui sign-in with username and password. The password stays in a local [TextFieldState], is sent once to
 * NCT and is cleared right after; only the returned token is stored, through [SourceAccounts.nctTokens].
 *
 * The form scrolls while the Log in button stays pinned above the navigation bar and the keyboard. The sheet window
 * itself is FLAG_SECURE, because the activity flag set by [BlockScreenshots] does not cover the sheet's own window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionName")
internal fun NctLoginSheet(
    onLoggedIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    BlockScreenshots()
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    val passwordState = remember { TextFieldState("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(passwordState) { onDispose { passwordState.clearText() } }

    fun submit() {
        if (isLoggingIn || username.isBlank() || passwordState.text.isEmpty()) return
        isLoggingIn = true
        error = null
        val password = passwordState.text.toString()
        passwordState.clearText()
        scope.launch {
            try {
                SourceAccounts.loginNct(username.trim(), password)
                onLoggedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (_: NctException.AuthFailed) {
                error = getString(Res.string.source_nct_login_failed)
            } catch (_: NctException.Network) {
                error = getString(Res.string.source_nct_login_network_error)
            } catch (_: Exception) {
                error = getString(Res.string.source_nct_login_error)
            } finally {
                isLoggingIn = false
            }
        }
    }

    val errorMessage = error
    val brand = LocalBrand.current
    val canSubmit = !isLoggingIn && username.isNotBlank() && passwordState.text.isNotEmpty()
    AdaptiveModalBottomSheet(
        onDismissRequest = { if (!isLoggingIn) onDismiss() },
        sheetState = rememberModalSheetState(enabledValues = FullSheetOnly),
        containerColor = MaterialTheme.colorScheme.surface,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.source_nct_login),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagBadge(text = stringResource(Res.string.source_experimental))
                        Text(
                            text = stringResource(Res.string.source_nct_login_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        error = null
                    },
                    label = { Text(stringResource(Res.string.source_nct_username)) },
                    leadingIcon = { Icon(MetrolistIcons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !isLoggingIn,
                    isError = errorMessage != null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                )
                OutlinedSecureTextField(
                    state = passwordState,
                    label = { Text(stringResource(Res.string.password)) },
                    leadingIcon = { Icon(MetrolistIcons.Rounded.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) MetrolistIcons.Filled.VisibilityOff else MetrolistIcons.Filled.Visibility,
                                contentDescription =
                                    stringResource(
                                        if (passwordVisible) Res.string.source_hide_password else Res.string.source_show_password,
                                    ),
                            )
                        }
                    },
                    enabled = !isLoggingIn,
                    isError = errorMessage != null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    onKeyboardAction = { submit() },
                    textObfuscationMode = if (passwordVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(MetrolistIcons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(Res.string.source_nct_privacy_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = stringResource(Res.string.source_nct_privacy_password),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(Res.string.source_nct_privacy_token),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Pinned footer: the error and the buttons never scroll away or hide behind the keyboard.
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (errorMessage != null) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Button(
                    onClick = { submit() },
                    enabled = canSubmit,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = brand.onGradient,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(CircleShape)
                            .then(if (canSubmit) Modifier.background(brand.gradient) else Modifier),
                ) {
                    if (isLoggingIn) {
                        ContainedLoadingIndicator(Modifier.size(32.dp))
                    } else {
                        Text(stringResource(Res.string.action_login), fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !isLoggingIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.source_nct_use_free))
                }
            }
        }
    }
}

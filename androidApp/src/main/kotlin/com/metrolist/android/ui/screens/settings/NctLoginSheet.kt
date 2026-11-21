package com.metrolist.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.metrolist.android.source.credentials.SourceAccounts
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_login
import com.metrolist.shared.generated.resources.password
import com.metrolist.shared.generated.resources.source_experimental
import com.metrolist.shared.generated.resources.source_nct_login
import com.metrolist.shared.generated.resources.source_nct_login_failed
import com.metrolist.shared.generated.resources.source_nct_login_network_error
import com.metrolist.shared.generated.resources.source_nct_login_subtitle
import com.metrolist.shared.generated.resources.source_nct_privacy_password
import com.metrolist.shared.generated.resources.source_nct_privacy_token
import com.metrolist.shared.generated.resources.source_nct_privacy_title
import com.metrolist.shared.generated.resources.source_nct_use_free
import com.metrolist.shared.generated.resources.source_nct_username
import com.metrolist.shared.source.nct.NctException
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.TagBadge
import com.metrolist.shared.utils.BlockScreenshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * NhacCuaTui sign-in with username and password. The password stays in a local [TextFieldState], is sent once to
 * NCT and is cleared right after; only the returned token is stored, through [SourceAccounts.nctTokens].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
            } catch (_: Exception) {
                error = getString(Res.string.source_nct_login_network_error)
            } finally {
                isLoggingIn = false
            }
        }
    }

    val errorMessage = error
    AdaptiveModalBottomSheet(onDismissRequest = { if (!isLoggingIn) onDismiss() }) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.source_nct_login),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TagBadge(text = stringResource(Res.string.source_experimental))
                }
                Text(
                    text = stringResource(Res.string.source_nct_login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(Res.string.source_nct_username)) },
                singleLine = true,
                enabled = !isLoggingIn,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            )
            OutlinedSecureTextField(
                state = passwordState,
                label = { Text(stringResource(Res.string.password)) },
                enabled = !isLoggingIn,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                onKeyboardAction = { submit() },
                textObfuscationMode = TextObfuscationMode.Hidden,
                isError = errorMessage != null,
                supportingText = if (errorMessage != null) { { Text(errorMessage) } } else null,
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(Res.string.source_nct_privacy_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
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
            Button(
                onClick = { submit() },
                enabled = !isLoggingIn && username.isNotBlank() && passwordState.text.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (isLoggingIn) {
                    ContainedLoadingIndicator(Modifier.size(32.dp))
                } else {
                    Text(stringResource(Res.string.action_login))
                }
            }
            TextButton(
                onClick = onDismiss,
                enabled = !isLoggingIn,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.source_nct_use_free))
            }
        }
    }
}

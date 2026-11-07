package dev.tasky.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.collectAsState
import dev.tasky.android.R
import dev.tasky.android.ui.errorMessage

@Composable
fun OnboardingScreen(vm: OnboardingViewModel) {
    val s by vm.state.collectAsState()
    BackHandler(enabled = s.step != OnboardingStep.Server) {
        vm.goTo(if (s.step == OnboardingStep.Choose) OnboardingStep.Server else OnboardingStep.Choose)
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.step != OnboardingStep.Server) {
            IconButton(onClick = {
                vm.goTo(if (s.step == OnboardingStep.Choose) OnboardingStep.Server else OnboardingStep.Choose)
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
        } else {
            Spacer(Modifier.height(32.dp))
        }
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        when (s.step) {
            OnboardingStep.Server -> ServerStep(s, vm)
            OnboardingStep.Choose -> ChooseStep(s, vm)
            else -> FormStep(s, vm)
        }
        ErrorText(s)
    }
}

@Composable
private fun ErrorText(s: OnboardingState) {
    val text = when {
        s.errorRes != null -> stringResource(s.errorRes)
        s.error is OnboardingViewModel.UnsupportedApi -> stringResource(R.string.error_api_version, s.error.version)
        s.error != null -> LocalResources.current.errorMessage(s.error)
        else -> null
    }
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ServerStep(s: OnboardingState, vm: OnboardingViewModel) {
    Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.onboarding_server_title), style = MaterialTheme.typography.titleMedium)
    Input(
        value = s.serverInput,
        onChange = { vm.edit { copy(serverInput = it) } },
        label = R.string.onboarding_server_url,
        placeholder = R.string.onboarding_server_hint,
        error = s.fieldErrors[Field.Server],
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        onIme = { vm.checkServer() },
    )
    SubmitButton(R.string.action_continue, s.busy) { vm.checkServer() }
}

@Composable
private fun ChooseStep(s: OnboardingState, vm: OnboardingViewModel) {
    s.serverInfo?.let { Text(stringResource(R.string.onboarding_connected, s.serverUrl.orEmpty(), it.version)) }
    Choice(R.string.onboarding_create, R.string.onboarding_create_desc) { vm.goTo(OnboardingStep.Create) }
    Choice(R.string.onboarding_join, R.string.onboarding_join_desc) { vm.goTo(OnboardingStep.Join) }
    Choice(R.string.onboarding_login, R.string.onboarding_login_desc) { vm.goTo(OnboardingStep.Login) }
    TextButton(onClick = { vm.goTo(OnboardingStep.Server) }) { Text(stringResource(R.string.onboarding_change_server)) }
}

@Composable
private fun Choice(title: Int, desc: Int, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FormStep(s: OnboardingState, vm: OnboardingViewModel) {
    val title = when (s.step) {
        OnboardingStep.Create -> R.string.onboarding_create
        OnboardingStep.Join -> R.string.onboarding_join
        else -> R.string.onboarding_login
    }
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
    s.serverUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    val next = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false)
    if (s.step == OnboardingStep.Create) {
        Input(s.setupToken, { vm.edit { copy(setupToken = it) } }, R.string.onboarding_setup_token,
            error = s.fieldErrors[Field.SetupToken], help = R.string.onboarding_setup_token_help, keyboard = next)
        Input(s.householdName, { vm.edit { copy(householdName = it) } }, R.string.onboarding_household_name,
            error = s.fieldErrors[Field.HouseholdName],
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
    }
    if (s.step == OnboardingStep.Join) {
        Input(s.inviteCode, { vm.edit { copy(inviteCode = it) } }, R.string.onboarding_invite_code,
            error = s.fieldErrors[Field.InviteCode],
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next, autoCorrectEnabled = false))
    }
    if (s.step != OnboardingStep.Login) {
        Input(s.displayName, { vm.edit { copy(displayName = it) } }, R.string.onboarding_display_name,
            error = s.fieldErrors[Field.DisplayName], help = R.string.onboarding_display_name_help,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
    }
    Input(s.username, { vm.edit { copy(username = it) } }, R.string.onboarding_username,
        error = s.fieldErrors[Field.Username], keyboard = next)
    PasswordInput(s.password, { vm.edit { copy(password = it) } }, s.fieldErrors[Field.Password]) { vm.submit() }
    SubmitButton(title, s.busy) { vm.submit() }
}

@Composable
private fun Input(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    placeholder: Int? = null,
    error: Int? = null,
    help: Int? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onIme: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        isError = error != null,
        supportingText = (error ?: help)?.let { { Text(stringResource(it)) } },
        singleLine = true,
        keyboardOptions = keyboard,
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onIme?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordInput(value: String, onChange: (String) -> Unit, error: Int?, onDone: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.onboarding_password)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    stringResource(if (visible) R.string.onboarding_hide_password else R.string.onboarding_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubmitButton(label: Int, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(label))
        }
    }
}

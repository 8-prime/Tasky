package dev.tasky.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tasky.android.AppContainer
import dev.tasky.android.BuildConfig
import dev.tasky.android.InviteLink
import dev.tasky.android.ui.onboarding.OnboardingScreen
import dev.tasky.android.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun TaskyRoot(container: AppContainer, invite: MutableStateFlow<InviteLink?>) {
    val session by container.sessions.session.collectAsState()
    val pendingInvite by invite.collectAsState()
    if (session == null) {
        val vm: OnboardingViewModel = viewModel {
            OnboardingViewModel(container.account, container.sessions.lastServerUrl, BuildConfig.DEFAULT_SERVER_URL)
        }
        LaunchedEffect(pendingInvite) {
            pendingInvite?.let {
                vm.openInvite(it.server, it.code)
                invite.value = null
            }
        }
        OnboardingScreen(vm)
    } else {
        // Already signed in: v1 has one household per user, so invite links are ignored.
        LaunchedEffect(pendingInvite) { invite.value = null }
        MainScaffold(container)
    }
}

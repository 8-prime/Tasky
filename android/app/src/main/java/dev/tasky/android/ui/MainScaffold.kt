package dev.tasky.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.tasky.android.AppContainer

@Composable
fun MainScaffold(container: AppContainer) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(container.sessions.current?.householdName.orEmpty())
    }
}

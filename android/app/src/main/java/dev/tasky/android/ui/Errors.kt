package dev.tasky.android.ui

import android.content.res.Resources
import dev.tasky.android.R
import dev.tasky.android.api.ApiException
import dev.tasky.api.ErrorCode
import java.net.UnknownServiceException

/** A user-facing message for anything thrown by the API client. */
fun Resources.errorMessage(e: Throwable): String = when (e) {
    is ApiException -> when (e.code) {
        ErrorCode.InvalidCredentials -> getString(R.string.error_invalid_credentials)
        ErrorCode.InvalidSetupToken -> getString(R.string.error_invalid_setup_token)
        ErrorCode.InvalidInvite -> getString(R.string.error_invalid_invite)
        ErrorCode.UsernameTaken -> getString(R.string.error_username_taken)
        else -> getString(R.string.error_server, e.message ?: e.status.toString())
    }
    is UnknownServiceException -> getString(R.string.error_cleartext)
    else -> getString(R.string.error_network, e.message ?: e.javaClass.simpleName)
}

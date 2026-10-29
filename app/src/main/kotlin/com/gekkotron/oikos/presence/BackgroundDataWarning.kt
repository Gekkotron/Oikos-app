package com.gekkotron.oikos.presence

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.R
import com.gekkotron.oikos.data.PresenceConfig

/**
 * Presence publishes from the background; with the app's "Background data" off (or Data Saver on without an exemption),
 * an EXIT seen on mobile data waits for Wi-Fi, so the hub only hears "away" once the phone is back home.
 */
fun shouldWarnBackgroundData(presence: PresenceConfig?, restrictStatus: Int): Boolean =
    presence?.enabled == true && restrictStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

private fun restrictBackgroundStatus(context: Context): Int =
    context.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus
        ?: ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED

/** The app's "Mobile data & Wi-Fi" page (Background data toggle); the app info page where that screen is missing. */
private fun openBackgroundDataSettings(context: Context) {
    val pkg = Uri.parse("package:${context.packageName}")
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Warns once per launch while presence is on and Android blocks the app's background mobile data. */
@Composable
fun WarnBackgroundDataOnLaunch(presence: PresenceConfig?) {
    val context = LocalContext.current
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val status = remember { restrictBackgroundStatus(context) }
    if (dismissed || !shouldWarnBackgroundData(presence, status)) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.presence_background_data_title)) },
        text = { Text(stringResource(R.string.presence_background_data_body)) },
        confirmButton = {
            TextButton(onClick = { dismissed = true; openBackgroundDataSettings(context) }) {
                Text(stringResource(R.string.presence_background_data_open))
            }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.presence_background_data_later)) }
        },
    )
}

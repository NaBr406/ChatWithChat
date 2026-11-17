package cn.nabr.chatwithchat.data.generation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.service.ChatGenerationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidChatGenerationKeepAlive @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ChatGenerationKeepAlive {
    override fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ChatGenerationService::class.java))
        } catch (error: RuntimeException) {
            // A background-start restriction or exhausted FGS quota must not discard the user's turn.
            reportGenerationKeepAliveFailure(context, error)
        }
    }
}

internal fun reportGenerationKeepAliveFailure(context: Context, error: RuntimeException) {
    Log.w("ChatGenerationService", "Unable to keep generation in a foreground service", error)
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, R.string.chat_generation_background_unavailable, Toast.LENGTH_LONG).show()
    }
}

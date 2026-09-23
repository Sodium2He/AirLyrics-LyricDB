package com.andsi.airlyrics.app.host

import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.lyrics.catalog.LibraryCatalog
import com.andsi.airlyrics.lyrics.catalog.SyncRejectReason
import com.andsi.airlyrics.settings.store.LibrarySyncStore
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainUiHost.catalogActiveText(): String {
    val status = LibraryCatalog.status(this) ?: return getString(R.string.ui_library_catalog_inactive)
    return getString(
        R.string.ui_library_catalog_active,
        status.libraryId,
        status.generation.toString()
    )
}

internal fun MainUiHost.librarySyncStatusText(): String {
    val reason = LibrarySyncStore.lastReason(this)
        ?: return getString(R.string.ui_library_sync_idle)
    val generation = LibrarySyncStore.lastGeneration(this)?.toString().orEmpty()
    return when (reason) {
        "ACTIVATED" -> getString(R.string.ui_sync_ok, generation)
        SyncRejectReason.UNCHANGED.name -> getString(R.string.ui_sync_same)
        SyncRejectReason.NOT_WIFI.name -> getString(R.string.ui_sync_wifi)
        SyncRejectReason.VPN_UNCONFIRMED.name -> getString(R.string.ui_sync_vpn)
        SyncRejectReason.NO_URL.name -> getString(R.string.ui_library_sync_no_url)
        else -> getString(com.andsi.airlyrics.i18n.syncFailureResource(LibrarySyncStore.lastDetail(this)), listOfNotNull(reason, LibrarySyncStore.lastDetail(this)?.takeIf { it.isNotBlank() }).joinToString("\n"))
    }
}

internal fun MainUiHost.showLibrarySyncEditorImpl() {
    val urlInput = syncField(
        hint = getString(R.string.ui_library_sync_url),
        value = LibrarySyncStore.getManifestUrl(this),
        password = false
    )
    val userInput = syncField(
        hint = getString(R.string.ui_library_sync_user),
        value = LibrarySyncStore.getUsername(this),
        password = false
    )
    val passwordInput = syncField(
        hint = getString(R.string.ui_library_sync_password),
        value = LibrarySyncStore.getPassword(this),
        password = true
    )
    showAirDialog(
        title = getString(R.string.ui_library_sync),
        message = getString(R.string.ui_library_sync_hint),
        positiveText = getString(R.string.ui_save),
        negativeText = getString(R.string.ui_cancel),
        body = {
            addView(urlInput)
            addView(userInput)
            addView(passwordInput)
        },
        onPositive = {
            LibrarySyncStore.setEndpoint(
                context = this,
                manifestUrl = urlInput.text.toString(),
                username = userInput.text.toString(),
                password = passwordInput.text.toString()
            )
            uiActions.syncLibraryNow()
        }
    )
}

private fun MainUiHost.syncField(hint: String, value: String, password: Boolean): EditText {
    return EditText(this).apply {
        setHint(hint)
        setText(value)
        textSize = AirUiTokens.TextSize.Body
        isSingleLine = true
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        setTextColor(colorTextStrong)
        setHintTextColor(colorTextMuted)
        setPadding(
            dp(AirUiTokens.Space.Xxl),
            dp(AirUiTokens.Space.Xl),
            dp(AirUiTokens.Space.Xxl),
            dp(AirUiTokens.Space.Xl)
        )
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(AirUiTokens.Space.Md)
        }
    }
}

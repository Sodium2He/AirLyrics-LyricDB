package com.andsi.airlyrics.i18n

import com.andsi.airlyrics.R

fun syncFailureResource(detail: String?): Int = when {
    detail == null -> R.string.ui_sync_failed_detail
    "HTTP 401" in detail || "HTTP 403" in detail -> R.string.ui_sync_failed_auth
    "HTTP 404" in detail || "HTTP 410" in detail -> R.string.ui_sync_failed_missing
    "HTTP 30" in detail -> R.string.ui_sync_failed_redirect
    "Timeout" in detail -> R.string.ui_sync_failed_timeout
    "SSL" in detail || "Certificate" in detail -> R.string.ui_sync_failed_tls
    "ConnectException" in detail || "NoRouteToHost" in detail || "UnknownHost" in detail -> R.string.ui_sync_failed_connection
    "PARSE_MANIFEST" in detail -> R.string.ui_sync_failed_manifest
    else -> R.string.ui_sync_failed_detail
}

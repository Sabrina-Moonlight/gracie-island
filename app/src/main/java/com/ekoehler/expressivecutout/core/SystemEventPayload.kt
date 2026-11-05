package com.ekoehler.expressivecutout.core

import android.graphics.Bitmap
import com.ekoehler.expressivecutout.service.ProgressData

/**
 * Rich domain payload describing a device-level system event, carrying dynamic metadata,
 * collapsed badge text, secondary detail lines, and optional action intent routing.
 */
data class SystemEventPayload(
    val type: SystemEventType,
    val title: String? = null,
    val subtitle: String? = null,
    val collapsedBadgeText: String? = null,
    val secondaryLines: List<String> = emptyList(),
    val actionIntentAction: String? = null,
    val actionIntentUri: String? = null,
    val vectorIconName: String? = null,
    val iconBitmap: Bitmap? = null,
    /** A live, measurable progress (a running download); drawn as the ring on the pill's trailing edge. */
    val progress: ProgressData? = null,
)

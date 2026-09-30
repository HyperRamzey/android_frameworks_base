/*
 * Copyright (C) 2017-2018 Paranoid Android
 * Copyright (C) 2022 FlamingoOS Project
 * Copyright (C) 2024 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy

import android.content.Context
import android.util.Log
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.doze.util.zigzag
import com.android.systemui.navigationbar.views.NavigationBarView
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val TAG = BurnInProtectionController::class.simpleName

/**
 * Shifts the status bar and navigation bar contents around to avoid permanent burn-in on OLED
 * panels.
 *
 * The offsets are driven by an internal tick counter rather than by the wall clock, so every
 * shift interval produces a new position. The vertical/horizontal amplitude is capped by
 * [R.dimen.vertical_max_shift] / [R.dimen.horizontal_max_shift] and the interval by
 * [R.integer.config_systemBarBurnInProtectionShiftInterval].
 */
@SysUISingleton
class BurnInProtectionController @Inject constructor(
    private val context: Context,
    configurationController: ConfigurationController,
) : ConfigurationController.ConfigurationListener {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val shiftEnabled = context.resources.getBoolean(
        com.android.internal.R.bool.config_enableBurnInProtection
    )

    private val shiftInterval = context.resources.getInteger(
        R.integer.config_systemBarBurnInProtectionShiftInterval
    ) * 1000L

    private var phoneStatusBarView: PhoneStatusBarView? = null
    private var navigationBarView: NavigationBarView? = null

    private var shiftJob: Job? = null
    private var shiftCounter = 0

    private var statusBarStartLimitX = 0 to 0
    private var statusBarEndLimitX = 0 to 0
    private var maxStatusBarOffsetY = 0
    private var maxNavBarOffsetX = 0
    private var maxNavBarOffsetY = 0

    private var lastStartOffset = Offset.Zero
    private var lastEndOffset = Offset.Zero
    private var lastNavBarOffset = Offset.Zero

    init {
        logD {
            "shiftEnabled = $shiftEnabled, isGesturalMode = ${isGesturalMode()}"
        }
        configurationController.addCallback(this)
        loadResources()
    }

    private fun isGesturalMode(): Boolean = context.resources.getInteger(
        com.android.internal.R.integer.config_navBarInteractionMode
    ) == NAVIGATION_MODE_GESTURAL

    private fun loadResources() {
        with(context.resources) {
            val horizontalMaxShift = getDimensionPixelSize(R.dimen.horizontal_max_shift)
            val verticalMaxShift = getDimensionPixelSize(R.dimen.vertical_max_shift)

            statusBarStartLimitX = -min(
                getDimensionPixelSize(R.dimen.status_bar_padding_start),
                horizontalMaxShift
            ) to horizontalMaxShift

            statusBarEndLimitX = -horizontalMaxShift to min(
                getDimensionPixelSize(R.dimen.status_bar_padding_end),
                horizontalMaxShift
            )

            maxStatusBarOffsetY = min(
                SystemBarUtils.getStatusBarHeight(context) -
                    getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height_default),
                verticalMaxShift
            ) / 2
        }
        calculateNavBarMaxOffset()
        logD {
            "statusBarStartLimitX = $statusBarStartLimitX, " +
                "statusBarEndLimitX = $statusBarEndLimitX, " +
                "maxStatusBarOffsetY = $maxStatusBarOffsetY"
        }
    }

    private fun calculateNavBarMaxOffset() {
        with(context.resources) {
            maxNavBarOffsetX = if (isGesturalMode()) {
                0
            } else {
                getDimensionPixelSize(R.dimen.floating_rotation_button_min_margin) / 4
            }
            maxNavBarOffsetY = if (isGesturalMode()) {
                getDimensionPixelSize(R.dimen.navigation_handle_bottom) / 3
            } else {
                (getDimensionPixelSize(R.dimen.navigation_bar_height) -
                    getDimensionPixelSize(R.dimen.navigation_icon_size)) / 3
            }
        }
        logD {
            "maxNavBarOffsetX = $maxNavBarOffsetX, maxNavBarOffsetY = $maxNavBarOffsetY"
        }
    }

    fun setPhoneStatusBarView(phoneStatusBarView: PhoneStatusBarView?) {
        this.phoneStatusBarView = phoneStatusBarView
    }

    fun setNavigationBarView(navigationBarView: NavigationBarView?) {
        this.navigationBarView = navigationBarView
    }

    fun startShiftTimer() {
        if (!shiftEnabled || (shiftJob?.isActive == true)) return
        shiftJob = coroutineScope.launch {
            while (isActive) {
                val startOffset = Offset(
                    getBurnInOffset(statusBarStartLimitX),
                    getBurnInOffset(maxStatusBarOffsetY)
                )
                val endOffset = Offset(
                    getBurnInOffset(statusBarEndLimitX),
                    getBurnInOffset(maxStatusBarOffsetY)
                )
                val navBarOffset = Offset(
                    getBurnInOffset(maxNavBarOffsetX),
                    getBurnInOffset(maxNavBarOffsetY)
                )
                logD {
                    "new offsets: start = $startOffset, end = $endOffset, " +
                        "navBar = $navBarOffset"
                }
                updateViews(startOffset, endOffset, navBarOffset)
                delay(shiftInterval)
                shiftCounter++
            }
        }
        logD {
            "Started shift job"
        }
    }

    private fun getBurnInOffset(maxOffset: Int): Int {
        if (maxOffset <= 0) return 0
        val amplitude = maxOffset.toFloat()
        val period = amplitude * 2
        val mult = if ((shiftCounter / period) % 2 == 0f) 1 else -1
        return mult * Math.round(zigzag(shiftCounter.toFloat(), amplitude, period))
    }

    private fun getBurnInOffset(offsetLimits: Pair<Int, Int>): Int {
        val amplitude = (offsetLimits.second - offsetLimits.first).toFloat()
        if (amplitude <= 0f) return offsetLimits.first
        val period = amplitude * 2
        return Math.round(
            zigzag(shiftCounter.toFloat(), amplitude, period) + offsetLimits.first
        )
    }

    private fun updateViews(startOffset: Offset, endOffset: Offset, navBarOffset: Offset) {
        if (startOffset != lastStartOffset || endOffset != lastEndOffset) {
            logD {
                "Translating statusbar"
            }
            phoneStatusBarView?.offsetStatusBar(startOffset, endOffset)
            lastStartOffset = startOffset
            lastEndOffset = endOffset
        }
        if (navBarOffset != lastNavBarOffset) {
            logD {
                "Translating navbar"
            }
            navigationBarView?.offsetNavBar(navBarOffset)
            lastNavBarOffset = navBarOffset
        }
    }

    fun stopShiftTimer() {
        if (!shiftEnabled || (shiftJob?.isActive != true)) return
        logD {
            "Cancelling shift job"
        }
        coroutineScope.launch {
            shiftJob?.cancelAndJoin()
            updateViews(Offset.Zero, Offset.Zero, Offset.Zero)
            logD {
                "Cancelled shift job"
            }
        }
    }

    override fun onDensityOrFontScaleChanged() {
        logD {
            "onDensityOrFontScaleChanged"
        }
        loadResources()
    }

    private companion object {
        const val NAVIGATION_MODE_GESTURAL = 2
    }
}

private inline fun logD(crossinline msg: () -> String) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, msg())
    }
}

data class Offset(
    val x: Int,
    val y: Int
) {
    companion object {
        val Zero = Offset(0, 0)
    }
}

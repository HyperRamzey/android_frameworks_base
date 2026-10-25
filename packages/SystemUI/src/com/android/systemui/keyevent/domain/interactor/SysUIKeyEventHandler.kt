/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyevent.domain.interactor

import android.view.KeyEvent
import com.android.systemui.back.domain.interactor.BackActionInteractor
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardKeyEventInteractor
import javax.inject.Inject
import javax.inject.Provider

/**
 * Sends key events to the appropriate interactors and then acts upon key events that haven't
 * already been handled but should be handled by SystemUI.
 *
 * To observe any key event states, see [KeyEventInteractor].
 */
@SysUISingleton
class SysUIKeyEventHandler
@Inject
constructor(
    private val backActionInteractor: BackActionInteractor,
    private val keyguardKeyEventInteractor: KeyguardKeyEventInteractor,
    private val authControllerProvider: Provider<AuthController>,
) {
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyguardKeyEventInteractor.dispatchKeyEvent(event)) {
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (!backActionInteractor.isBackCallbackRegistered() && event.handleAction()) {
                    backActionInteractor.onBackRequested()
                }
                return true
            }
            // a30s (ET715): the TSP's out-of-LPM (AOD) gesture key.
            // KEY_BLACK_UI_GESTURE (0x1c7) is not an Android keycode, so
            // sec_touchscreen.kl maps it to BUTTON_1, which nothing else uses.
            // AuthController only consumes it when the UDFPS rect is known.
            KeyEvent.KEYCODE_BUTTON_1 -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return authControllerProvider.get().onAodGesture()
                }
            }
        }
        return false
    }

    fun interceptMediaKey(event: KeyEvent): Boolean {
        return keyguardKeyEventInteractor.interceptMediaKey(event)
    }

    fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        return keyguardKeyEventInteractor.dispatchKeyEventPreIme(event)
    }

    companion object {
        // Most actions shouldn't be handled on the down event and instead handled on subsequent
        // key events like ACTION_UP.
        fun KeyEvent.handleAction(): Boolean {
            return action != KeyEvent.ACTION_DOWN
        }
    }
}

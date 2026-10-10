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
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardKeyEventInteractor
import javax.inject.Inject

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
    private val authController: AuthController,
    private val statusBarStateController: StatusBarStateController,
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

            // A30s: the Imagis TSP reports an AOD touch. The Linux keycode
            // (KEY_BLACK_UI_GESTURE, 0x1c7) is mapped to this by
            // Vendor_0000_Product_0000_Version_0000.kl, because Generic.kl has no entry for
            // it and it would otherwise reach us as AKEYCODE_UNKNOWN. AOSP has no handler
            // for the gesture at all - its AOD UDFPS path needs a doze sensor that this
            // device does not have - so forward it to the UDFPS controller.
            KEYCODE_BLACK_UI_GESTURE -> {
                if (statusBarStateController.isDozing) {
                    authController.onAodGesture()
                }
                return true
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
        private const val TAG = "SysUIKeyEventHandler"

        /**
         * A30s: Android keycode that the Imagis TSP's AOD gesture arrives as. The TSP reports
         * Linux KEY_BLACK_UI_GESTURE (0x1c7); that keycode is absent from Generic.kl, so
         * frameworks/base/data/keyboards/Vendor_0000_Product_0000_Version_0000.kl maps it
         * here. BUTTON_1 (android.view.KeyEvent.KEYCODE_BUTTON_1) is used because nothing
         * in PhoneWindowManager, SystemUI or the accessibility services consumes it.
         */
        private const val KEYCODE_BLACK_UI_GESTURE = KeyEvent.KEYCODE_BUTTON_1

        // Most actions shouldn't be handled on the down event and instead handled on subsequent
        // key events like ACTION_UP.
        fun KeyEvent.handleAction(): Boolean {
            return action != KeyEvent.ACTION_DOWN
        }
    }
}

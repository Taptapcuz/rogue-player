package com.google.android.apps.auto.sdk

import android.support.car.input.CarRestrictedEditText
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.android.gms.car.input.CarEditableListener
import com.google.android.gms.car.input.InputManager

/**
 * Head-unit keyboard bridge.
 *
 * Facts this is built on (verified against the bundled aauto-sdk v4.5):
 * - `CarActivity` is NOT a `CarActivityHost`; the host object is the private `a`
 *   field of `com.google.android.gms.car.c` (the same one exitCarApp() uses).
 * - `CarActivityHost.getInputManager()` is what raises the car keyboard.
 * - Handing it the SDK's own `x` bridge makes the keyboard PAINT but swallows the
 *   text: `x` forwards the host's InputConnection rather than the EditText's, so
 *   committed characters never reach the field.
 *
 * So the host gets an adapter delegating straight to our [CarRestrictedEditText]:
 * its real onCreateInputConnection() is what the host must write into.
 */
object CarImeBridge {

    /** Raise the head-unit keyboard, typing straight into [field]. */
    @JvmStatic
    fun start(input: InputManager, field: CarRestrictedEditText) {
        input.startInput(HostEditable(field))
    }

    /** Drop the keyboard / release the host's input session. */
    @JvmStatic
    fun stop(input: InputManager) {
        runCatching { input.stopInput() }
    }

    /** True while the host is showing its keyboard for this activity. */
    @JvmStatic
    fun isActive(input: InputManager): Boolean =
        runCatching { input.isInputActive() }.getOrDefault(false)

    /** com.google.android.gms.car.input.CarEditable over our own EditText. */
    private class HostEditable(private val field: CarRestrictedEditText) :
        com.google.android.gms.car.input.CarEditable {

        override fun onCreateInputConnection(info: EditorInfo?): InputConnection? =
            field.onCreateInputConnection(info ?: EditorInfo())

        override fun setCarEditableListener(listener: CarEditableListener?) {
            if (listener == null) {
                field.setCarEditableListener(null)
            } else {
                field.setCarEditableListener(
                    object : android.support.car.input.CarEditableListener {
                        override fun onUpdateSelection(
                            oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int,
                        ) = listener.onUpdateSelection(oldStart, oldEnd, newStart, newEnd)
                    },
                )
            }
        }

        override fun setInputEnabled(enabled: Boolean) = field.setInputEnabled(enabled)
    }
}

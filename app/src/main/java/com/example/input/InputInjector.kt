package com.example.input

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Takano3D Nexus Ecosystem: Input Injection Subsystem
 *
 * This class handles processing incoming desktop commands and injecting them back into Android.
 * It accesses the system InputManager via reflection to attempt low-latency root-free direct
 * injection, falling back gracefully to companion Unix sockets or standard terminal shell actions.
 */
object InputInjector {

    private const val TAG = "InputInjector"
    private val injectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Holds a handle to our companion app_process daemon's stream if connected
    private var daemonOutputStream: OutputStream? = null

    // InputManager reflection variables
    private var inputManager: Any? = null
    private var injectInputEventMethod: java.lang.reflect.Method? = null
    private var appContext: Context? = null

    /**
     * Initialize InputInjector with application context to acquire system InputManager reflection handles.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val im = context.getSystemService(Context.INPUT_SERVICE)
            inputManager = im
            injectInputEventMethod = im.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(TAG, "InputManager injectInputEvent reflection initialized successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize InputManager reflection: ${e.localizedMessage}")
        }
    }

    fun setDaemonStream(stream: OutputStream?) {
        daemonOutputStream = stream
        Log.i(TAG, "Companion input injection daemon stream connected.")
    }

    /**
     * Entry point to parse and inject input actions securely received from the desktop.
     * Command syntax: "TYPE;PARAMS"
     */
    fun inject(command: String) {
        injectionExecutor.execute {
            try {
                val parts = command.split(";")
                if (parts.isEmpty()) return@execute

                val action = parts[0]
                when (action) {
                    // --- EXISTING PORTABILITY COMMANDS ---
                    "TAP" -> {
                        if (parts.size >= 3) {
                            val x = parts[1].toFloat()
                            val y = parts[2].toFloat()
                            injectMotionEvent(MotionEvent.ACTION_DOWN, x, y)
                            injectMotionEvent(MotionEvent.ACTION_UP, x, y)
                        }
                    }
                    "DRAG" -> {
                        if (parts.size >= 6) {
                            val x1 = parts[1].toFloat()
                            val y1 = parts[2].toFloat()
                            val x2 = parts[3].toFloat()
                            val y2 = parts[4].toFloat()
                            val duration = parts[5].toInt()
                            executeInjection("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $duration")
                        }
                    }
                    "KEY" -> {
                        if (parts.size >= 2) {
                            val keycode = parts[1].toInt()
                            injectKeyEvent(KeyEvent.ACTION_DOWN, keycode)
                            injectKeyEvent(KeyEvent.ACTION_UP, keycode)
                        }
                    }
                    "TEXT" -> {
                        if (parts.size >= 2) {
                            injectText(parts[1])
                        }
                    }

                    // --- NEW FULL-FIDELITY REMOTE CONTROL PROTOCOL (MOUSE & KEYBOARD) ---
                    "MOUSE_MOVE" -> {
                        if (parts.size >= 3) {
                            val x = parts[1].toFloat()
                            val y = parts[2].toFloat()
                            injectMotionEvent(
                                action = MotionEvent.ACTION_MOVE,
                                x = x,
                                y = y,
                                source = InputDevice.SOURCE_MOUSE
                            )
                        }
                    }
                    "MOUSE_DOWN" -> {
                        if (parts.size >= 4) {
                            val x = parts[1].toFloat()
                            val y = parts[2].toFloat()
                            val buttonState = parts[3].toInt() // 1 = Left, 2 = Right
                            injectMotionEvent(
                                action = MotionEvent.ACTION_DOWN,
                                x = x,
                                y = y,
                                source = InputDevice.SOURCE_MOUSE,
                                buttonState = buttonState
                            )
                        }
                    }
                    "MOUSE_UP" -> {
                        if (parts.size >= 4) {
                            val x = parts[1].toFloat()
                            val y = parts[2].toFloat()
                            val buttonState = parts[3].toInt()
                            injectMotionEvent(
                                action = MotionEvent.ACTION_UP,
                                x = x,
                                y = y,
                                source = InputDevice.SOURCE_MOUSE,
                                buttonState = buttonState
                            )
                        }
                    }
                    "MOUSE_CLICK" -> {
                        if (parts.size >= 4) {
                            val x = parts[1].toFloat()
                            val y = parts[2].toFloat()
                            val buttonState = parts[3].toInt()
                            injectMotionEvent(MotionEvent.ACTION_DOWN, x, y, InputDevice.SOURCE_MOUSE, buttonState)
                            injectMotionEvent(MotionEvent.ACTION_UP, x, y, InputDevice.SOURCE_MOUSE, buttonState)
                        }
                    }
                    "MOUSE_SCROLL" -> {
                        if (parts.size >= 5) {
                            val x = parts[1].toFloat()
                            val y = parts[2].toFloat()
                            val deltaX = parts[3].toFloat()
                            val deltaY = parts[4].toFloat()
                            injectMotionEvent(
                                action = MotionEvent.ACTION_SCROLL,
                                x = x,
                                y = y,
                                source = InputDevice.SOURCE_MOUSE,
                                axisScrollX = deltaX,
                                axisScrollY = deltaY
                            )
                        }
                    }
                    "KEY_DOWN" -> {
                        if (parts.size >= 2) {
                            val keycode = parts[1].toInt()
                            injectKeyEvent(KeyEvent.ACTION_DOWN, keycode)
                        }
                    }
                    "KEY_UP" -> {
                        if (parts.size >= 2) {
                            val keycode = parts[1].toInt()
                            injectKeyEvent(KeyEvent.ACTION_UP, keycode)
                        }
                    }
                    "KEY_TEXT" -> {
                        if (parts.size >= 2) {
                            injectText(parts[1])
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unknown injection command format: $command")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing input injection stream", e)
            }
        }
    }

    /**
     * Helper to construct and inject MotionEvents (including multi-axis and scrolls) securely.
     */
    private fun injectMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN,
        buttonState: Int = 0,
        axisScrollX: Float = 0f,
        axisScrollY: Float = 0f
    ) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        try {
            val event = if (action == MotionEvent.ACTION_SCROLL) {
                val properties = arrayOf(MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                })
                val coords = arrayOf(MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    setAxisValue(MotionEvent.AXIS_VSCROLL, axisScrollY)
                    setAxisValue(MotionEvent.AXIS_HSCROLL, axisScrollX)
                })
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    1,
                    properties,
                    coords,
                    0,
                    0,
                    1.0f,
                    1.0f,
                    0,
                    0,
                    source,
                    0
                )
            } else {
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    0
                ).apply {
                    this.source = source
                    if (buttonState != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val setButtonStateMethod = MotionEvent::class.java.getMethod("setButtonState", Int::class.javaPrimitiveType)
                            setButtonStateMethod.invoke(this, buttonState)
                        } catch (ex: Exception) {
                            // Suppressed fallback
                        }
                    }
                }
            }

            // Attempt direct InputManager Injection (High performance)
            val method = injectInputEventMethod
            val im = inputManager
            if (method != null && im != null) {
                method.invoke(im, event, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
                Log.v(TAG, "Successfully injected motion event via InputManager: action=$action x=$x y=$y")
                event.recycle()
                return
            }
        } catch (e: Exception) {
            Log.v(TAG, "InputManager direct injection failed, using fallback execution: ${e.localizedMessage}")
        }

        // Fallback execution path
        val shellCmd = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> "input tap ${x.toInt()} ${y.toInt()}"
            MotionEvent.ACTION_SCROLL -> "input swipe ${x.toInt()} ${y.toInt()} ${(x + axisScrollX * 100).toInt()} ${(y + axisScrollY * 100).toInt()} 100"
            else -> null
        }
        if (shellCmd != null) {
            executeInjection(shellCmd)
        }
    }

    /**
     * Helper to inject keyboard key events using Android InputManager.
     */
    private fun injectKeyEvent(action: Int, keycode: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()
        val event = KeyEvent(downTime, eventTime, action, keycode, 0)

        try {
            val method = injectInputEventMethod
            val im = inputManager
            if (method != null && im != null) {
                method.invoke(im, event, 0)
                Log.v(TAG, "Successfully injected key event via InputManager: action=$action keycode=$keycode")
                return
            }
        } catch (e: Exception) {
            Log.v(TAG, "InputManager key injection failed: ${e.localizedMessage}")
        }

        if (action == KeyEvent.ACTION_DOWN) {
            executeInjection("input keyevent $keycode")
        }
    }

    private fun injectText(text: String) {
        val escapedText = text.replace(" ", "%s")
        executeInjection("input text $escapedText")
    }

    /**
     * Executes the injection using the optimal path.
     * If the high-speed app_process companion socket is open, we write raw binary injection commands directly.
     * Otherwise, we fall back to a local Shell process execution (higher latency, suitable for fallback/testing).
     */
    private fun executeInjection(shellCommand: String) {
        val daemon = daemonOutputStream
        if (daemon != null) {
            try {
                // High-performance path: Write command directly to Unix local socket of companion process
                val cmdBytes = (shellCommand + "\n").toByteArray(Charsets.UTF_8)
                daemon.write(cmdBytes)
                daemon.flush()
                Log.v(TAG, "Input event piped to companion daemon: $shellCommand")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Companion daemon channel severed, falling back to local runtime exec.", e)
                daemonOutputStream = null
            }
        }

        // Low-performance local runtime fallback: Spawn local shell process
        try {
            val process = Runtime.getRuntime().exec(shellCommand)
            process.waitFor()
            Log.d(TAG, "Input event executed via fallback subprocess: $shellCommand")
        } catch (e: Exception) {
            Log.e(TAG, "Local Sandbox execution blocked. Connect via ADB/companion daemon for input injection.", e)
        }
    }
}

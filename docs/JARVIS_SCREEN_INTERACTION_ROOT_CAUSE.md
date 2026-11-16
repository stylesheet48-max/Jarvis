# JARVIS Screen Interaction — Detailed Root Cause Analysis

**Date:** November 17, 2026  
**Document:** Root Cause Analysis of Screen Interaction Failures  

---

## 1. Termux Interaction Failure Analysis

### Symptom
When the user requests JARVIS to run a command or interact with Termux (e.g. *"Type python test.py in Termux"*, *"Press Enter"*, *"Copy output"*), JARVIS fails to type or claims an input field was not found.

### Source Code Trace
1. **Entry Point**: `VisualTaskOrchestrator.handleType(text, targetInput, submit, x, y)`.
2. **Target Resolution**:
   ```kotlin
   val editable = elements.filter { it.isEditable || it.className.contains("EditText", true) }
   ```
3. **Termux Hierarchy**:
   Termux's main activity (`com.termux.app.TermuxActivity`) hosts a custom `com.termux.view.TerminalView`.
   `TerminalView` inherits directly from `android.view.View`, not `android.widget.EditText`.
   In Android Accessibility:
   - `node.isEditable` reports `false`.
   - `node.className` reports `"com.termux.view.TerminalView"`.
   - `node.actionList` omits `ACTION_SET_TEXT` (ID: 0x00200000).
4. **Failure Trigger**:
   Because `editable` is empty, `handleType` attempts `service.smartTypeText(text)`.
   Inside `JarvisAccessibilityService.kt`:
   ```kotlin
   val target = editableAt(appRoot)
   if (target == null) {
       Log.w(TAG, "smartTypeText found no verified editable target; no text was entered")
       return false
   }
   ```
   `editableAt()` returns `null`, and typing is aborted immediately with zero action taken on the terminal.

### The Correct Fix for Termux
1. **Terminal Surface Recognition**: Identify `com.termux` or views matching `className.contains("TerminalView")`.
2. **Terminal Focus Activation**: Dispatch a single tap at the center of `TerminalView` to ensure the terminal session receives input focus and the software keyboard or extra keys are connected.
3. **Dual Input Strategy**:
   - **Strategy A (Clipboard & Paste)**: Put the text/command into the Android system clipboard via `ClipboardManager`, then dispatch a long press (600ms) on `TerminalView` to trigger the Termux context menu and tap the "PASTE" button, OR call `performAction(ACTION_PASTE)` if the view accepts it.
   - **Strategy B (IME Key Event Dispatch)**: Dispatch character key events or use an accessibility text insertion bridge.
4. **Terminal Submit / Execution**: Dispatch the Enter key via `dispatchKeyEvent(KeyEvent(ACTION_DOWN, KEYCODE_ENTER))` or tap Termux's extra-keys "ENTER" chip.
5. **Verification**: Verify terminal state transition via `ScreenSignatureCalculator` (comparing the terminal buffer's visual/text hash before and after the action) instead of requiring an exact string match in an `EditText`.

---

## 2. Browser & WebView Failure Analysis

### Symptom
On websites loaded in Google Chrome or embedded WebViews:
1. Buttons (like "Generate", "Submit", "Sign in", "Next") appear to be clicked, but no action occurs on the webpage.
2. In-page search inputs or chat prompt boxes are ignored, or the browser's address bar is clicked instead.
3. Dynamic single-page applications (React/Next.js/Vue) do not register programmatic clicks.

### Source Code Trace
1. **Virtual Accessibility Node Limitations**:
   Chromium exposes web content via virtual accessibility nodes (`AccessibilityNodeProvider`).
   When `node.performAction(AccessibilityNodeInfo.ACTION_CLICK)` is called:
   - Android OS returns `true`.
   - However, Chromium's internal accessibility bridge only synthesizes a DOM click if the web element has an explicit HTML `role` or native button tag.
   - For custom clickable `<div>`, `<span>`, or WebGL/Canvas elements with JavaScript `addEventListener('click')` or `pointerdown`, Chromium frequently drops the synthetic accessibility event.
2. **Input Identification Collision**:
   Chrome displays its native address bar (`com.android.chrome:id/url_bar`) with hints like *"Search or type URL"*.
   In-page search bars often have similar hints (*"Search...", "Search products", "Search topic"*).
   The heuristic in `findTargetElement()` previously collided with the URL bar unless specifically blocked by hardcoded string checks.
3. **Hardcoded Coordinate Vulnerabilities**:
   In `VisualTaskOrchestrator.kt`, lines 675–676:
   ```kotlin
   val exactArrowX = realDm.widthPixels * 0.9333f // Exactly 1008 on 1080p
   val exactArrowY = realDm.heightPixels * 0.9440f // Exactly 2273 on 2408p
   ```
   This code assumed a fixed `1080x2408` display resolution. On devices with different aspect ratios (e.g. 19.5:9, 20:9, tablets, foldables), the tap completely missed the button.

### The Correct Fix for Browsers
1. **Physical Touch Fallback**: In browsers and WebViews (`isBrowserApp = true`), never rely solely on `ACTION_CLICK`. Always dispatch a real hardware gesture (`dispatchGesture`) directly to `bounds.exactCenterX(), bounds.exactCenterY()`. This forces Android's input pipeline to inject real `MotionEvent.ACTION_DOWN` / `ACTION_UP` into the Chromium rendering engine.
2. **Address Bar vs. In-Page Input Isolation**:
   - Explicitly filter out `url_bar`, `omnibox`, and native browser chrome when targeting web content.
   - Treat in-page inputs with dedicated web placeholder matching and visual grounding.
3. **Dynamic Resolution Calculation**:
   Calculate all normalized coordinates dynamically against `WindowManager.currentWindowMetrics` or `DisplayMetrics`, incorporating real-time navigation bar and cutout insets.

---

## 3. Inaccurate Scrolling & Swiping Analysis

### Symptom
1. Swiping down scrolls the top toolbar or drawer instead of the main feed.
2. Scrolling in photo galleries or documents moves too fast and flings past the desired item.
3. Horizontal swipes for carousels or tabs fail or get converted to vertical scrolls.

### Source Code Trace
In `VisualTaskOrchestrator.kt:1364`:
```kotlin
val success = mutateUi("scroll:$direction") { service.performSmartScroll(direction, distance) }
```
`performSmartScroll` was called without `targetRect` or `scrollableNode`.
Inside `JarvisAccessibilityService.kt`:
```kotlin
val nodeToScroll = scrollableNode ?: findFirstScrollableNode(root)
```
In any complex app with multiple scrollable components (e.g., a horizontally scrollable chip bar, a navigation drawer, and a main vertical `RecyclerView`), `findFirstScrollableNode(root)` returns the **first node encountered in depth-first traversal**. Often this is a horizontal filter bar or header, not the main content area!

### The Correct Fix for Scrolling
1. **Targeted Container Selection**:
   Accept an optional `target_name` or `container_bounds` for `scroll_screen`.
   If the user or task is looking for an item in a specific list, find the scrollable container encompassing that area or point.
2. **Explicit Directional Swipes**:
   Add a dedicated `swipe_screen(direction, startX, startY, distance, durationMs)` primitive so the model or agent can swipe horizontally or vertically at a specific location on screen.
3. **Controlled Gesture Trajectory**:
   Keep swipe paths strictly inside the container bounds (inset by at least 15% from container boundaries) to avoid triggering edge swipe gestures (like Android back gesture) or status bar pull-downs.

---

## 4. Capability Gap Summary

| Missing Primitive | Impact on Human-Like Interaction |
|---|---|
| **Long Press** (`long_press_element`) | Unable to open context menus, select chat messages, copy terminal output, or trigger press-and-hold buttons. |
| **Double Tap** (`double_tap_element`) | Unable to zoom images, like social posts, or select words in documents. |
| **Drag and Drop** (`drag_and_drop`) | Unable to move sliders, reorder lists, or drag floating widgets. |
| **Text Selection** (`select_text`, `select_all`) | Unable to select specific words, paragraphs, or entire document text. |
| **Clipboard Actions** (`copy`, `cut`, `paste`, `clear`) | Unable to copy text between applications or safely clear input fields. |
| **Termux Terminal Engine** | Completely blocked from interacting with terminal command lines and developer tools. |
| **Display Cutout / Rotation Inset Calibration** | Taps drift on rotated displays or devices with camera punch-holes. |

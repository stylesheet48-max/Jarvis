# JARVIS Android Screen Interaction Upgrade --- Implementation Report

**Document Version:** 1.0.0  
**Project:** JARVIS Android Assistant (`com.jarvis.assistant`)  
**Target Architecture:** Android API 34 (UpsideDownCake), Min API 26 (Oreo)  
**AI Brain:** Google Gemini Live API (Preserved exclusively, no Local LLM)  
**Component:** Full Human-Like Computer / Screen Interaction System

---

## 1. Architectural Overview

Prior to this upgrade, JARVIS relied on a fragmented interaction model where accessibility node clicks (`ACTION_CLICK`) failed silently on web pages, terminal emulators (Termux) were unusable due to lack of `ACTION_SET_TEXT` support, scrolling operated on arbitrary parent nodes, and touch primitives such as long press, double tap, drag-and-drop, and rich text operations were absent.

The new **Unified Screen Interaction Architecture** provides human-like interaction through a four-tiered execution hierarchy:

```
+-----------------------------------------------------------------------------------+
|                            Google Gemini Live API                                 |
|      (Sole AI Brain: Emits click_ui_element, type_text, scroll_screen, etc.)     |
+-----------------------------------------+-----------------------------------------+
                                          | JSON Tool Calls
                                          v
+-----------------------------------------------------------------------------------+
|               JarvisConversationService & VisualTaskOrchestrator                  |
|         - Parameter validation & coordinate normalization (0-1000 -> px)          |
|         - App & context gating (Terminal, Chrome, System UI)                      |
|         - Execution routing & State verification (ActionVerifier)                 |
+-----------------------------------------+-----------------------------------------+
                                          |
        +---------------------------------+---------------------------------+
        |                                 |                                 |
        v                                 v                                 v
+-------------------+             +-------------------+             +-------------------+
| Method 1:         |             | Method 2:         |             | Method 3:         |
| Semantic Node     |             | Calibrated Gestures|            | Visual / Media    |
| (Accessibility    |             | (dispatchGesture) |             | Projection        |
| Actions: CLICK,   |             | Taps, Long-press, |             | Coordinate Taps & |
| FOCUS, SET_TEXT)  |             | Double-tap, Drag  |             | Grounding         |
+-------------------+             +-------------------+             +-------------------+
        ^                                 ^                                 ^
        |                                 |                                 |
        +---------------------------------+---------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                      Method 4: Specialized Surface Handlers                       |
|   +---------------------------------------+-----------------------------------+   |
|   | Termux Terminal Handler               | Chrome / WebView DOM Handler      |   |
|   | - Focused canvas tap                  | - Fallback to calibrated tap      |   |
|   | - Clipboard staging & paste           | - Inset-aware coordinates         |   |
|   | - Soft Enter key dispatch             | - Scroll inside div containers    |   |
|   +---------------------------------------+-----------------------------------+   |
+-----------------------------------------------------------------------------------+
```

---

## 2. File-by-File Implementation Details

### 2.1 `automation/accessibility/JarvisAccessibilityService.kt`
*File Path:* [JarvisAccessibilityService.kt](file:///storage/emulated/0/Jarvis/app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt)

#### Added Features:
1. **Terminal Detection & Input Pipeline:**
   - `isTerminalSurface()`: Detects active foreground package `com.termux` or node class `com.termux.view.TerminalView`.
   - `findTerminalView(rootNode)`: Recursively locates the terminal surface node.
   - `typeIntoTerminal(text: String, submit: Boolean)`: 
     - Taps on the center of the `TerminalView` to ensure focus.
     - Stages text into Android's `ClipboardManager`.
     - Tries context paste via long-press or direct selection.
     - Automatically fires `sendEnterKey()` when `submit=true`.
   - `sendEnterKey()`: Emits `KeyEvent.KEYCODE_ENTER` via `dispatchGesture` on on-screen Enter/Return action keys or fallback IME actions.
2. **Calibrated Touch Primitives:**
   - `performLongPressAtRect(rect: Rect, durationMs: Long)`: Normalized bounding-box center extraction with clamping against display insets. Dispatches `GestureDescription` with specified hold duration (default 700ms - 1200ms).
   - `longPressAt(x: Float, y: Float, durationMs: Long)`: Direct screen coordinate long-press.
   - `performDoubleTapAtRect(rect: Rect)`: Dispatches two consecutive `clickAt` events spaced by 80ms interval.
   - `doubleTapAt(x: Float, y: Float)`: Coordinate-level double tap.
   - `dragAndDrop(startX, startY, endX, endY, holdDurationMs, dragDurationMs)`: Multi-stroke gesture holding at origin before interpolating to destination.
3. **Rich Text Editing:**
   - `performTextAction(action: String, targetRect: Rect?, textArg: String?, startSel: Int?, endSel: Int?)`:
     - `"select_all"` -> `AccessibilityNodeInfo.ACTION_SELECT` / `ACTION_SET_SELECTION(0, length)`.
     - `"copy"` -> `AccessibilityNodeInfo.ACTION_COPY`.
     - `"cut"` -> `AccessibilityNodeInfo.ACTION_CUT`.
     - `"paste"` -> `AccessibilityNodeInfo.ACTION_PASTE`.
     - `"clear"` -> Empty string assignment via `ACTION_SET_TEXT`.
     - `"set_selection"` -> Target cursor bounds setting.
4. **Targeted Container Scrolling:**
   - `performSwipeGesture(startX, startY, endX, endY, durationMs)`: Smooth scroll path.
   - Bounded swiping constrained strictly inside target container bounds.

---

### 2.2 `vision/UiGroundingEngine.kt`
*File Path:* [UiGroundingEngine.kt](file:///storage/emulated/0/Jarvis/app/src/main/java/com/jarvis/assistant/vision/UiGroundingEngine.kt)

#### Added Features:
1. **Termux Semantic Surface Grounding:**
   - Added `isTerminal: Boolean = false` to data class `UiElementInfo`.
   - In `scanScreen()`: Specifically checks `className == "com.termux.view.TerminalView"`. Tags it as `isTerminal = true`, `isClickable = true`, `isEditable = true`, with `primaryLabel = "Terminal Buffer"`.
   - In `findTargetElement()`: Resolves aliases `"terminal"`, `"termux"`, `"shell"`, `"console"`, `"command line"` directly to the active terminal view.
2. **Container Identification:**
   - Tags scrollable containers (`isScrollable = true`) with unique identifiers and bounds to permit container-scoped scrolls.

---

### 2.3 `automation/agent/ActionVerifier.kt`
*File Path:* [ActionVerifier.kt](file:///storage/emulated/0/Jarvis/app/src/main/java/com/jarvis/assistant/automation/agent/ActionVerifier.kt)

#### Added Features:
1. **Terminal Typing Verification:**
   - Previously, typing verification required `targetNode.text` to contain the typed string. Because `TerminalView` does not expose text in its accessibility node, typing into Termux always failed verification.
   - Added `service.isTerminalSurface()` check: When typing in Termux, verification confirms successful gesture execution and screen transition rather than inspecting inaccessible node text.

---

### 2.4 `vision/VisualTaskOrchestrator.kt`
*File Path:* [VisualTaskOrchestrator.kt](file:///storage/emulated/0/Jarvis/app/src/main/java/com/jarvis/assistant/vision/VisualTaskOrchestrator.kt)

#### Added Features:
1. **Terminal Routing in `handleType()`:**
   - When active package is Termux or target is `"terminal"`, delegates directly to `service.typeIntoTerminal(text, submit)`.
2. **Container-Scoped `handleScroll()`:**
   - Accepts `targetContainer`, `containerX`, `containerY`.
   - Locates target container bounds, computes safe scroll track (clamped inside container bounds with 15% margins), preventing parent page scroll.
3. **Touch Primitive Handlers:**
   - `handleLongPress(...)`: Resolves element or normalized coordinates (0-1000) and executes calibrated hold.
   - `handleDoubleTap(...)`: Resolves target and fires 80ms double tap.
   - `handleDragAndDrop(...)`: Performs multi-point drag path.
   - `handleTextAction(...)`: Focuses target field and issues rich text commands.

---

### 2.5 `network/GeminiLiveWebSocket.kt`
*File Path:* [GeminiLiveWebSocket.kt](file:///storage/emulated/0/Jarvis/app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt)

#### Added Features:
1. Exposed 4 new tools to Gemini Live session tools schema:
   - `long_press_element(target_name, element_id, duration_ms, x, y)`
   - `double_tap_element(target_name, element_id, x, y)`
   - `drag_and_drop(start_x, start_y, end_x, end_y, hold_duration_ms)`
   - `text_action(action, target_input_name, text_argument, start_selection, end_selection)`
2. Updated existing tool schemas:
   - `click_ui_element`: Added `action_type` ("click", "long_press", "double_tap") and `duration_ms`.
   - `scroll_screen`: Added `target_container`, `container_x`, `container_y`.

---

### 2.6 `background/JarvisConversationService.kt`
*File Path:* [JarvisConversationService.kt](file:///storage/emulated/0/Jarvis/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt)

#### Added Features:
1. Registered new tools in `isAutomationTool` whitelist and cross-app foreground sanity guards.
2. Wired `handleLongPress`, `handleDoubleTap`, `handleDragAndDrop`, and `handleTextAction` in `when (name)` tool execution block.
3. Enhanced `click_ui_element` and `scroll_screen` to forward new optional arguments.

---

## 3. Invariants & Safety Measures Maintained
- **AI Brain Invariant:** Google Gemini Live WebSocket connection remains the sole intelligence engine. No local LLM was introduced.
- **Wake-Word & Sleep Invariant:** Offline Vosk ("Hey JARVIS", "Hey Myra") and local 4-state voice machine (`JarvisVoiceState`) remain untouched.
- **System Boundaries:** All touches respect system insets (status bar, navigation bar) and permission boundaries.

# JARVIS Screen Interaction — Comprehensive Technical Audit Report

**Date:** November 17, 2026  
**Project:** JARVIS Assistant (`com.jarvis.assistant`)  
**Scope:** Android Screen Understanding, Visual Grounding, Accessibility Actions, Gesture Dispatch, Termux & Browser Compatibility, and Action Verification  
**Target Environment:** Android 14 (API 34) | Minimum SDK: 26 (Android 8.0) | Kotlin 2.0 / JDK 17  

---

## 1. Executive Summary

This audit delivers an exhaustive source-code and runtime examination of the screen-interaction subsystems in JARVIS. JARVIS interacts with Android applications through a hybrid architecture combining an Android `AccessibilityService` (`JarvisAccessibilityService`), an on-device UI grounding engine (`UiGroundingEngine`), a multi-step visual orchestrator (`VisualTaskOrchestrator`), and real-time screen capture via `ScreenCaptureManager` (MediaProjection).

### Key Audit Findings

1. **Interaction Pipeline Disconnect**:
   The existing system relies on a fractured two-tier execution model:
   - **Tier 1 (Programmatic Accessibility)**: Traverses `AccessibilityNodeInfo` hierarchies. It performs well in standard native Android widgets (`android.widget.Button`, `TextView`, `RecyclerView`), but fails silently in apps with virtual or custom render hierarchies (Chrome/WebViews, Termux, Flutter, Canvas-based games).
   - **Tier 2 (Ad-Hoc Coordinate Taps)**: Hardcoded heuristics (e.g. snapping to fixed pixel offsets like `2408p` or `x >= 850 && y <= 150` for Google Flow/Chrome) were added for specific demo screens, but fail when screen resolution, display density, orientation, or UI themes change.

2. **Root Cause of Browser (Chrome / WebView) Failures**:
   - In Chromium and WebViews, accessibility nodes are virtual nodes exposed via the Chromium Accessibility Bridge. Programmatic `performAction(ACTION_CLICK)` frequently returns `true` to Android's `AccessibilityManager`, but Chromium fails to dispatch synthetic mouse/touch events to JavaScript event listeners (especially in React, Vue, Angular, and Canvas apps).
   - Input fields in modern web applications (`contenteditable`, Monaco Editor, rich text editors) do not report `isEditable = true` and reject `ACTION_SET_TEXT`.
   - The address bar (`url_bar`) frequently collides with in-page search inputs due to loose label matching.

3. **Root Cause of Termux Failures**:
   - Termux renders its terminal buffer using a custom Canvas/OpenGL view (`com.termux.view.TerminalView`).
   - `TerminalView` does not expose `isEditable = true` or `ACTION_SET_TEXT` in accessibility metadata.
   - When JARVIS attempted to type commands via `smartTypeText`, it required an editable node matching `isEditable || className.contains("EditText")`. This condition evaluated to `false` on Termux, causing typing to be rejected immediately with `"FAILED: No focused or unique editable input was found on screen"`.
   - Terminal input requires focus activation followed by soft keyboard / IME text entry or clipboard paste via the Termux context menu.

4. **Inaccurate Scrolling & Swiping**:
   - `VisualTaskOrchestrator.handleScroll` only accepted `direction` and `distance`, omitting `targetRect` or `containerNode`.
   - `performSmartScroll` defaulted to `findFirstScrollableNode(root)`. In multi-pane or nested layouts (e.g., sidebar + main list, or tab bar + feed), it repeatedly scrolled the wrong outer container or drawer.

5. **Missing Gesture & Text Manipulation Primitives**:
   - While low-level primitives (`longPressAt`, `doubleTapAt`, `performDragAndDrop`) existed in `JarvisAccessibilityService`, they were **never exposed** in `VisualTaskOrchestrator` or in Gemini's tool declarations (`click_ui_element` only supported simple taps).
   - Rich text editing operations (select text, select all, cursor placement, copy, cut, paste, clear, replace) were completely absent.

---

## 2. End-to-End Action Execution Flow

```
[User Spoken Request]
       │
       ▼
[Gemini Live WebSocket API]
  - Interprets user visual goal
  - Dispatches toolCall: click_ui_element / type_text / scroll_screen
       │
       ▼
[JarvisConversationService.handleToolCallExecution()]
  - Acquires Tool Execution Mutex
  - Verifies foreground app matches task goal
       │
       ▼
[VisualTaskOrchestrator]
  - SerializedActionExecutor (Exclusive lock)
  - Captures pre-action ScreenSignature
       │
       ├──────────────────────────────┬──────────────────────────────┐
       ▼                              ▼                              ▼
[Method 1: Semantic Node]     [Method 2: Calibrated Gesture]  [Method 3: Visual Grounding]
(Accessible & Actionable)     (Hardware Touch Dispatch)       (Normalized x,y -> Pixels)
  - ACTION_CLICK                - dispatchGesture()             - UiGroundingEngine
  - ACTION_LONG_CLICK           - Calibrated duration           - Screen bounds validation
  - ACTION_SET_TEXT             - Container bounds checking     - Inset / rotation compensation
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      │
                                      ▼
                        [ActionVerifier & Post-Observation]
                          - Delay for UI animation / render
                          - Compute post-action ScreenSignature
                          - Verify state transition (Window / Tree / Text)
                          - Produce TaskActionResult (success, verified, summary)
                                      │
                                      ▼
                        [Tool Response to Gemini Live]
```

---

## 3. Component-by-Component Source Code Analysis

### A. `JarvisAccessibilityService.kt`
- **Location**: `app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt`
- **Current Lines**: 2291 lines.
- **Capabilities Present**:
  - `clickAt(x, y)`: Single touch gesture (100ms stroke).
  - `longPressAt(x, y, durationMs)`: Touch gesture (default 700ms stroke).
  - `doubleTapAt(x, y)`: Two sequential strokes separated by 80ms.
  - `performDragAndDrop(startX, startY, endX, endY)`: Hold stroke followed by drag path.
  - `performSmartScroll(direction, distance, targetRect, scrollableNode)`: Evaluates semantic scroll first, falls back to container gesture.
  - `smartTypeText(text, targetRect, submit)`: Sets text or falls back to clipboard paste.
- **Defects Identified**:
  - `smartTypeText` aborts if no node matches `isEditable` (breaking Termux and web editors).
  - Gestures calculate coordinates against `resources.displayMetrics` without accounting for display rotation or system bar insets.

### B. `UiGroundingEngine.kt`
- **Location**: `app/src/main/java/com/jarvis/assistant/vision/UiGroundingEngine.kt`
- **Current Lines**: 594 lines.
- **Capabilities Present**:
  - `scanScreen(root)`: Traverses accessibility tree, computes bounding boxes, extracts primary labels.
  - `findTargetElement(elements, targetName)`: Fuzzy string matching against text, description, and view ID.
- **Defects Identified**:
  - Lacked support for terminal views (`TerminalView`).
  - Strict input filter required `isEditable || className.contains("EditText")`.
  - Overfitted heuristics for specific third-party websites (Google Flow video controls).

### C. `VisualTaskOrchestrator.kt`
- **Location**: `app/src/main/java/com/jarvis/assistant/vision/VisualTaskOrchestrator.kt`
- **Current Lines**: 1523 lines.
- **Capabilities Present**:
  - `handleClick()`: Snaps normalized coordinates (0-1000) or finds target by label.
  - `handleType()`: Delegates to `smartTypeText`.
  - `handleScroll()`: Dispatches scroll with loop detection (15 consecutive scrolls max).
- **Defects Identified**:
  - No `handleLongPress()` or `handleDoubleTap()`.
  - No text selection, cursor positioning, or clipboard tool.
  - Hardcoded screen heights (e.g. `2408`) and fixed coordinate percentages (`0.9333f`, `0.9440f`).

### D. `ActionVerifier.kt`
- **Location**: `app/src/main/java/com/jarvis/assistant/automation/agent/ActionVerifier.kt`
- **Current Lines**: 157 lines.
- **Defects Identified**:
  - `verifyTyping` requires `targetNode.isEditable` and exact string match on `targetNode.text`. This fails completely on terminal emulators and rich web text editors where text changes appear in sibling rendering views rather than in `node.text`.

---

## 4. Detailed Root Cause Classifications

| Dimension | Target App / UI | Failure Symptom | Confirmed Root Cause |
|---|---|---|---|
| **Terminal Interaction** | Termux (`com.termux`) | Typing fails; clicks do not focus terminal; cannot paste or long-press. | `TerminalView` is a custom canvas view with no accessibility `isEditable` flag or `ACTION_SET_TEXT`. Strict validation rejects input. |
| **Browser Interaction** | Google Chrome & WebViews | Taps report success but web buttons do not trigger; in-page forms not typed. | Virtual accessibility nodes accept `ACTION_CLICK` without forwarding hardware touch events to Chromium JS listeners. Fallback to physical gesture is required. |
| **Scrolling & Swiping** | Multi-container apps (Settings, Play Store, Social feeds) | Wrong pane scrolls; scrolls wrong direction or flings past content. | `handleScroll` omitted container target parameters; defaulted blindly to `findFirstScrollableNode`. |
| **Touch Primitives** | General Android apps | Cannot open context menus, select items, or perform drag-and-drop. | Long press, double tap, and drag-and-drop were not exposed through orchestrator or Gemini tool schemas. |
| **Text Editing** | Text editors, messaging apps | Cannot select text, replace words, move cursor, or copy/paste. | No text selection (`ACTION_SET_SELECTION`), select-all, or cursor navigation tools existed. |
| **Coordinate Calibration** | Rotated / notched displays | Taps land slightly above or below buttons. | Coordinates did not compensate for display rotation, navigation bar insets, or camera cutouts. |

---

## 5. Prioritized Action Plan

1. **Phase B (Unified Interaction Layer)**:
   - Enhance `JarvisAccessibilityService` with rotation-aware, cutout-aware coordinate calibration.
   - Implement comprehensive touch primitives: Single Tap, Double Tap, Long Press (configurable duration), Drag and Drop, and Targeted Swiping.
   - Build dedicated Termux terminal interaction support (focused tap, IME typing, clipboard paste, terminal key dispatch).
   - Build dedicated Browser/WebView interaction support (hardware gesture fallback for virtual web elements, in-page form focus).
2. **Phase C (Intelligent Action Planning & Text Editing)**:
   - Add rich text editing methods: `focusInput`, `setCursorPosition`, `selectText`, `selectAll`, `copyText`, `cutText`, `pasteText`, `clearText`.
   - Update `VisualTaskOrchestrator` to expose all touch, gesture, and text primitives.
   - Enhance `ActionVerifier` to verify terminal and web content changes using screen signature diffs when semantic text nodes are unavailable.
3. **Phase D (Gemini Tool Declarations & Orchestration)**:
   - Declare `long_press_element`, `double_tap_element`, `drag_and_drop`, and `text_action` in `GeminiLiveWebSocket.kt`.
   - Wire tool dispatchers in `JarvisConversationService.kt`.
   - Remove hardcoded resolution hacks in favor of dynamic screen metric scaling.
4. **Phase E & F (Verification, Build & Documentation)**:
   - Run code-level verifications.
   - Build via GitHub Actions CI and verify APK generation.
   - Produce all mandated markdown deliverables.

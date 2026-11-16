# JARVIS Android Screen Interaction Upgrade --- Changelog

**Release Version:** 2.2.0-screen-interaction  
**Date:** 2026-11-17  
**Module:** Automation, Accessibility & Vision

---

## [2.2.0-screen-interaction] - 2026-11-17

### Added
- **Termux Terminal Integration:**
  - Added `JarvisAccessibilityService.isTerminalSurface()` to detect `com.termux` foreground activity or `TerminalView` nodes.
  - Added `JarvisAccessibilityService.findTerminalView(rootNode)` to locate the active terminal canvas.
  - Added `JarvisAccessibilityService.typeIntoTerminal(text, submit)` implementing focus tap, clipboard staging, fallback context paste, and IME Enter dispatch.
  - Added `JarvisAccessibilityService.sendEnterKey()` for shell command submission.
- **Calibrated Touch Primitives:**
  - Added `JarvisAccessibilityService.performLongPressAtRect(rect, durationMs)` and `longPressAt(x, y, durationMs)` for configurable long presses (500ms - 2000ms).
  - Added `JarvisAccessibilityService.performDoubleTapAtRect(rect)` and `doubleTapAt(x, y)` for rapid double taps (80ms spacing).
  - Added `JarvisAccessibilityService.dragAndDrop(startX, startY, endX, endY, holdDurationMs, dragDurationMs)`.
- **Rich Text Operations:**
  - Added `JarvisAccessibilityService.performTextAction(action, targetRect, textArg, startSel, endSel)` supporting `"select_all"`, `"copy"`, `"cut"`, `"paste"`, `"clear"`, and `"set_selection"`.
- **Targeted Container Scrolling:**
  - Added `JarvisAccessibilityService.performSwipeGesture(startX, startY, endX, endY, durationMs)`.
  - Added `targetContainer`, `containerX`, `containerY` support to `VisualTaskOrchestrator.handleScroll()` to constrain scrolling strictly within specified container bounds.
- **Gemini Live AI Tools:**
  - Registered 4 new tools in `GeminiLiveWebSocket.kt`:
    * `long_press_element`
    * `double_tap_element`
    * `drag_and_drop`
    * `text_action`
  - Enhanced existing `click_ui_element` with `action_type` and `duration_ms`.
  - Enhanced existing `scroll_screen` with `target_container`, `container_x`, and `container_y`.
- **Documentation Deliverables:**
  - `docs/JARVIS_SCREEN_INTERACTION_AUDIT.md`: Complete audit of screen interaction architecture and gaps.
  - `docs/JARVIS_SCREEN_INTERACTION_ROOT_CAUSE.md`: In-depth root cause analysis of Chrome, Termux, and gesture issues.
  - `docs/JARVIS_SCREEN_INTERACTION_IMPLEMENTATION.md`: File-by-file implementation breakdown.
  - `docs/JARVIS_SCREEN_INTERACTION_TEST_REPORT.md`: Test matrix and validation outcomes.
  - `docs/JARVIS_SCREEN_INTERACTION_CHANGELOG.md`: Chronological changelog of all modifications.

### Changed
- `vision/UiGroundingEngine.kt`:
  - Added `isTerminal: Boolean` flag to `UiElementInfo`.
  - Updated `scanScreen()` to capture `com.termux.view.TerminalView` as an actionable element labeled `"Terminal Buffer"`.
  - Updated `findTargetElement()` to resolve aliases `"terminal"`, `"termux"`, `"shell"`, `"console"` directly to the terminal view.
- `automation/agent/ActionVerifier.kt`:
  - Updated `verifyTyping()` to bypass standard `node.text` equality checks when on a terminal surface (`service.isTerminalSurface()`), verifying via screen state changes instead.
- `vision/VisualTaskOrchestrator.kt`:
  - In `handleType()`: Added fast path routing terminal input to `service.typeIntoTerminal()`.
  - In `handleScroll()`: Added container bounds resolution to prevent parent container scroll runaway.
  - Added `handleLongPress()`, `handleDoubleTap()`, `handleDragAndDrop()`, `handleTextAction()`.
- `background/JarvisConversationService.kt`:
  - Added new tools to `isAutomationTool` whitelist and cross-app foreground safety guards.
  - Wired execution of `long_press_element`, `double_tap_element`, `drag_and_drop`, and `text_action`.
  - Forwarded enhanced parameters for `click_ui_element` and `scroll_screen`.

# Implementation Plan - Modern Flat Minimalism UI Update

This plan aims to transform the current BLE Remote UI into a modern, flat, minimalist design. The focus is on clean lines, high-quality typography, and a refined color palette that feels "premium" and "tech-forward".

## User Review Required

> [!IMPORTANT]
> The design will shift from pure black to a deep charcoal theme with neon/cyan accents. This is a common "modern minimalist" aesthetic for remote control apps.

## Proposed Changes

### UI & Theme Refinement

#### [MODIFY] [Color.kt](file:///C:/Users/Nel/AndroidStudioProjects/BLERemote/app/src/main/java/com/example/bleremote/ui/theme/Color.kt)
- Define a new color palette:
    - `Background`: `#0F0F0F` (Deep Charcoal)
    - `Surface`: `#1A1A1A` (Slightly lighter charcoal for cards/buttons)
    - `Accent`: `#00E5FF` (Electric Cyan for active states)
    - `Inactive`: `#666666` (Muted grey for inactive icons)

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Nel/AndroidStudioProjects/BLERemote/app/src/main/java/com/example/bleremote/MainActivity.kt)
- **Refactor `BleRemoteApp`**:
    - Improve the `connectionState` display with a small status dot.
    - Redesign the Power Button:
        - Use a circular container with a subtle border and a central icon.
        - Add a "glow" effect when active (if state allows).
    - Redesign Secondary Buttons:
        - Align them in a balanced horizontal row or a 2x2 grid depending on future expansion.
        - Use consistent sizing and subtle backgrounds.
    - Enhance Spacing and Hierarchy:
        - Use a vertical arrangement that balances the screen real estate.
        - Add padding and use `Arrangement.SpaceBetween` or `SpaceAround`.

### Logic Enhancements (Minor)

- Add a state for the power button (if possible to infer from `connectionState` or message history) to change its color.

## Verification Plan

### Automated Tests
- Build and run the app to ensure no regressions in BLE logic.

### Manual Verification
- Deploy to device and verify the UI matches the "modern flat minimalism" aesthetic.
- Test button interactions (tap, long press) to ensure they still work.

# Test Plan for Shuffle Button State Fix

## Issue Description
The shuffle button in PlayerActivity does not maintain its state correctly across navigation cycles:
1. Enter player, activate shuffle (button shows green)
2. Exit player
3. Re-enter player (button shows green - correct)
4. Exit player again
5. Re-enter player (button shows white/deactivated - incorrect)

## Root Cause
The `updateUIFromService()` method was not calling `updateShuffleButton()` to sync the UI with the local state that was properly saved/restored from SharedPreferences.

## Fix Applied
Added `updateShuffleButton()` and `updateRepeatButton()` calls to the `updateUIFromService()` method to ensure all button states are properly synchronized when the activity resumes.

## Manual Test Steps
1. Open the app and navigate to PlayerActivity
2. Tap the shuffle button to activate shuffle (should turn green)
3. Exit the player (back button or navigation)
4. Re-enter the player
5. Verify shuffle button is still green (1st cycle)
6. Exit the player again
7. Re-enter the player
8. Verify shuffle button is still green (2nd cycle - this was failing before)
9. Repeat steps 6-8 multiple times to ensure consistency

## Expected Result
The shuffle button should maintain its green state across all navigation cycles when shuffle is enabled.

## Code Changes
File: PlayerActivity.kt
Method: updateUIFromService()
Added: updateShuffleButton() and updateRepeatButton() calls
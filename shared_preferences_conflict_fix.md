# SharedPreferences Conflict Fix - Shuffle Button Issue Resolution

## Issue Description
The shuffle button in PlayerActivity was losing its active state after navigation cycles, appearing to work correctly on the first re-entry but showing as inactive (white) on subsequent entries despite the user having enabled shuffle mode.

## Root Cause Analysis
The issue was caused by a **SharedPreferences conflict** between two components:

### 1. PlayerActivity Shuffle Management
- Uses "player_prefs" SharedPreferences with "shuffle_mode" key
- Saves shuffle state when toggled: `prefs.edit().putBoolean("shuffle_mode", isShuffleEnabled).apply()`
- Restores shuffle state in onCreate(): `isShuffleEnabled = prefs.getBoolean("shuffle_mode", false)`

### 2. AlbumDetailFragment Shuffle Management (Conflicting)
- **Also uses the same "player_prefs" file and "shuffle_mode" key**
- Had its own `isShuffleMode` variable that was not synchronized with PlayerActivity
- **Overwrote the SharedPreferences in two places:**
  1. `onStop()` method: Always saved its local `isShuffleMode` value
  2. Shuffle button click handler: Saved its locally managed state

## The Race Condition
1. User enables shuffle in PlayerActivity → Saves `shuffle_mode = true`
2. User navigates to AlbumDetailFragment → Fragment reads old state or has default `false`
3. User navigates back (fragment stops) → Fragment's `onStop()` saves `shuffle_mode = false`
4. User re-enters PlayerActivity → Reads `shuffle_mode = false` (overwritten by fragment)
5. Shuffle button appears inactive despite being previously enabled

## Solution Implemented

### Changes Made to AlbumDetailFragment.kt:

#### 1. Removed Conflicting Save in onStop()
```kotlin
// BEFORE (CONFLICTING)
override fun onStop() {
    // ... other cleanup code ...
    val prefs = requireActivity().getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("shuffle_mode", isShuffleMode).apply()
}

// AFTER (FIXED)
override fun onStop() {
    // ... other cleanup code ...
    // Note: Removed shuffle_mode save to prevent overwriting PlayerActivity's shuffle state
}
```

#### 2. Fixed Shuffle Button Click Handler
```kotlin
// BEFORE (CONFLICTING)
binding.btnShuffle.setOnClickListener {
    // Changed local variable first, then saved
    isShuffleMode = !isShuffleMode
    updateShuffleButton()
    prefs.edit().putBoolean("shuffle_mode", isShuffleMode).apply()
}

// AFTER (FIXED)
binding.btnShuffle.setOnClickListener {
    // Read current state from SharedPreferences first
    val currentShuffleState = prefs.getBoolean("shuffle_mode", false)
    val newShuffleState = !currentShuffleState
    
    // Update SharedPreferences first
    prefs.edit().putBoolean("shuffle_mode", newShuffleState).apply()
    
    // Then update local state to match
    isShuffleMode = newShuffleState
    updateShuffleButton()
}
```

## Key Improvements
1. **Eliminated Race Condition**: AlbumDetailFragment no longer overwrites PlayerActivity's shuffle state
2. **Single Source of Truth**: SharedPreferences "shuffle_mode" key is now managed consistently
3. **Proper State Synchronization**: AlbumDetailFragment reads the current state before modifying it
4. **Maintained Functionality**: Shuffle button in AlbumDetailFragment still works but respects the global state

## Files Modified
- `AlbumDetailFragment.kt`: Removed conflicting SharedPreferences operations

## Expected Result
The shuffle button should now maintain its correct state across all navigation cycles:
1. Enable shuffle in PlayerActivity → Button turns green, state saved
2. Navigate to AlbumDetailFragment → Fragment respects the saved state
3. Navigate back to PlayerActivity → Button remains green (state preserved)
4. Repeat any number of times → State remains consistent

## Testing
This fix should resolve the specific issue described in the debug logs where the shuffle state was being restored as `false` when it should have been `true`, due to the AlbumDetailFragment overwriting the PlayerActivity's saved preference.
# Reproduction Script for Shuffle Button Issue

## Problem Description
The shuffle button in PlayerActivity loses its active state after the second navigation cycle.

## Steps to Reproduce
1. Launch the app
2. Navigate to PlayerActivity
3. Tap the shuffle button to activate it (should turn green)
4. Exit PlayerActivity (back button)
5. Re-enter PlayerActivity
6. Verify shuffle button is green (1st cycle - should work)
7. Exit PlayerActivity again
8. Re-enter PlayerActivity 
9. Check shuffle button state (2nd cycle - THIS IS WHERE IT FAILS)

## Expected Result
Shuffle button should remain green (active) in step 9.

## Actual Result
Shuffle button turns white (inactive) in step 9.

## Root Cause Analysis
1. PlayerActivity stores shuffle state in SharedPreferences ✓
2. MusicService does NOT maintain shuffle state ✓
3. When PlayerActivity reconnects:
   - onCreate() restores isShuffleEnabled from SharedPreferences ✓
   - onServiceConnected() calls updateUIFromService() ✓
   - updateUIFromService() calls updateShuffleButton() ✓
   - updateShuffleButton() uses local isShuffleEnabled variable ✓

## The Problem
The issue might be that the shuffle state restoration in onCreate() happens correctly, but something is resetting the isShuffleEnabled variable between navigation cycles. Need to investigate if:
1. SharedPreferences are being saved correctly
2. SharedPreferences are being restored correctly
3. Something is overwriting the isShuffleEnabled variable

## Investigation Plan
Add logging to track the shuffle state through the lifecycle.
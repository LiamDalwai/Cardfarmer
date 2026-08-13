# Card Farmer

Plays red (attack) cards, fires the special whenever it lights up, ends the turn
when nothing is playable, skips reward screens, restarts the level. Loops.

## Getting the APK

1. Make a new GitHub repo and push this folder to it.
2. Actions builds it automatically. Give it about 3 minutes.
3. Grab the APK from the "latest" release on the repo page, or from the Actions
   run artifacts.
4. Install it on the phone (allow installing from unknown sources).

## First run

1. Open the app, tap step 1, switch Card Farmer on in accessibility settings.
2. Tap step 2, allow drawing over other apps.
3. Open the game, get into a fight, come back, hit START FARMING and accept the
   screen capture prompt. It drops you back to the game.
4. Watch the first fight. If it plays the wrong cards, adjust the coordinates in
   the app and restart it.

## Tuning

- `hand_slots` - centre of each card. Most common thing to get wrong.
- `red_threshold` - raise it if it plays skills or blocks instead of attacks.
- `post_battle_sequence` - the taps that clear rewards and restart the level.
  Add or remove steps to match your flow.

No root. Nothing is injected into the game, it reads the screen and taps.
Auto-play breaks most mobile game ToS, so use an account you can lose.

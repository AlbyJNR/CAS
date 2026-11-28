#!/usr/bin/env bash
# Photographs CAS's signed-in screens on the CI emulator (cas-build.yml, screens). Signs in to the throwaway Stremio
# account in CAS_TEST_EMAIL / CAS_TEST_PASSWORD for an auth key, hands it to the debug build's test hook, then walks
# the remote through Home, the rail, TV, Movies and a title page, taking a picture at each. Fails on a crash.
set -euo pipefail
APK="$1"
mkdir -p screens
BODY=$(jq -n --arg e "$CAS_TEST_EMAIL" --arg p "$CAS_TEST_PASSWORD" '{type:"Login",email:$e,password:$p,facebook:false}')
KEY=$(curl -s -m 30 -H 'content-type: application/json' -d "$BODY" https://api.strem.io/api/login | jq -r '.result.authKey // empty')
[ -n "$KEY" ] || { echo "::error::The test account did not sign in to Stremio"; exit 1; }
echo "::add-mask::$KEY"
# A profile's copy goes by way of the account's library as one hidden item, in the item's poster address (Stremio
# keeps only the fields it knows). This writes one of 60,000 characters and reads it back: "whole" must be true.
NOW=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
BLOB=$(head -c 45000 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_' | head -c 60000)
PUT=$(jq -n --arg k "$KEY" --arg n "$NOW" --arg b "cas1:$BLOB" '{authKey:$k,collection:"libraryItem",changes:[{_id:"cas:copy",name:"CAS: lists and settings",type:"other",poster:$b,posterShape:"poster",removed:true,temp:false,_ctime:$n,_mtime:$n,behaviorHints:{},state:{lastWatched:$n,timeWatched:0,timeOffset:0,overallTimeWatched:0,timesWatched:0,flaggedWatched:0,duration:0,video_id:null,watched:null,noNotif:false}}]}')
curl -s -m 30 -H 'content-type: application/json' -d "$PUT" https://api.strem.io/api/datastorePut | jq -c '{put: (.result // .error)}' > screens/copy-check.txt
GET=$(jq -n --arg k "$KEY" '{authKey:$k,collection:"libraryItem",ids:["cas:copy"],all:false}')
curl -s -m 30 -H 'content-type: application/json' -d "$GET" https://api.strem.io/api/datastoreGet | jq -c --arg b "cas1:$BLOB" '.result[0] | {sent: ($b|length), back: ((.poster // "")|length), whole: (.poster == $b)}' >> screens/copy-check.txt
cat screens/copy-check.txt
shot() { adb exec-out screencap -p > "screens/$1.png"; }
key() { adb shell input keyevent "$1"; sleep "${2:-2}"; }
adb install "$APK" > /dev/null
adb logcat -c
adb shell am start -n uk.albyjnr.cas/dev.alder.ProfilesActivity --es testKey "$KEY" > /dev/null
sleep 35; shot 01-home
# A held OK on the first title: its menu, Trailer first.
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 3; shot 01a-hold-menu
key KEYCODE_BACK 3
# Along the first row to its end and one press past it: the whole row as a grid.
for i in $(seq 1 12); do key KEYCODE_DPAD_RIGHT 1; done; shot 01b-row-end
key KEYCODE_DPAD_RIGHT 6; shot 01c-see-all
key KEYCODE_BACK 3; for i in $(seq 1 12); do key KEYCODE_DPAD_LEFT 1; done
key KEYCODE_DPAD_DOWN 3; shot 02-home-row2
key KEYCODE_DPAD_LEFT 2; shot 03-rail-open
key KEYCODE_DPAD_DOWN 12; shot 04-tv-rail
key KEYCODE_DPAD_RIGHT 3; shot 05-tv
key KEYCODE_DPAD_DOWN 3; key KEYCODE_DPAD_DOWN 6; shot 06-tv-row3
key KEYCODE_DPAD_LEFT 2; key KEYCODE_DPAD_DOWN 12; key KEYCODE_DPAD_RIGHT 3; shot 07-movies
key KEYCODE_DPAD_CENTER 12; shot 08-title
key KEYCODE_DPAD_RIGHT 2; shot 08a-on-trailer
key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_RIGHT 2; shot 08b-right-to-cast
key KEYCODE_DPAD_DOWN 2; key KEYCODE_DPAD_LEFT 2; shot 08c-left-back
key KEYCODE_DPAD_DOWN 3; shot 09-title-tabs
key KEYCODE_DPAD_CENTER 6; shot 11-tab-first-open
key KEYCODE_BACK 3; shot 12-tab-closed
key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_CENTER 6; shot 13-tab-details
key KEYCODE_BACK 3; key KEYCODE_DPAD_UP 2; shot 14-back-on-play
key KEYCODE_DPAD_CENTER 10; shot 15-play-pressed
key KEYCODE_BACK 3; key KEYCODE_BACK 3; key KEYCODE_BACK 3; shot 10-back
# From the rail on Movies: up to Search, then to Settings.
key KEYCODE_DPAD_LEFT 2; key KEYCODE_DPAD_UP 1; key KEYCODE_DPAD_UP 1; key KEYCODE_DPAD_UP 2; key KEYCODE_DPAD_CENTER 8; shot 16-search
key KEYCODE_BACK 3; key KEYCODE_DPAD_UP 2; key KEYCODE_DPAD_CENTER 3; shot 17-settings-menu
key KEYCODE_DPAD_DOWN 2; key KEYCODE_DPAD_CENTER 6; shot 18-settings
key KEYCODE_DPAD_DOWN 2; shot 19-settings-profile
key KEYCODE_BACK 3
# The same account in the dark mode: Home, a row down, and a title page.
adb shell am start -S -n uk.albyjnr.cas/dev.alder.ProfilesActivity --ez casDark true > /dev/null
sleep 30; shot 20-dark-home
key KEYCODE_DPAD_LEFT 2; shot 21-dark-rail
key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_CENTER 12; shot 22-dark-title
key KEYCODE_DPAD_DOWN 3; key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_CENTER 6; shot 23-dark-details
# The ranked lists, opened by the debug build's hook (the short CAS has no genre chips to walk to): films, a genre,
# TV, and two people's.
open() { adb shell "am start --activity-clear-task -n uk.albyjnr.cas/dev.alder.ProfilesActivity --es casOpen '$1'" > /dev/null; sleep "${2:-12}"; }
open ranked; shot 24-dark-top-rated
key KEYCODE_DPAD_DOWN 1; key KEYCODE_DPAD_DOWN 1; key KEYCODE_DPAD_RIGHT 2; shot 24a-dark-top-rated-moved
key KEYCODE_BACK 3
adb shell am start -S -n uk.albyjnr.cas/dev.alder.ProfilesActivity --ez casDark false > /dev/null; sleep 25
open ranked 10
key KEYCODE_DPAD_UP 2; for i in 1 2 3 4 5; do key KEYCODE_DPAD_RIGHT 1; done; sleep 6; shot 25-top-rated-crime
key KEYCODE_BACK 3
open rankedtv; shot 26-top-rated-tv
key KEYCODE_BACK 3
open "person:Martin Scorsese"; shot 27-person
key KEYCODE_DPAD_UP 2; key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_CENTER 10; shot 28-person-top-rated
key KEYCODE_DPAD_DOWN 2; key KEYCODE_DPAD_DOWN 2; shot 28a-person-top-rated-line
key KEYCODE_BACK 3
open "person:Olivia Colman"
key KEYCODE_DPAD_UP 2; key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_CENTER 10; shot 29-person-actress
key KEYCODE_BACK 3
# Live: the page, a card along and down, then a channel playing, the next channel, and Twitch's player.
open live 8; shot 30-live
key KEYCODE_DPAD_RIGHT 1; key KEYCODE_DPAD_DOWN 2; shot 31-live-news
key KEYCODE_DPAD_RIGHT 1; key KEYCODE_DPAD_CENTER 15; shot 32-live-playing
key KEYCODE_DPAD_DOWN 12; shot 33-live-next-channel
key KEYCODE_BACK 3; shot 34-live-back
key KEYCODE_DPAD_DOWN 2; for i in 1 2 3 4 5 6 7 8; do key KEYCODE_DPAD_RIGHT 1; done; shot 35-live-sport
key KEYCODE_DPAD_CENTER 25; shot 36-live-twitch
key KEYCODE_BACK 3
key KEYCODE_DPAD_RIGHT 1; key KEYCODE_DPAD_CENTER 12; shot 37-live-sound-only
key KEYCODE_BACK 3
# The rail with the remote on Live.
adb shell am start -S -n uk.albyjnr.cas/dev.alder.ProfilesActivity > /dev/null; sleep 25
key KEYCODE_DPAD_LEFT 2; for i in 1 2 3; do key KEYCODE_DPAD_DOWN 1; done; sleep 1; shot 38-rail-on-live
key KEYCODE_DPAD_CENTER 6; shot 39-live-from-rail
adb logcat -d | grep -vF "$KEY" > screens/logcat.txt || true
if grep -q "FATAL EXCEPTION" screens/logcat.txt; then echo "::error::CAS crashed on the signed-in walk"; grep -A 20 "FATAL EXCEPTION" screens/logcat.txt | head -40; exit 1; fi

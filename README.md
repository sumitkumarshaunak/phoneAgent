# Phone Agent (v0.1)

Minimal Android agent: reads the screen via an AccessibilityService, asks Gemini for the next
action, executes it, repeats (max 15 steps).

## Run it
1. Install Android Studio and open this folder (it will generate the Gradle wrapper).
2. Get a free key at https://aistudio.google.com/apikey
3. Enable Developer options + USB debugging on your phone, plug in, press Run.
4. In the app: tap "Enable accessibility service", turn on "Phone Agent".
   (If the toggle is greyed out on Android 13+: App info > ⋮ > "Allow restricted settings".)
5. Paste the API key, type a goal, tap Run. Start with harmless goals such as
   "Open Settings and turn on Do Not Disturb".

## Safety built in
- Hard cap of 15 steps per run.
- Blocklist of payment/banking apps (AgentService.BLOCKED_PACKAGES).
- The model is told to stop with ask_user before payments, deletions, posting, passwords.
- Disable the service in Accessibility settings to kill everything instantly.

## Known limits
- Apps with no accessibility labels (games, some Flutter/WebView UIs) can't be read.
- No screenshots yet, text tree only.
- API key is in plain SharedPreferences; fine for personal use only.

## Get an APK without installing anything
1. Create a free GitHub account and a new repository.
2. Upload the contents of this folder (including the hidden .github folder).
3. Open the Actions tab, wait for "Build APK" to finish (3-6 minutes).
4. Open the run, download the "PhoneAgent-debug-apk" artifact, unzip it, and copy app-debug.apk to your phone.
5. On the phone, allow "Install unknown apps" for your file manager or browser, then tap the APK.

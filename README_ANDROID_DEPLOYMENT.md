# MediConnect Android Setup and Run Guide (Beginner Friendly)

This guide helps you run the project from zero setup and then install it on your Android phone.

## 1) What you will install

- Android Studio
- Java 17 (required by Android Gradle Plugin)
- Python 3.10+ for backend
- Git (optional, if you clone again)

## 2) Install Android Studio and required SDK tools

1. Download and install Android Studio from the official website.
2. Open Android Studio and complete the first-run setup wizard.
3. Open SDK Manager:
   - File -> Settings -> Android SDK
4. Install these SDK components:
   - Android SDK Platform 34 or newer
   - Android SDK Build-Tools (latest)
   - Android SDK Platform-Tools
   - Android Emulator
5. Open SDK Tools tab and install:
   - Android SDK Command-line Tools (latest)
   - Intel HAXM or Windows Hypervisor support (whichever your machine supports)

## 3) Ensure Java 17 is available

1. Install JDK 17.
2. In Android Studio:
   - File -> Settings -> Build, Execution, Deployment -> Build Tools -> Gradle
3. Set Gradle JDK to a Java 17 installation.

## 4) Open the MediConnect project

1. Open Android Studio.
2. Click Open and choose this folder:
   - MediConnect
3. Wait for Gradle sync to finish.

## 5) Backend setup (required for chat, triage, explain, voice)

1. Open terminal in project root.
2. Go to backend folder:
   - cd mediconnect_backend
3. Create or edit environment file:
   - .env
4. Add your Gemini key:
   - GEMINI_API_KEY=your_actual_key
5. Install backend dependencies:
   - pip install -r requirements.txt
6. Start backend server:
   - python app.py
7. Keep this terminal running while testing.

## 6) Configure Android app API base URL

Edit file:
- app/src/main/java/network/RetrofitClient.kt

Choose one value based on target device:

- Android Emulator:
  - http://10.0.2.2:5000/
- Physical Android phone on same Wi-Fi as laptop:
  - http://YOUR_LAPTOP_LAN_IP:5000/

Example:
- http://192.168.1.15:5000/

Important:
- Port must match backend (default 5000).
- URL must end with slash.

## 7) Run on Android Emulator first

1. Open Device Manager in Android Studio.
2. Create device:
   - Pixel 6 (or any modern profile)
   - API 34 image (Google APIs)
3. Start emulator.
4. In Android Studio toolbar, select emulator and click Run.

## 8) Functional checks in the app

1. Login to app.
2. Open Symptom Checker screen.
3. Enter Patient ID in the Patient ID field.
4. Select category (maternal, child, tb, general).
5. Test Add Vitals:
   - Tap Add Vitals
   - Fill values
   - Save
   - Verify confirmation appears in chat
6. Test Run Triage:
   - Tap Run Triage
   - Verify risk + anomaly output
   - Verify explainability message follows
7. Test Voice Ask:
   - Enter question and tap Voice Ask
8. Test Guided Vitals:
   - Tap Guided Vitals and confirm prompts continue
9. Test mic input:
   - Tap mic button and speak a symptom

## 9) What is now persisted and backed up

- Manual vitals are stored locally in Room DB with current timestamp.
- Manual vitals are also pushed to Firebase Firestore collection:
  - patient_vitals

This reduces device-loss risk because manual vitals are backed up to cloud when network is available.

## 10) Move to real Android phone

1. Enable Developer Options on phone:
   - Settings -> About phone -> tap Build number 7 times
2. Enable USB Debugging:
   - Settings -> Developer options -> USB debugging
3. Connect phone by USB.
4. Allow debugging prompt on phone.
5. In Android Studio, select your phone as run target.
6. Ensure base URL uses laptop LAN IP (not 10.0.2.2).
7. Click Run.

## 11) Optional: generate APK and install manually

1. Build menu -> Build Bundle(s) / APK(s) -> Build APK(s)
2. After build completes, click locate.
3. APK path is usually:
   - app/build/outputs/apk/debug/app-debug.apk
4. Copy APK to phone and install (allow unknown sources if prompted).

## 12) Troubleshooting

### App cannot connect to backend

- Confirm backend terminal is running.
- Verify URL in RetrofitClient.kt.
- Emulator must use 10.0.2.2.
- Physical phone must use LAN IP of laptop.
- Ensure phone and laptop are on same Wi-Fi.
- Check firewall allows inbound port 5000.

### Build fails with Java error

- Re-check Gradle JDK is Java 17.

### Firebase not syncing vitals

- Verify google-services.json exists in app module.
- Ensure Firebase project is configured for this app id.
- Check internet connectivity.

### Voice features are weak or empty

- Verify GEMINI_API_KEY is set correctly in mediconnect_backend/.env.
- Restart backend after changing .env.

## 13) Quick run checklist

- Backend running
- Correct BASE_URL configured
- App launches on emulator
- Add Vitals saves successfully
- Run Triage uses entered patient/category
- Explainability response appears
- Voice Ask response appears
- Guided Vitals flow responds
- Ready for phone deployment

# FeaturesticBox SDK — Universal Loader Integration Guide (Zero-Crash Edition)

`FeaturesticBox` is a self-initializing Android 7–16 (API 24–36) Virtualization & Game Loader Engine supporting **32-bit (`armeabi-v7a`, `x86`)**, **64-bit (`arm64-v8a`, `x86_64`)**, **Instant Zero-Copy OBB Redirection**, and **Universal Drop-In Loader Compatibility**.

---

## 1. Fastest 1-Line Integration (Works in ANY Random Loader Activity!)

Because `FeaturesticBox` now **auto-initializes itself** across `:main`, `:core`, and `:p0..:p24` via its built-in ContentProviders, you don't even need a custom `Application` class! Inside any Button click in your `MainActivity`:

### Java:
```java
import com.featurestic.box.FeaturesticLoader;

// Auto-initializes SDK, auto-clones game from phone if not installed yet, links OBB in 0ms, and launches!
FeaturesticLoader.launchGame(this, "com.pubg.imobile");
```

### Kotlin:
```kotlin
import com.featurestic.box.FeaturesticLoader

FeaturesticLoader.launchGame(this, "com.pubg.imobile")
```

---

## 2. Standard `FeaturesticBoxCore` Integration (Optional `Application` Setup)

If your Loader has a custom `Application` class, all 3 initialization styles work:

### Style A — Simple 1-Line Init:
```java
FeaturesticBoxCore.get().init(this);
```

### Style B — 1-Arg `doAttachBaseContext`:
```java
@Override
protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    FeaturesticBoxCore.get().doAttachBaseContext(base);
}

@Override
public void onCreate() {
    super.onCreate();
    FeaturesticBoxCore.get().doCreate();
}
```

### Style C — Custom `ClientConfiguration`:
```kotlin
override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    FeaturesticBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
        override fun getHostPackageName(): String = base.packageName
        override fun isHideRoot(): Boolean = true
        override fun isHideXposed(): Boolean = true
    })
}
```

---

## 3. Drop-In Compatibility With Existing Loader Templates (Zero Code Changes!)

This SDK includes built-in package/class bridges for the most common Loader templates:

1. **FeaturesticBox Native:**
   - `com.featurestic.box.FeaturesticLoader`
   - `com.featurestic.box.FeaturesticBoxCore`
2. **Mundo / Meta (`net_62v.external.*`) Loaders:**
   - `net_62v.external.MetaCore`
   - `net_62v.external.MetaApplicationInstaller` (`cloneApp`, `installPackage`, `installPackageFromApk`)
   - `net_62v.external.MetaActivityManager` (`launchApp`, `startActivity`, `killApp`, `isAppRunning`)
   - `net_62v.external.MetaPackageManager` (`isInnerAppInstalled`, `getInstalledInnerApps`, `uninstallApp`, `cleanAppData`)
   - `net_62v.external.MetaActivationManager` / `FeaturesticActivationManager`
   - `net_62v.external.MetaStorageManager` / `FeaturesticStorageManager`
3. **BlackBox (`top.niunaijun.blackbox.*`) Loaders:**
   - `top.niunaijun.blackbox.BlackBoxCore`
   - `top.niunaijun.blackbox.app.configuration.ClientConfiguration`
   - `top.niunaijun.blackbox.app.configuration.AppLifecycleCallback`
4. **VCore (`com.vcore.*`) Loaders:**
   - `com.vcore.BlackBoxCore`
   - `com.vcore.app.configuration.ClientConfiguration`
   - `com.vcore.app.configuration.AppLifecycleCallback`

---

## 4. Building the Release `.aar`

### Option A — Android Studio / Local Terminal:
```bash
./gradlew :FeaturesticBox:assembleRelease
```
Output AAR location:
`FeaturesticBox/build/outputs/aar/FeaturesticBox-release.aar`

### Option B — Free 1-Click Cloud Build via GitHub Actions (No PC Needed):
1. Upload this project to a private GitHub repository.
2. Open the **Actions** tab and run the **Android CI** workflow (`.github/workflows/android.yml`).
3. Download `FeaturesticBox-release-aar` directly from the completed workflow artifacts.

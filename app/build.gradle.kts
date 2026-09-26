plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "com.lana.kitchen"
  compileSdk = 34
  defaultConfig { applicationId = "com.lana.kitchen"; minSdk = 24; targetSdk = 34; versionCode = 2; versionName = "1.1" }
  buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}

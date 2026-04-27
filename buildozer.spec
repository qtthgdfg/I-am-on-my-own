[app]
title = Simple Notes App
package.name = simplenotes
package.domain = org.example
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json
version = 1.0
requirements = python3,kivy==2.3.0,plyer,android
orientation = portrait
osx.python_version = 3
osx.kivy_version = 2.3.0
fullscreen = 0
android.permissions = INTERNET,WRITE_EXTERNAL_STORAGE,READ_EXTERNAL_STORAGE,VIBRATE
android.api = 30
android.minapi = 21
android.ndk = 23b
android.sdk = 30
android.gradle_dependencies = 
android.arch = armeabi-v7a
android.allow_backup = True
ios.kivy_version = 2.3.0

[buildozer]
log_level = 2
warn_on_root = 1

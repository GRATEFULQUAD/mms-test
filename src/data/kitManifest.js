// Each Android source file is a real file under ./kit/, imported as raw text
// via Vite's ?raw so no string-escaping is ever needed. On disk the kit is a
// valid standalone Gradle project: open src/data/kit directly in Android Studio.
import readme from './kit/README.md?raw';
import settingsGradle from './kit/settings.gradle.kts?raw';
import rootBuild from './kit/build.gradle.kts?raw';
import gradleProperties from './kit/gradle.properties?raw';
import gradleWrapperProps from './kit/gradle/wrapper/gradle-wrapper.properties?raw';
import appBuild from './kit/app/build.gradle.kts?raw';
import manifest from './kit/app/src/main/AndroidManifest.xml?raw';
import filePaths from './kit/app/src/main/res/xml/file_paths.xml?raw';
import activityMain from './kit/app/src/main/res/layout/activity_main.xml?raw';
import strings from './kit/app/src/main/res/values/strings.xml?raw';
import mainActivity from './kit/app/src/main/java/com/mmstest/MainActivity.kt?raw';
import mmsSender from './kit/app/src/main/java/com/mmstest/mms/MmsSender.kt?raw';
import videoTranscoder from './kit/app/src/main/java/com/mmstest/mms/VideoTranscoder.kt?raw';
import diagLogger from './kit/app/src/main/java/com/mmstest/mms/DiagnosticLogger.kt?raw';

export const KIT_FILES = [
  { path: 'README.md', lang: 'markdown', content: readme },
  { path: 'settings.gradle.kts', lang: 'kotlin', content: settingsGradle },
  { path: 'build.gradle.kts', lang: 'kotlin', content: rootBuild },
  { path: 'gradle.properties', lang: 'properties', content: gradleProperties },
  { path: 'gradle/wrapper/gradle-wrapper.properties', lang: 'properties', content: gradleWrapperProps },
  { path: 'app/build.gradle.kts', lang: 'kotlin', content: appBuild },
  { path: 'app/src/main/AndroidManifest.xml', lang: 'xml', content: manifest },
  { path: 'app/src/main/res/xml/file_paths.xml', lang: 'xml', content: filePaths },
  { path: 'app/src/main/res/layout/activity_main.xml', lang: 'xml', content: activityMain },
  { path: 'app/src/main/res/values/strings.xml', lang: 'xml', content: strings },
  { path: 'app/src/main/java/com/mmstest/MainActivity.kt', lang: 'kotlin', content: mainActivity },
  { path: 'app/src/main/java/com/mmstest/mms/MmsSender.kt', lang: 'kotlin', content: mmsSender },
  { path: 'app/src/main/java/com/mmstest/mms/VideoTranscoder.kt', lang: 'kotlin', content: videoTranscoder },
  { path: 'app/src/main/java/com/mmstest/mms/DiagnosticLogger.kt', lang: 'kotlin', content: diagLogger },
];

export const KIT_INTENT = `A throwaway native Android app whose only job is to prove video MMS sending
works on your exact phone/carrier. Open this folder directly in Android Studio,
sync, build, install, and press Send. If the recipient gets the video, this
whole kit becomes a known-working reference to hand to Rocket.`;
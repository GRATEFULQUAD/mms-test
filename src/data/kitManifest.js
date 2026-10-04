// Each Android source file is a real file under ./kit/, imported as raw text
// via Vite's ?raw so no string-escaping is ever needed.
import readme from './kit/README.md?raw';
import settingsGradle from './kit/settings.gradle.kts?raw';
import rootBuild from './kit/build.root.gradle.kts?raw';
import appBuild from './kit/build.app.gradle.kts?raw';
import manifest from './kit/AndroidManifest.xml?raw';
import filePaths from './kit/file_paths.xml?raw';
import activityMain from './kit/activity_main.xml?raw';
import strings from './kit/strings.xml?raw';
import mainActivity from './kit/MainActivity.kt?raw';
import mmsSender from './kit/MmsSender.kt?raw';
import videoTranscoder from './kit/VideoTranscoder.kt?raw';
import diagLogger from './kit/DiagnosticLogger.kt?raw';

export const KIT_FILES = [
  { path: 'README.md', lang: 'markdown', content: readme },
  { path: 'settings.gradle.kts', lang: 'kotlin', content: settingsGradle },
  { path: 'build.gradle.kts', lang: 'kotlin', content: rootBuild },
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
works on your exact phone/carrier. Paste these files into a new Android Studio
project, build, install, and press Send. If the recipient gets the video, this
whole kit becomes a known-working reference to hand to Rocket.`;
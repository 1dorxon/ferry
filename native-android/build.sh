#!/bin/sh
# Build, align and sign Ferry, the native Android app (formerly BrowserDrop).
#
#   native-android/build.sh   -> native-android/dist/ferry-<version>.apk  (sideload / website)
#                                native-android/dist/ferry-<version>.aab  (Google Play upload)
#
# Same package and same key as the Trusted Web Activity it replaces
# (~/.luitlabs-secrets/browserdrop-android.jks), so it installs as an update.
# Bump versionCode in app/build.gradle.kts for every release.
set -eu
cd "$(dirname "$0")"
# Signing key + its passwords; override FERRY_SECRETS to use your own.
SEC=${FERRY_SECRETS:-$HOME/.luitlabs-secrets}
# Ferry's own build image (native-android/docker/Dockerfile), built on first use.
# Bump IMAGE_TAG whenever the Dockerfile changes.
IMAGE_TAG=1
IMAGE=ferry-android-build:$IMAGE_TAG
CACHE=${FERRY_GRADLE_CACHE:-$HOME/.cache/ferry-gradle}
. "$SEC/browserdrop-android.env"
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
mkdir -p dist "$CACHE" "$CACHE/m2"   # m2: Robolectric's Android jar, fetched once by the screen tests
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -t "$IMAGE" docker

docker run --rm --memory=3g --memory-swap=3g --user "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  -v "$CACHE":/gradle -e GRADLE_USER_HOME=/gradle -v "$CACHE/m2":/home/ubuntu/.m2 \
  "$IMAGE" ./gradlew --no-daemon -q testDebugUnitTest assembleRelease bundleRelease

docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/work" -w /work -v "$SEC:/sec:ro" \
  -e PW="$ANDROID_KEYSTORE_PASSWORD" "$IMAGE" sh -c "
  BT=/opt/android-sdk/build-tools/36.0.0
  \$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/aligned.apk
  \$BT/apksigner sign --ks /sec/browserdrop-android.jks --ks-key-alias $ANDROID_KEY_ALIAS \
    --ks-pass env:PW --key-pass env:PW --out dist/ferry-$VERSION.apk /tmp/aligned.apk
  \$BT/apksigner verify dist/ferry-$VERSION.apk
  jarsigner -keystore /sec/browserdrop-android.jks -storepass:env PW -keypass:env PW \
    -sigalg SHA256withRSA -digestalg SHA-256 -signedjar dist/ferry-$VERSION.aab \
    app/build/outputs/bundle/release/app-release.aab $ANDROID_KEY_ALIAS >/dev/null
  jarsigner -verify dist/ferry-$VERSION.aab | head -1"
rm -f dist/*.idsig
echo "built native-android/dist/ferry-$VERSION.apk and .aab"

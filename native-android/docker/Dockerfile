# Ferry's Android build environment. Owned by this repo -- no other project's
# image is involved -- and fully pinned, so the same toolchain can be rebuilt
# from scratch on any machine (a server, CI, a new laptop).
#
# Contents: JDK 21 (also provides jarsigner/keytool), the Android command-line
# tools, one SDK platform and its build tools (aapt2, zipalign, apksigner).
# Gradle itself comes from the project's wrapper (gradle-wrapper.properties).
#
# native-android/build.sh builds this on first use as ferry-android-build:<IMAGE_TAG>.
# Bump IMAGE_TAG there whenever this file changes.

# eclipse-temurin:21-jdk-noble, pinned to the digest it had on 2026-10-21.
FROM eclipse-temurin@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964

ARG CMDLINE_TOOLS=13114758
ARG CMDLINE_TOOLS_SHA1=5fdcc763663eefb86a5b8879697aa6088b041e70
# Keep in step with compileSdk / the apksigner path in build.sh.
ARG PLATFORM=android-36
ARG BUILD_TOOLS=36.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/$BUILD_TOOLS

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
 && echo "${CMDLINE_TOOLS_SHA1}  /tmp/tools.zip" | sha1sum -c - \
 && mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" \
 && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
 && rm /tmp/tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS}" > /dev/null \
 && chmod -R a+rX "$ANDROID_HOME"

WORKDIR /work

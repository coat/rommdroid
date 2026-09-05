{
  description = "RomMDroid — Android ROM manager client for RomM";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # ── Android SDK configuration ─────────────────────────────────────────
        # Bump these to upgrade the SDK toolchain; everything else follows.
        androidSdkConfig = {
          buildToolsVersions = [ "35.0.0" ];
          platformVersions   = [ "35" ];       # compileSdk / targetSdk
          abiVersions        = [ "arm64-v8a" "x86_64" ];
          includeEmulator    = true;
          includeSystemImages = false;          # set true if you want emulator images
          systemImageTypes   = [ "google_apis_playstore" ];
          cmdLineToolsVersion = "13.0";
          platformToolsVersion = "37.0.1";
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages androidSdkConfig;
        androidSdk = androidComposition.androidsdk;

        # ── Build inputs ──────────────────────────────────────────────────────
        buildInputs = with pkgs; [
          # JVM
          jdk21

          # Build tools
          gradle
          kotlin

          # Android SDK
          androidSdk

          # API code generation from RomM's OpenAPI spec
          openapi-generator-cli

          # ADB / fastboot
          android-tools

          # SVG -> PNG for design/logo/generate-icons.py
          resvg

          # Misc dev utils
          jq
          curl
          git
        ];

        # Android Studio is large; include it only when ROMMDROID_WITH_AS=1
        # so `nix develop` stays fast for CI/CLI use.
        # Override: `nix develop .#withStudio`
        studioInputs = buildInputs ++ [ pkgs.android-studio ];

        # ── Shell hook: point Gradle + SDK at the Nix-managed SDK ────────────
        androidEnvHook = ''
          export ANDROID_SDK_ROOT="${androidSdk}/libexec/android-sdk"
          export ANDROID_HOME="$ANDROID_SDK_ROOT"
          export JAVA_HOME="${pkgs.jdk21}"

          # Gradle writes caches here; keep them out of the source tree
          export GRADLE_USER_HOME="$HOME/.gradle"

          # Make adb / emulator available without full PATH tricks
          export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

          # AGP downloads its own aapt2 from Maven — a generic-linux binary that
          # NixOS cannot exec. Point it at the SDK's patchelf'd copy instead.
          # Passed as -D rather than in gradle.properties so the /nix/store path
          # stays out of the repo, and because the property name contains dots
          # (ORG_GRADLE_PROJECT_* env vars cannot express those).
          export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_SDK_ROOT/build-tools/${builtins.head androidSdkConfig.buildToolsVersions}/aapt2"

          echo "RomMDroid dev shell ready"
          echo "  ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
          echo "  JAVA_HOME        = $JAVA_HOME"
          echo "  gradle $(gradle --version 2>/dev/null | grep '^Gradle' | awk '{print $2}')"
        '';

      in {
        # ── devShells ─────────────────────────────────────────────────────────

        devShells = {
          # Default: CLI build only (fast, no Android Studio)
          default = pkgs.mkShell {
            inherit buildInputs;
            shellHook = androidEnvHook;
          };

          # Full: includes Android Studio
          # Usage: nix develop .#withStudio
          withStudio = pkgs.mkShell {
            buildInputs = studioInputs;
            shellHook = androidEnvHook + ''
              echo "  Android Studio included — launch with: android-studio"
            '';
          };
        };

        # ── Packages ──────────────────────────────────────────────────────────
        # Build the debug APK via Gradle
        packages.default = pkgs.stdenv.mkDerivation {
          pname = "rommdroid";
          version = "0.1.0";
          src = ./.;

          buildInputs = buildInputs;

          buildPhase = ''
            export ANDROID_SDK_ROOT="${androidSdk}/libexec/android-sdk"
            export ANDROID_HOME="$ANDROID_SDK_ROOT"
            export JAVA_HOME="${pkgs.jdk21}"
            export GRADLE_USER_HOME="$TMPDIR/.gradle"
            gradle assembleDebug --no-daemon \
              -Dorg.gradle.project.android.aapt2FromMavenOverride="$ANDROID_SDK_ROOT/build-tools/${builtins.head androidSdkConfig.buildToolsVersions}/aapt2"
          '';

          installPhase = ''
            mkdir -p $out
            cp app/build/outputs/apk/debug/app-debug.apk $out/rommdroid-debug.apk
          '';
        };
      }
    );
}

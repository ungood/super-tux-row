{
  description = "SuperTuxRow: rowing games for the WaterRower S4";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          buildToolsVersion = "35.0.0";
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          };
          androidHome = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [
              # For the protocol tools in tools/.
              (pkgs.python3.withPackages (ps: [ ps.pyserial ]))
              pkgs.just
              # adb, for the Android tablet.
              pkgs.android-tools
              android.androidsdk
              pkgs.gradle
              pkgs.jdk17
            ];

            ANDROID_HOME = androidHome;
            ANDROID_SDK_ROOT = androidHome;
            JAVA_HOME = pkgs.jdk17.home;
            # Gradle otherwise downloads a generic aapt2 binary, which doesn't run on NixOS.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidHome}/build-tools/${buildToolsVersion}/aapt2";
          };
        }
      );
    };
}

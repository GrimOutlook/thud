{
  description = "tHUD Android development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      # Google's Linux SDK build tools are distributed for x86_64 hosts.
      systems = [ "x86_64-linux" ];
      forEachSystem = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forEachSystem (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          buildToolsVersion = "36.0.0";
          android = pkgs.androidenv.composeAndroidPackages {
            buildToolsVersions = [ buildToolsVersion ];
            platformVersions = [ "35" ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          };
          sdk = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [ android.androidsdk pkgs.jdk17 pkgs.kotlin pkgs.git pkgs.unzip ];
            JAVA_HOME = pkgs.jdk17.home;
            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;
            # Google's Maven aapt2 binary cannot run directly on NixOS.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
          };
        });
    };
}

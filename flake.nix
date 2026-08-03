{
  description = "dok2 - offline GPS track recorder for hiking (Android, Kotlin)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;             # Android SDK is unfree
            android_sdk.accept_license = true;
          };
        };

        androidSdk = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" ];       # compileSdk (AGP); 36/37 also available
          buildToolsVersions = [ "35.0.0" ];
          includeEmulator = false;
          includeNDK = false;
          includeSystemImages = false;
        };
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21                    # JDK for Gradle + Android Gradle Plugin
            gradle                   # wrapper bootstrap + CLI builds
            androidSdk.androidsdk    # platforms, build-tools, platform-tools (adb)
            tilemaker                # .osm.pbf -> .pmtiles offline maps (README workflow)
            gdal                     # ogr2ogr for map/elevation data prep
            wget                     # download Geofabrik extracts + SRTM .hgt
          ];

          # Android toolchain wiring
          ANDROID_HOME = "${androidSdk.androidsdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk.androidsdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk21.home}";

          shellHook = ''
            echo "dok2 dev shell: JDK ${pkgs.jdk21.version} + Android SDK (compileSdk 35)"
          '';
        };
      });
}

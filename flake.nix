{
  description = "Yardhal — Android IRC client development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      jdk = pkgs.jdk21_headless;

      androidSdk = pkgs.androidenv.composeAndroidPackages {
        cmdLineToolsVersion = "11.0";
        buildToolsVersions = [ "35.0.0" ];
        platformVersions = [ "35" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
        includeNDK = false;
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          jdk
          pkgs.gradle
          pkgs.git
        ];

        YARDHAL_SDK_TEMPLATE = "${androidSdk.androidsdk}";
        JAVA_HOME = "${jdk.home}";

        shellHook = ''
          export YARDHAL_SDK_TEMPLATE="${androidSdk.androidsdk}"
          export JAVA_HOME="${jdk.home}"
          bash scripts/ensure-sdk.sh
          export YARDHAL_SDK_CLONE="''${YARDHAL_SDK_CLONE:-$HOME/.cache/yardhal/android-sdk}"
          export ANDROID_HOME="$YARDHAL_SDK_CLONE/libexec/android-sdk"
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
          echo "Yardhal dev shell ready: ANDROID_HOME=$ANDROID_HOME"
        '';
      };
    };
}

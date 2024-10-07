{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    devenv.url = "github:cachix/devenv";
    clj-nix.url = "github:jlesquembre/clj-nix";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };
  nixConfig = {
    extra-trusted-public-keys = "devenv.cachix.org-1:w1cLUi8dv3hnoSPGAuibQv+f9TZLr6cv/Hm9XgU50cw=";
    extra-substituters = "https://devenv.cachix.org";
  };
  outputs = {
    self,
    nixpkgs,
    devenv,
    clj-nix,
    ...
  } @ inputs: {
    formatter = builtins.mapAttrs (system: pkgs: pkgs.alejandra) nixpkgs.legacyPackages;
    devShells =
      builtins.mapAttrs (system: pkgs: let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };
      in {
        default = devenv.lib.mkShell {
          inherit inputs pkgs;
          modules = [
            (
              {
                config,
                pkgs,
                ...
              }: {
                # https://devenv.sh/reference/options/
                packages = [
                  pkgs.git
                  pkgs.babashka
                  pkgs.jet
                  pkgs.neovim
                  pkgs.cljfmt
                  pkgs.nodePackages.prettier
                  #pkgs.vscode
                  (pkgs.vscode-with-extensions.override {
                    vscodeExtensions = [
                      pkgs.vscode-extensions.betterthantomorrow.calva
                      pkgs.vscode-extensions.vscodevim.vim
                      pkgs.vscode-extensions.jnoortheen.nix-ide
                    ];
                  })
                ];

                languages.clojure.enable = true;

                # N.B. picks up quotes and inline comments
                dotenv.enable = true;

                scripts.format.exec = ''
                  nix fmt
                  cljfmt fix .
                  prettier -w **/*.js
                '';
                scripts.lock.exec = ''
                  nix flake lock
                  nix run .#deps-lock
                '';
                scripts.update.exec = ''
                  nix flake update
                  nix run .#deps-lock
                '';
                scripts.build.exec = ''
                  nix build .
                '';

                enterShell = ''
                  # start editor
                  code .
                '';
              }
            )
          ];
        };
      })
      nixpkgs.legacyPackages;
    packages =
      builtins.mapAttrs (system: pkgs: {
        deps-lock = clj-nix.packages.${system}.deps-lock;
        container = pkgs.dockerTools.buildLayeredImage {
          name = "lnd-boost-scraper";
          config = {
            Entrypoint = ["${self.packages.${system}.default}/bin/lnd-boost-scraper"];
          };
        };
        default = clj-nix.lib.mkCljApp {
          inherit pkgs;
          modules = [
            {
              projectSrc = ./.;
              name = "com.noblepayne/lnd-boost-scraper";
              main-ns = "boost-scraper.core";
              compileCljOpts = {
                java-opts = [
                  "--add-opens=java.base/java.nio=ALL-UNNAMED"
                  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
                  "-Djdk.internal.httpclient.disableHostnameVerification"
                ];
              };
              java-opts = [
                "--add-opens=java.base/java.nio=ALL-UNNAMED"
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
                "-Djdk.internal.httpclient.disableHostnameVerification"
              ];
            }
          ];
        };
      })
      nixpkgs.legacyPackages;
    nixosModules = {
      default = {
        options,
        config,
        pkgs,
        ...
      }: {
        imports = [./module.nix];
        config.services.lnd-boost-scraper.pkg = self.packages.${pkgs.system}.default;
      };
    };
  };
}

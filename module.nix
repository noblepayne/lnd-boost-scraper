{
  config,
  lib,
  pkgs,
  ...
}:
with lib; let
  options.services.lnd-boost-scraper = {
    enable =
      mkEnableOption "lnd-boost-scraper, for scraping them boosts";
    uiAddress = mkOption {
      type = types.str;
      default = "127.0.0.1";
      description = "Address to listen for UI connections.";
    };
    uiPort = mkOption {
      type = types.port;
      default = 9999; # FIXME: real port
      description = "Port to listen for UI connections.";
    };
    user = mkOption {
      type = types.str;
    };
    group = mkOption {
      type = types.str;
    };
    albyTokenPath = mkOption {
      type = types.str;
    };
    jbnodeMacaroonPath = mkOption {
      type = types.str;
    };
    nodecanMacaroonPath = mkOption {
      type = types.str;
    };
    pkg = mkOption {
      type = types.package;
      defaultText = "config.lnd-boost-scraper.pkg";
      description = "The package providing lightning-terminal binaries.";
    };
    dataDir = mkOption {
      type = types.path;
      default = "/var/lib/lightning-terminal";
      description = "The data directory for lightning-terminal.";
    };
  };
  cfg = config.services.lnd-boost-scraper;
in {
  inherit options;

  config = mkIf cfg.enable {
    # environment.systemPackages = [cfg.package]; # (hiPrio cfg.cli) ];
    systemd.tmpfiles.rules = [
      "d '${cfg.dataDir}' 0770 ${cfg.user} ${cfg.group} - -"
      "d '${cfg.dataDir}/alby' 0770 ${cfg.user} ${cfg.group} - -"
      "d '${cfg.dataDir}/jbnode' 0770 ${cfg.user} ${cfg.group} - -"
      "d '${cfg.dataDir}/nodecan' 0770 ${cfg.user} ${cfg.group} - -"
    ];

    systemd.services.lnd-boost-scraper = {
      wantedBy = ["multi-user.target"];
      # requires = ["lnd.service"];
      # after = [
      #   "lnd.service"
      #   "nix-bitcoin-secrets.target"
      # ];
      serviceConfig = {
        ExecStart = "${cfg.pkg}/bin/lnd-boost-scraper";
        User = cfg.user;
        Group = cfg.group;
        Restart = "on-failure";
        RestartSec = "10s";
        ReadWritePaths = [cfg.dataDir];
        # TODO: skip? configurable?
        ReadOnlyPaths = ["/nix" "/etc" "/var"];
        WorkingDirectory = cfg.dataDir;
      };
      environment = {
        ALBY_DBI = "${cfg.dataDir}/alby";
        JBNODE_DBI = "${cfg.dataDir}/jbnode";
        NODECAN_DBI = "${cfg.dataDir}/nodecan";
        ALBY_TOKEN_PATH = cfg.albyTokenPath;
        JBNODE_MACAROON_PATH = cfg.jbnodeMacaroonPath;
        NODECAN_MACAROON_PATH = cfg.nodecanMacaroonPath;
        SCRAPER_UIPORT = builtins.toString cfg.uiPort;
      };
    };
  };
}

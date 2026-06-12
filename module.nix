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
    zapriteApiKeyPath = mkOption {
      type = types.str;
      default = "";
      description = "Path to the Zaprite API key file. Empty to disable.";
    };
    r2AccessKeyIdPath = mkOption {
      type = types.str;
      default = "";
      description = "Path to the R2 access key ID file. Empty to disable.";
    };
    r2SecretAccessKeyPath = mkOption {
      type = types.str;
      default = "";
      description = "Path to the R2 secret access key file. Empty to disable.";
    };
    r2AccountId = mkOption {
      type = types.str;
      default = "";
      description = "R2 Cloudflare account ID (subdomain of r2.cloudflarestorage.com).";
    };
    r2BoostBucket = mkOption {
      type = types.str;
      default = "";
      description = "R2 bucket name for member boost records.";
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
        ZAPRITE_API_KEY_PATH = cfg.zapriteApiKeyPath;
        R2_ACCESS_KEY_ID_PATH = cfg.r2AccessKeyIdPath;
        R2_SECRET_ACCESS_KEY_PATH = cfg.r2SecretAccessKeyPath;
        R2_ACCOUNT_ID = cfg.r2AccountId;
        R2_BOOST_BUCKET = cfg.r2BoostBucket;
      };
    };
  };
}

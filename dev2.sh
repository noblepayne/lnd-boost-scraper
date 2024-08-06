#!/usr/bin/env sh
clj -Sdeps '{:deps {datalevin/datalevin {:mvn/version "0.8.25"} nrepl/nrepl {:mvn/version "1.1.0"} org.babashka/http-client {:mvn/version "0.3.11"} org.clojure/core.async {:mvn/version "1.6.681"}}}' -J--add-opens -J"java.base/java.nio=ALL-UNNAMED" -J--add-opens -J"java.base/sun.nio.ch=ALL-UNNAMED" -J-D"jdk.internal.httpclient.disableHostnameVerification" -M -m nrepl.cmdline

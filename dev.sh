#!/usr/bin/env sh
bb -cp $(clj -Spath -Sdeps '{:deps {djblue/portal {:mvn/version "0.38.1"}}}') nrepl-server

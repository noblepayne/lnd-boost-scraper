#!/usr/bin/env sh
nix develop --impure path:. -c $SHELL

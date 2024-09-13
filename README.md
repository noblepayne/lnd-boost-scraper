<br/>
<p align="center">
  <h3 align="center">LND Boost Scraper</h3>

  <p align="center">
    BOOOOOOST
    <br/>
    <br/>
  </p>
</p>

![License](https://img.shields.io/github/license/noblepayne/lnd-boost-scraper) 

## Table Of Contents

* [About the Project](#about-the-project)
* [Built With](#built-with)
* [Getting Started](#getting-started)
  * [Prerequisites](#prerequisites)
* [Usage](#usage)
* [Roadmap](#roadmap)
* [Contributing](#contributing)
* [License](#license)

## About The Project
Fetches invoices from the LND or Alby Wallet API and outputs formatted boosts in show-notes appropriate markdown. 

- Most invoice and boost data is stored in a local database for easy analytics and fast retrieval.
- Supports managing multiple nodes/dbs and syncing missing boosts into a "source of truth" database.
- Supports both LND and Alby as upstream invoice sources.
- Generates markdown-formatted reports suitable for Jupiter Broadcasting style show notes.
- Offers a simple web interface for exporting boost data.

## Built With

* [Clojure](https://clojure.org)
* [Datalevin](https://github.com/juji-io/datalevin)
* [Nix](https://nixos.org/)
* [devenv](https://devenv.sh/)
* [clj-nix](https://github.com/jlesquembre/clj-nix)
* [Alby](https://getalby.com)
* [LND](https://github.com/lightningnetwork/lnd)
* and many more wonderful FOSS components.

## Getting Started
TODO ...

### Prerequisites
TODO: document necessary config/env vars.
- nix
- LND Macaroon(s)
- Alby Token(s)

## Usage
First, make sure to define the necessary env vars for your upstreams. Then run the project.

```sh
$ nix run github:noblepayne/lnd-boost-scraper
```

## Roadmap
This list is a WIP.

- [ ] Load Alby token from file whose path is given by env var
- [ ] Add NixOS Module
- [ ] Observability
- [ ] Tests
- [ ] General refactor and cleanup
  - [ ] Configuration file
  - [ ] Data-oriented approach to defining upstreams
  - [ ] Make podcast definitions modular.
  - [ ] Anything else needed to use outside of JB.

## Contributing

* If you have suggestions for improving the project, feel free to [open an issue](https://github.com/noblepayne/lnd-boost-scraper/issues/new) to discuss it, or directly create a pull request.

## License

Distributed under the MIT License. See [LICENSE](https://github.com/noblepayne/lnd-boost-scraper/blob/main/LICENSE.md) for more information.

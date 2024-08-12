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
  * [Installation](#installation)
* [Usage](#usage)
* [Roadmap](#roadmap)
* [Contributing](#contributing)
* [License](#license)

## About The Project

A minimum viable boost scraper. Fetches invoices from the LND or Alby Wallet API and outputs formatted boosts in show-notes appropriate markdown. 

Initial version requires an LND macaroon or personal alby token.

## Built With

* [Babashka](https://babashka.org)
* [Alby](https://getalby.com)

## Getting Started

TODO

### Prerequisites

TODO

### Installation

TODO

## Usage

TODO

## Roadmap

See the [open issues](https://github.com/noblepayne/lnd-boost-scraper/issues) for a list of proposed features (and known issues).

Also the following TODOs:
- TODO: document using age to manually update secrets.

## Contributing

Contributions are what make the open source community such an amazing place to be learn, inspire, and create. Any contributions you make are **greatly appreciated**.
* If you have suggestions for adding or removing projects, feel free to [open an issue](https://github.com/noblepayne/lnd-boost-scraper/issues/new) to discuss it, or directly create a pull request after you edit the *README.md* file with necessary changes.
* Please make sure you check your spelling and grammar.
* Create individual PR for each suggestion.

### Creating A Pull Request

1. Fork the Project
2. Create your Feature Branch (`git switch -c feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push -u origin feature/AmazingFeature`)
5. Open a Pull Request

## License

Distributed under the MIT License. See [LICENSE](https://github.com/noblepayne/lnd-boost-scraper/blob/main/LICENSE.md) for more information.




### Token Hackery
```
window.indexedDB.open("invoiceDb").onsuccess = (event) => {event.target.result.transaction("authTable").objectStore("authTable").get(1).onsuccess = (event) => {alert(event.target.result.authStatus.oauthTokens.accessToken);};};

window.indexedDB.open("invoiceDb").onsuccess = (event) => {event.target.result.transaction("authTable").objectStore("authTable").get(1).onsuccess = (event) => {alert(event.target.result.authStatus.oauthTokens.refreshToken);};};

alert(JSON.parse(localStorage.getItem("conshax-strg") %7C%7C '%7B"oauthToken"%3A "NO TOKEN FOUND"%7D')%5B"oauthToken"%5D)%3B%7D)
```

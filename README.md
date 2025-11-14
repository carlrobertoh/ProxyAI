<a name="readme-top"></a>

<br />
<div align="center">
  <a href="https://github.com/carlrobertoh/ProxyAI">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="/src/main/resources/icons/proxyRounded_dark.svg">
      <img alt="ProxyAI Logo" src="/src/main/resources/icons/proxyRounded.svg">
    </picture>
  </a>
  <h1 style="margin: 0;" align="center">ProxyAI</h1>
  <p style="width: 640px">
    The leading open-source AI copilot for <a target="_blank" href="https://plugins.jetbrains.com/plugin/21056-proxy-ai">JetBrains</a>. Connect to any model in any environment, and customize your coding experience in any way you like.
  </p>
</div>

[![Downloads][downloads-shield]][plugin-repo]
[![Rating][rating-shield]][plugin-repo]
[![Discord][discord-shield]][invite-link]
[![Version][version-shield]][plugin-repo]

<!-- TABLE OF CONTENTS -->
<details>
  <summary>Table of Contents</summary>
  <ol>
    <li><a href="#about-the-project">About The Project</a></li>
    <li><a href="#core-features">Core Features</a></li>
    <li><a href="#running-locally">Running Locally</a></li>
    <li><a href="#privacy">Privacy</a></li>
    <li><a href="#feedback">Feedback</a></li>
    <li><a href="#license">License</a></li>
  </ol>
</details>

![Plugin screenshot](https://tryproxy.io/images/main.png)

## About The Project

ProxyAI is an AI-powered code assistant designed to help you with various programming activities. It is a great alternative to GitHub Copilot, AI Assistant, Codiumate, or any other extension on the JetBrains marketplace.

We equip you with the latest models, advanced tools, and on-premise solutions that are designed to significantly enhance your developer experience.

Access top-tier language models from OpenAI, Anthropic, Azure, Mistral, and others, or opt for a self-hosted model for a full offline experience.

## Core Features

ProxyAI offers a wide range of features to enhance your development experience:

### Chat

- **Auto Apply:** Stream AI-suggested code changes directly into your editor. Preview modifications in diff view and approve or reject them with a single click.

- **Use images:** Chat with your images. Upload manually or let ProxyAI auto-detect your screenshots. 

- **Reference your files and folders:** Quickly access and reference your project files and folders for context-aware coding assistance.

- **Reference web docs:** Quickly reference web docs in your chat session, such as API guides, library manuals, and more. 

- **Reference git history:** Quickly reference commit logs and changes in your chat session 

- **Search the web:** Connect your favourite LLM to the web. ProxyAI will search for the most relevant information to answer your questions. 

- **Customize your assistant:** Choose between multiple different personas for your specific needs, whether you're looking to learn, write or proofread.

### Code


- **Next edits:** Get multi-line edits based on your recent activity as you type.

- **Autocomplete your code:** Receive single-line or whole-function autocomplete suggestions as you type.

- **Edit code in natural language:** Highlight the code you want to modify, describe the desired changes, and watch ProxyAI work its magic.

- **Get name suggestions:** Get context-aware naming suggestions for methods, variables, and more.

- **Generate commit messages:** Generate concise and descriptive commit messages based on the changes made in your codebase.

For a full list of features and detailed descriptions, visit our [official documentation](https://docs.tryproxy.io).

## Running locally

**Linux or macOS**
```shell
git clone https://github.com/carlrobertoh/ProxyAI.git
cd ProxyAI
git submodule update
./gradlew runIde
```

**Windows ARM64**
```shell
./gradlew runIde -Penv=win-arm64
```

**Tailing logs**
```shell
tail -f build/idea-sandbox/IC-2024.1.2/log/idea.log
```

## Privacy

**Your data stays yours.** ProxyAI **does not** collect or store any kind of sensitive information.

However, with users' consent, we do collect anonymous usage data, which we use to understand how users interact with the extension, including the most-used features and preferred providers.

## Feedback

Your input helps us grow. Reach out through:

- [Issue Tracker](https://github.com/carlrobertoh/ProxyAI/issues)
- [Discord](https://discord.gg/8dTGGrwcnR)
- [Email](mailto:carlrobertoh@gmail.com)

## License

Apache 2.0 © [Carl-Robert Linnupuu][portfolio]

If you found this project interesting, kindly rate it on the marketplace and don't forget to give it a star. Thanks again!
<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->

[downloads-shield]: https://img.shields.io/jetbrains/plugin/d/21056-proxy-ai
[discord-shield]: https://img.shields.io/discord/1118629761049182238?style=flat&logo=discord&label=Discord
[version-shield]: https://img.shields.io/jetbrains/plugin/v/21056-proxy-ai?label=version
[rating-shield]: https://img.shields.io/jetbrains/plugin/r/rating/21056-proxy-ai
[marketplace-img]: https://github.com/carlrobertoh/CodeGPT-docs/blob/main/images/marketplace.png?raw=true
[plugin-repo]: https://plugins.jetbrains.com/plugin/21056-proxy-ai
[invite-link]: https://discord.gg/8dTGGrwcnR
[open-issues]: https://github.com/carlrobertoh/ProxyAI/issues
[api-key-url]: https://platform.openai.com/account/api-keys
[portfolio]: https://carlrobert.ee

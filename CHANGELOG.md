# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [3.7.2-241.1] - 2026-01-30

### Added

- Advanced settings (HTTP/SOCKS proxy) for Agent mode

### Fixed

- Agen rollback changes and context window tracking
- Incorrect line separators on Windows

## [3.7.1-241.1] - 2026-01-26

### Added

- Inception provider with Mercury model (Agent)
- GPT-5.2 Codex (ProxyAI, OpenAI), Gemini 3 Flash Preview (Google), and Devstral 2 (Mistral) models

### Changed

- Improved error handling and retry logic for streaming requests
- Improved tool call validation and agent model selection UI

### Fixed

- Ollama and Custom OpenAI base URL configuration
- Agent total tokens tooltip display
- RollbackPanel layout and text truncation

## [3.7.0-241.1] - 2026-01-19

### Added

- ProxyAI Agent for end-to-end tasks
- Model Context Protocol (MCP) for Chat

### Improved

- Autocomplete and Next Edit suggestions reliability and trigger behavior
- Code completion request handling and gRPC connection stability

## [3.6.8-241.1] - 2025-11-17

### Added

- Inline Edit quick question in the editor
- Debug logs support with toggle in Advanced settings

### Fixed

- Re-enabled improved diff editor fragment for Next Edits

## [3.6.7-241.1] - 2025-11-13

### Improved

- Autocomplete and Next-Edit suggestions experience

### Added

- Colorful file tags based on its state in VCS (modified/added/removed)

## [3.6.6-241.1] - 2025-10-29

### Fixed

- gRPC request timeouts during autocompletion and next-edits
- Order of search tags in the prompt lookup
- Custom OpenAI serialization issues [#1142](https://github.com/carlrobertoh/ProxyAI/issues/1142), [#1151](https://github.com/carlrobertoh/ProxyAI/issues/1151)

## [3.6.5-241.1] - 2025-10-22

### Added

- Configurable send shortcut [#1149](https://github.com/carlrobertoh/ProxyAI/issues/1149)
- Middle-click to remove files from context [#1148](https://github.com/carlrobertoh/ProxyAI/issues/1148)
- Code completion request handling for the `stop` parameter [#1125](https://github.com/carlrobertoh/ProxyAI/issues/1125)

### Fixed

- Performance issues caused by token calculation
- PSI preview link theme issues
- Updated code not restored when closing the 'Apply' view
- Maintain a rolling list (up to 2) of unselected tags when switching between editors
- Message deletion [#1147](https://github.com/carlrobertoh/ProxyAI/issues/1147)
- Code blocks containing nested backticks [#1144](https://github.com/carlrobertoh/ProxyAI/issues/1144)
- Backtick-formatted code overflowing and going out of view [#1145](https://github.com/carlrobertoh/ProxyAI/issues/1145)

## [3.6.4-241.1] - 2025-10-15

### Added

- GPT-5 Codex and Claude Sonnet 4.5 models (ProxyAI)

## [3.6.3-241.1] - 2025-10-08

### Added

- New Inception Labs provider (BYOK) supporting all the in-built features
- Different set of specialized apply models for ProxyAI Cloud users (`mercury-coder`, `relace` and `morph`)

### Replaced

- ProxyAI Next Edit `zeta` model with `mercury-coder`

### Fixed

- Commit Message action from disappearing [#1134](https://github.com/carlrobertoh/ProxyAI/issues/1134)
- Read access exception on GoLand [#1139](https://github.com/carlrobertoh/ProxyAI/issues/1139)

## [3.6.2-241.1] - 2025-10-03

### Fixed

- Unresponsive prompt textfield tags that couldn't be removed or clicked

## [3.6.1-241.1] - 2025-10-03

### Added

- Copy-paste placeholder for prompt text field
- Quick link navigation
- Tooltips for input editor tags [#1109](https://github.com/carlrobertoh/ProxyAI/issues/1109)

### Fixed

- EDIT_CODE model backward compatibility [#1129](https://github.com/carlrobertoh/ProxyAI/issues/1129)
- Google model selection [#1111](https://github.com/carlrobertoh/ProxyAI/issues/1111)
- Mistral autocompletion toggle [#1114](https://github.com/carlrobertoh/ProxyAI/issues/1114)

## [3.6.0-241.1] - 2025-09-23

### Added

- Inline edit (replaces the old Edit Code feature)
- Configurable screenshot detection
- Diagnostics tag
- Drag-and-drop files and folders from the Project tool window [#1124](https://github.com/carlrobertoh/ProxyAI/issues/1124)

### Fixed

- Migrated Custom OpenAI services to use UUIDs and addressed related issues

## [3.5.1-241.1] - 2025-09-03

### Added

- GPT-5 model support
- Chat-based FIM code completion for Custom OpenAI providers
- Chinese language support

### Fixed

- Edit Code prompt regression issue [#1070](https://github.com/carlrobertoh/ProxyAI/issues/1070)
- Custom OpenAI services not adhering to model selections

## [3.5.0-241.1] - 2025-07-24

### Added

- Qwen3 Code model for Free and Pro tiers
- Native Mistral client support
- New Chat History UI/UX
- Server and build logs for llama.cpp
- Support for model per feature
- Support for PSI analysis (Code Analyze) of added files
- Support for cancelling auto apply requests

### Updated

- OpenAI and Google model selection

### Fixed

- llama.cpp server build

### Reverted

- Direct auto apply when similarity threshold is met

## [3.4.4-241.1] - 2025-07-04

### Added

- Chat modes Ask/Edit/Agent
- Support for conversation renaming

### Fixed

- NPE when loading state with null conversations
- Internal error when virtual file is invalid
- Auto Apply logic for larger files

## [3.4.3-241.1] - 2025-06-26

### Added

- Conversations tag support
- Support for changing models/providers in mid-conversation

### Fixed

- Issues where plugin silently connected to a wrong provider (related to a model/provider dep)
- NPE issues with old Azure settings state

## [3.4.2-241.1] - 2025-06-06

### Fixed

- Compatibility issues with the latest IDEs

### Added

- Global context search (users can now perform a speed search without selecting a group first)

## [3.4.1-241.1] - 2025-06-04

### Fixed

- Invalid line separators exception on Windows

## [3.4.0-241.1] - 2025-06-02

### Added

- Ability to edit files while streaming
- Auto Apply functionality now available for every provider and model
- Claude Sonnet 4, Sonnet 4 Thinking and Gemini Flash 2.5 models

## [3.3.0-241.1] - 2025-05-13

### Added

- Support for both Autocomplete and Next Edits
- o4-mini model for ProxyAI provider
- Auto-scroll down when submitting a message
- Loading animation during output generation and several other UI improvements

### Fixed

- NPEs on different types of error messages in chat UI
- Other IDE warnings/errors

## [3.2.5-241.1] - 2025-04-30

### Added

- Apply changes even if the target file isn't open
- Support for creating new files using the 'Apply Changes' action
- Apply and Reject buttons to the diff toolbar

## [3.2.4-241.1] - 2025-04-23

### Added

- GPT-4.1 models for OpenAI provider

### Fixed

- Internal errors and warnings caused by recent IDE upgrade

## [3.2.3-241.1] - 2025-04-16

### Added

- GPT-4.1 models for ProxyAI users 
- Import/Export functionality for Personas

### Fixed

- Duplicate chat referenced files
- Several IDE warnings/errors

## [3.2.2-241.1] - 2025-04-06

### Added

- Gemini 2.5 Pro model for ProxyAI users

### Fixed

- SSL/TLS issues when connecting to Proxy API
- UI issues when using images
- Next Edits manual triggering

## [3.2.1-241.1] - 2025-04-03

### Added

- Gemini 2.5 Pro (exp) model

### Fixed

- Toolwindow ID inconsistencies
- EDT issues when reading files on submit

### Removed

- Clear tags action from editor's context menu

## [3.2.0-241.1] - 2025-03-28

### Added

- UX/UI improvements for chat context selection (migration from old logic to editor's lookup API)
- Shortcut key for deleting all chat tags [#946](https://github.com/carlrobertoh/ProxyAI/pull/946)
- Custom base host support for Anthropic provider [#948](https://github.com/carlrobertoh/ProxyAI/pull/948)
- Img support for Claude 3.7 Sonnet (CodeGPT)

## [3.1.1-241.1] - 2025-03-24

### Added

- Re-add logic to include the active editor selection when creating a new chat

### Fixed

- Custom OpenAI settings UI [#938](https://github.com/carlrobertoh/ProxyAI/pull/938)
- Suggestions popup positioning and tag panel styling [#934](https://github.com/carlrobertoh/ProxyAI/pull/934)
- Typo in Rubber Duck persona [#892](https://github.com/carlrobertoh/ProxyAI/pull/892)
- Prompts configuration [#933](https://github.com/carlrobertoh/ProxyAI/pull/933)

## [3.1.0-241.1] - 2025-03-20

### Added

- Support for multi-line edits (replaces the old Code Assistant logic)
- Popup menu with close actions and other UX improvements for chat tags
- Support for importing and exporting Custom OpenAI settings [#921](https://github.com/carlrobertoh/ProxyAI/pull/921)
- Dependency structure analyser for chat (Kotlin) [#897](https://github.com/carlrobertoh/ProxyAI/pull/897)

## [3.0.0-241.1] - 2025-03-04

### Rebranding

- CodeGPT -> ProxyAI

## [2.16.4-241.1] - 2025-02-25

### Added

- Claude 3.7 Sonnet model (CodeGPT)

## [2.16.3-241.1] - 2025-02-11

### Added

- Support for latest gemini models [#860](https://github.com/carlrobertoh/CodeGPT/issues/860)
- Option to configure multiple Custom OpenAI providers [#859](https://github.com/carlrobertoh/CodeGPT/issues/859)

### Fixed

- Statusbar spinner when request fails
- Do not include duplicate context files
- Send editor's content when files are unsaved [#871](https://github.com/carlrobertoh/CodeGPT/issues/871)

## [2.16.2-241.1] - 2025-02-06

### Fixed

- Tool window initialization race condition [#856](https://github.com/carlrobertoh/CodeGPT/issues/856)
- Invalid OpenAI model state [#842](https://github.com/carlrobertoh/CodeGPT/issues/842)
- Tag panel vertical alignment using GridBagLayout [#857](https://github.com/carlrobertoh/CodeGPT/issues/857)

## [2.16.1-241.1] - 2025-02-04

### Added

- `o3-mini` model (CodeGPT, OpenAI)
- Support for code editing and commit message generation with Deepseek R1 model [#835](https://github.com/carlrobertoh/CodeGPT/issues/835)
- Kotlin dependency analyzer for code completions [#829](https://github.com/carlrobertoh/CodeGPT/pull/829)
- Status toolbar completion progress for all core actions
- Support for disabling system prompts
- Avatar images for registered CodeGPT users

### Fixed

- Tool window content initialization anomalies
- Deepseek R1 thought process parsing
- Missing selection in final prompt [#844](https://github.com/carlrobertoh/CodeGPT/issues/844)

## [2.16.0-241.1] - 2025-01-28

### Added

- DeepSeek V3 and R1 models (CodeGPT, Ollama and llama.cpp)
- New 'Include Current Changes' chat action

### Improved

- Chat text input actions UI/UX

## [2.15.2-241.1] - 2025-01-13

### Added

- Example code assistant guideline in the prompt.
- Support for opening all code assistant changes in full mode.
- Additional commit dialog actions:
  - Generate Message (no functional changes).
  - Generate Message with Additional Input: Adds support for additional input in the prompt.
  - Review Changes: Creates a new chat session and asks CodeGPT to review your changes and suggest improvements.

### Fixed

- Inconsistencies in the model combobox title when changing between tabs [#814](https://github.com/carlrobertoh/CodeGPT/issues/814).

## [2.15.1-241.1] - 2025-01-07

### Added

- Code Assistant predictions for Paste action
- Prioritization and ordering of predictions closest to cursor position

### Fixed

- Code Assistant custom accept shortcut and missing keymap labels

## [2.15.0-241.1] - 2024-12-30

### Added

- Code Assistant: A new feature that suggests edits throughout the file [[Read More](https://www.codegpt.ee/blog/introducing-code-assistant)]
- 'Include Open Files' chat action item [#796](https://github.com/carlrobertoh/CodeGPT/issues/796)

## [2.14.3-241.1] - 2024-12-17

### Added

- Support for copying user and response messages in their original format [#791](https://github.com/carlrobertoh/CodeGPT/issues/791)
- Right-click popup menu for response messages

### Fixed

- Escaped code response characters [#169](https://github.com/carlrobertoh/CodeGPT/issues/169), [#437](https://github.com/carlrobertoh/CodeGPT/issues/437)
- Model value change for empty conversations
- Order of Custom OpenAI applied settings [#797](https://github.com/carlrobertoh/CodeGPT/issues/797)

## [2.14.2-241.1] - 2024-12-12

### Added

- Partial code completion acceptance

## [2.14.1-241.1] - 2024-12-06

### Fixed

- Response streaming for o1 models
- IDE error caused by credential validation in EDT
- Submission handling when current response is ongoing

## [2.14.0-241.1] - 2024-12-04

### Added

- Support for multi-line code completions
- Recent Qwen 2.5 Coder models (llama.cpp)
- Codestral as default code model (CodeGPT)

### Fixed

- NPE caused by lookup element sorter

### Removed

- StarCoder 7B model (CodeGPT)

## [2.13.1-241.1] - 2024-11-27

### Fixed

- NPE when using IDE internal completions [#777](https://github.com/carlrobertoh/CodeGPT/issues/777)
- Inline completion triggering when editor has active lookup

## [2.13.0-241.1] - 2024-11-25

### Added

- Single standalone view for prompt configuration
- Codestral model for code completion (CodeGPT)

### Fixed

- Git diff logic for commit message generation

## [2.12.5-241.1] - 2024-11-18

### Added

- Option to explain commits from VCS log tree [#688](https://github.com/carlrobertoh/CodeGPT/issues/688)

### Fixed

- Chat response streaming for messages received faster than polling rate [#757](https://github.com/carlrobertoh/CodeGPT/issues/757)

### Improved

- Default CodeGPT prompt instructions

## [2.12.4-241.1] - 2024-11-14

### Added

- Qwen 2.5 Coder model for chat and code completion (CodeGPT)

### Fixed

- Image popup notification condition fix [#711](https://github.com/carlrobertoh/CodeGPT/issues/711) 

### Improved

- Code completion user experience
  - Display and apply completions line by line
  - Make suggestion available as soon as new line is available
  - Add logic for whitespace adjustments
  - Post-insertion logic, i.e., move cursor to the beginning of next completion

## [2.12.3-241.1] - 2024-11-07

### Fixed

- Files not persisting between messages in same session

## [2.12.2-241.1] - 2024-11-04

### Fixed

- Ollama chat completion streaming [#744](https://github.com/carlrobertoh/CodeGPT/issues/744)
- Non-stream chat completions error handling (CodeGPT, OpenAI) [#746](https://github.com/carlrobertoh/CodeGPT/issues/746)
- Prompt text component height issues [#747](https://github.com/carlrobertoh/CodeGPT/issues/747)
- Endless loading when persona name or instructions are null [#748](https://github.com/carlrobertoh/CodeGPT/issues/748)

## [2.12.1-241.1] - 2024-11-01

### Added

- Gemini 1.5 Pro model (CodeGPT)

### Fixed

- Code completion state when session contains active completion

### Removed

- StarCoder 16B model (CodeGPT)

## [2.12.0-241.1] - 2024-10-29

### Added

- 'Auto Apply' feature for instant AI-suggested code updates [#743](https://github.com/carlrobertoh/CodeGPT/issues/743) (CodeGPT)

### Fixed

- Code completion enabled/disabled state
- Total token calculation for highlighted texts

## [2.11.7-241.1] - 2024-10-18

### Fixed

- Edit Code request building [#737](https://github.com/carlrobertoh/CodeGPT/issues/737) (OpenAI)
- Chat UI performance issues
- Minor issues related to git repository lookup

## [2.11.6-241.1] - 2024-10-15

### Fixed

- High CPU usage [#716](https://github.com/carlrobertoh/CodeGPT/issues/716)
- NPE on suggestion actions when no files are opened in editor [#725](https://github.com/carlrobertoh/CodeGPT/issues/725)
- Inconsistent text rendering in the chat tool window

## [2.11.5-241.1] - 2024-10-10

### Added

- Support high context limits (up to 200k tokens) via RAG when chatting with files (CodeGPT)
- Gemini 1.5 Pro model (Google)
- Legacy response support for text completions in code completions (Custom OpenAI)

### Fixed

- Chat UI threading issues [#730](https://github.com/carlrobertoh/CodeGPT/issues/730)

## [2.11.4-241.1] - 2024-10-01

### Added

- Qwen 2.5 (72B) and o1 models (CodeGPT, OpenAI)
- Free limited access to SOTA models (CodeGPT)

## [2.11.3-241.1] - 2024-09-25

### Fixed

- Anthropic (Claude) chat requests [#707](https://github.com/carlrobertoh/CodeGPT/issues/707)

## [2.11.2-223] - 2024-09-24

### Added

- Edit Code and Name Suggestions feature support for all providers
- Qwen 2.5 Coder models and prompt templates

### Fixed

- Model popup submenu auto-hover [#681](https://github.com/carlrobertoh/CodeGPT/issues/681)
- Prompt text field newline creation [#694](https://github.com/carlrobertoh/CodeGPT/issues/694)
- Prompt text field theme issues [#701](https://github.com/carlrobertoh/CodeGPT/issues/701)

## [2.11.1-223] - 2024-09-12

### Added

- Git history referencing in prompts
- Quick code snippet referencing in prompts [#690](https://github.com/carlrobertoh/CodeGPT/issues/690)
- Name suggestions for languages other than Java [#615](https://github.com/carlrobertoh/CodeGPT/issues/615)

### Fixed

- Prompt text field persona suggestions [#685](https://github.com/carlrobertoh/CodeGPT/issues/685)
- Text field autofocus when opening the chat toolwindow [#691](https://github.com/carlrobertoh/CodeGPT/issues/691)
- Git commit dialog selected changes not being included in diff
- Ollama server host configuration overriding [#684](https://github.com/carlrobertoh/CodeGPT/issues/684)

### Improved

- Model combobox menu UI/UX by extracting the providers into standalone subgroups [#681](https://github.com/carlrobertoh/CodeGPT/pull/681)
- Prompt text field inlay rendering

## [2.11.0-223] - 2024-09-05

### Added

- Multiple tool window editor actions for generated code:
  - Direct apply: Directly replaces the highlighted code in the main editor with the generated code
  - Compare with Original: Opens a diff view to compare the generated code with the highlighted code
  - Insert at Caret: Inserts the generated code at the exact location of the caret in the main editor
- Vision support for Azure models
- General improvements to code completions, including:
    - Proper streaming support
    - Git context integration

### Fixed

- Prompt text field background transparency issues
- OpenRouter default template for custom OpenAI provider
- Ollama streaming requests

## [2.10.2-223] - 2024-08-23

### Added

- Progress display for web docs

### Fixed

- Code completion prompt creation from placeholders
- Prompt text field inlay offset and up/down key events

## [2.10.1-223] - 2024-08-20

### Fixed

- Codestral Infill templates for code completion in llama.cpp [#667](https://github.com/carlrobertoh/CodeGPT/pull/667)

## [2.10.0-223] - 2024-08-19

### Added

- Web documentation support [#650](https://github.com/carlrobertoh/CodeGPT/pull/650)
- Improved user prompt text field component [#665](https://github.com/carlrobertoh/CodeGPT/pull/665)
    - Spellchecking
    - Auto-enclosing brackets, quotes, etc.
    - Removable suggestion action inlays
    - Context menu for copy-pasting, and more
- DeepSeek Coder V2 model for CodeGPT users
- Image support for Custom OpenAI-compatible providers

### Fixed

- IDE freezes caused by long running file search queries [#652](https://github.com/carlrobertoh/CodeGPT/pull/652)

## [2.9.0-223] - 2024-08-03

### Added

- **Web search support** - available only through the CodeGPT provider [#641](https://github.com/carlrobertoh/CodeGPT/pull/641)
- **Customizable Personas** - a quick way to define and switch between different system prompts. Initial release comes with over 600 pre-defined prompts. [#638](https://github.com/carlrobertoh/CodeGPT/pull/638)

### Other Improvements

- The textbox suggestions popover now offers better usability, making it easier and quicker to add additional context to your session for more accurate responses.
- Support for attaching folders, allowing all files within the folder to be added to your current chat session.
- Optimized the chat user interface for better performance. We noticed that for faster models, the inference speed didn't match what was being rendered on the screen.

## [2.8.5-223] - 2024-07-24

### Added

- **Llama 3.1 405B** model for CodeGPT Individual plan users

### Fixed

- Wrong line separators exception during code completion [(#637)](https://github.com/carlrobertoh/CodeGPT/issues/637)

## [2.8.4-223] - 2024-07-19

### Added

- GPT-4o mini model (**free** with [our hosted service](https://www.codegpt.ee/#pricing))
- New UI/UX design for the toolwindow's user input component
- Support for searching for and attaching files in the textarea popover when the '@' key is used

### Fixed

- Numeric values in Custom providers' headers section ([#619](https://github.com/carlrobertoh/CodeGPT/issues/619))
- Ollama settings sync ([#616](https://github.com/carlrobertoh/CodeGPT/issues/616))

## [2.8.3-223] - 2024-07-15

### Added

- Enter key shortcut for Fast Edit submission (#602)

### Fixed

- Ollama form combobox state (#606)
- Replace code behaviour (#614)
- Wrong line separators on Windows (#605)

## [2.8.2-233] - 2024-07-01

### Added

- Fast Code Edits [#601](https://github.com/carlrobertoh/CodeGPT/pull/601)

### Fixed

- Chat toolwindow autoscroller issues
- Wrong line separators on Windows machine

### Removed

- You.com provider support

## [2.8.1-223] - 2024-06-21

### Added

- Claude 3.5 Sonnet model (CodeGPT)
- Additional `maxTokens`, `temperature` and `apiKey` fields (Ollama)

### Fixed

- Model re-selection after refresh (Ollama)
- Commit message placeholders

## [2.8.0-223] - 2024-06-06

### Added

- OpenAI and Anthropic models for CodeGPT subscribed users
- Support Stable Code Instruct 3B (#552)
- Support Phi-3 Medium 128K (#577)
- Support Codestral 22B 32K (#587)
- Add field for environment variables for Llama server (#550)
- Add OpenRouter service template (#581)

### Fixed

- Remove trailing slashes from URL text fields (#579)
- Disable code completions by default

## [2.7.1-223] - 2024-05-15

### Fixed

- Backward compatibility issues on plugin updates (#551)

### Added

- Display a model name when starting the llama.cpp server (#546)

## [2.7.0-223] - 2024-05-14

### Added

- Three new providers - **Ollama**, **Google** and **CodeGPT** 🚀🚀🚀
- OpenAI GPT-4o model (#547)
- New provider configuration user interface (#538)
- Support Phi-3 Mini, CodeGemma and CodeQwen1.5 models (#516, #524, #525, #527)
- Llama 3 download sizes (#498)
- Mistral AI service template (#532)
- Support for starting/stopping llama.cpp server from statusbar (#544)

### Fixed

- Commit message generation for Custom OpenAI services (#496)
- NPE when using unsupported model for code completions (#499)
- Editor Actions configuration UI issues (#518)
- IDE error caused by Git4Idea dependency (#526)
- Custom Service test connection (#531)
- UI issues around llama.cpp provider configuration (#543, #529)

### Removed

- `max_tokens` configuration for code completions (will be handled internally)

## [2.6.3-223] - 2024-04-22

### Added

- Support for Llama 3 model via llama.cpp port (#479)
- Code completion for "Custom OpenAI Service" (#476)
- Support for configuring llama.cpp server build parameters (#481)
- "Include file in context" to editor context menu (#475)
- Support for placeholders in the commit message system prompt (#458)

### Fixed

- High CPU usage during new files check (#474)
- Persistence of credentials back into the PasswordSafe (#465)

## [2.6.2-222] - 2024-04-15

### Fixed

- Text rendering anomalies upon streaming

## [2.6.1-222] - 2024-04-12

### Fixed

- EncodingManager error handling for invalid inputs (#444)
- Initial default values for `checkForPluginUpdatesCheckBox` and `checkForNewScreenshotsCheckBox` (#446)
- Random IDE crashes caused by tree-sitter (#452, #446)
- Azure base url creation (#449)

## [2.6.0-222] - 2024-04-10

### Added

- Vision support (image understanding) for OpenAI GPT-4 and Anthropic Claude models
- Total token panel for all providers
- Autofocus for UserTextArea when the tool window is visible

### Fixed

- Git commit message generation
- Error when adding a single file to the context
- Several IntelliJ platform warnings

### Removed

- Azure custom configuration (use OpenAI-compatible service to override the default configuration)

## [2.5.1] - 2024-03-14

### Added

- Support for You Pro modes
- Basic post-processing for code completions
- Code completion feature toggle keyboard-shortcut
- Support for git commit message generation with Custom OpenAI and Anthropic services

### Fixed

- Several IntelliJ platform warnings

### Changed

- Supported minimum IDE build from 213 to 222

## [2.5.0] - 2024-03-06

### Added

- Support for Anthropic Claude service

### Fixed

- `IndexOutOfBoundsException` for Azure completions

## [2.4.0] - 2024-02-26

### Added

- Support for custom OpenAI-compatible service (#383)
- Llama cpp for generation of git commit message (#380)
- Remote server settings for Windows + Mixtral Instruct template (#378)

### Fixed

- Proxy settings for azure client (#382)

## [2.3.1] - 2024-02-19

### Added

- Support for custom OpenAI model configuration (#250)

### Fixed

- General Settings isModified state
- Azure service credential condition (#375)
- Caret offset location upon document changes (#367)

## [2.3.0] - 2024-02-14

### Added

- Support for autocompletion (disabled by default)
- Support for auto resolving compilation errors

### Replaced

- OpenAI chat models with the most recent ones
- CodeGPT header key

## [2.2.12] - 2023-12-21

### Added

- Support for extended request parameters for llama.cpp

## [2.2.11] - 2023-12-14

### Added

- Support for chatting with multiple files
- Support for generating commit messages and method names with Azure service

## [2.2.10] - 2023-12-12

### Added

- Configurable commit-message system prompt

### Fixed

- Redundant chat tab creation on provider/model change
- Azure bas host and path overriding

## [2.2.9] - 2023-12-04

### Added

- IDE notification on new plugin versions

### Fixed

- LLaMA settings state on server failure

### Improved

- LLaMA server logging

## [2.2.8] - 2023-12-01

### Fixed

- UI concurrency issues (run completion events on EDT)

## [2.2.7] - 2023-11-30

### Fixed

- LLaMA server boot up when additional params are not set

## [2.2.6] - 2023-11-27

### Added

- Support for automatic code formatting (#262)
- Support for additional command-line params for the llama server startup process
- Support for changing between different provides/models in the chat window (#227)
- Deepseek coder instruct models

### Fixed

- Git commit message generation based on the actual user selected/checked files (#291)

### Improved

- Toolwindow Chat UI

## [2.2.5] - 2023-11-23

### Added

- Option to set the number of threads for local LLM models

### Fixed

- Tool window chat editor enabled/disabled logic
- Tool window text pane caret visibility
- Editor actions for `createNewChatOnEachAction` state
- Plugin from crashing when the LLaMA server process is stuck

## [2.2.4] - 2023-11-20

### Added

- Support for git commit message generation
- Support for method/function name lookup generation

### Fixed

- Llama client read/connect timeout configuration

## [2.2.3] - 2023-11-15

### Added

- Expand/Collapse logic for tool window editors
- Interactive total token count label

### Removed

- Azure model selection (redundant field)

## [2.2.2] - 2023-11-07

### Added

- OpenAI GPT-4 Turbo and the latest GPT-3.5 models

### Fixed

- Credential validation when using llama service

## [2.2.1] - 2023-11-06

### Fixed

- Right click context menu (#251)
- Azure path (#232)
- Llama telemetry service value
- Include all feature builds by removing the value (#242)

## [2.2.0] - 2023-11-03

### Added

- Support for running local LLMs via LLaMA C/C++ port (#249)
- Support for fetching You.com subscription

### Fixed

- Project build for Windows ARM64

### Improved

- You.com coupon design
- Overall UI design

## [2.1.7] - 2023-10-26

### Replaced

- You.com GPT-4 toggle component with checkbox
- Telemetry segment service with rudderstack

### Fixed

- You.com web search not being displayed regardless of the flag

## [2.1.6] - 2023-10-12

### Added

- Default destination value for toolwindow editor save file action
- Suggestion to switch to different LLM provider on quota exceeded error
- Support for You.com GPT-4 model

### Fixed

- The style of wrapping used if the text area is wrapping lines
- Memory leaks (correct disposals for Configurable classes)

## [2.1.5] - 2023-10-02

### Added

- Support for diffing generated code

## [2.1.4] - 2023-10-01

### Added

- Support for modifying generated code within the chat toolwindow
- Support for creating new files directly from the chat toolwindow 

### Fixed

- Privacy policy url in the settings

## [2.1.3] - 2023-09-28

### Added

- More telemetry actions

### Fixed

- Error logging

## [2.1.2] - 2023-09-28

### Fixed

- Incorrect window selection during chat or toolwindow actions

## [2.1.1] - 2023-09-27

### Added

- Telemetry based on redhat impl

## [2.1.0] - 2023-09-21

### Added

- You API integration
- Ability to override completion path

### Updated

- Chat History UI

## [2.0.6] - 2023-08-29

### Removed

- Functionality to fetch/use OpenAI account name

### Fixed

- Rendering user input's newlines

### Added

- Support for closing other tabs (#172)
- Support for configuring custom hosts for OpenAI and Azure services

## [2.0.5] - 2023-06-12

### Fixed

- Tool window not opening on editor actions (#157)

### Added

- Support for changing the editor action behaviour (#157)
- Support for overriding completions request parameters (#152)
- User text area autofocus on creating a new chat (#155)
- Keymap tool window actions (#148)

## [2.0.4] - 2023-05-27

### Fixed

- TypeScript and C# code highlighting

- ToolWindow usability when virtual space option turned ON (#125) 

### Added

- ToolWindow code editor copy/replace header actions
- Custom prompt main editor action (#144)
- Support for 2023.2 EAP builds (#149)

## [2.0.3] - 2023-05-18

### Fixed

- Empty editor context menu item text (#137)
- Temp file path resolving (#130)

### Improved

- Response streaming

### Added

- Reset chat window toolbar action (#138)

## [2.0.2] - 2023-05-16

### Fixed

- Settings deserialization error

### Improved

- Memory consumption by disposing unused editors which are no longer needed

### Removed

- Main editor focus stealing on response streaming

## [2.0.1] - 2023-05-14

### Added

- New GPT-3.5-16k model 

### Fixed

- NPE when `displayName` couldn't be fetched

### Improved

- Proxy support by disabling the default trustmanager

### Removed

- Off-screen rendering setting option
- Automatic textarea focus on stream completion (#126)

## [2.0.0] - 2023-05-03

### Added

- Automatic retry logic on stream timeouts

### Improved

- Input prompt text field UI/UX (height grows with the content)

### Removed

- Custom prompt editor action (users can now provide custom actions within the chat window itself)

### Replaced

- ToolWindow HTML content with native Swing components

### Secured

- `OPENAI_API_KEY` persistence, key is saved in the OS password safe from now on

[Unreleased]: https://github.com/carlrobertoh/ProxyAI/compare/v3.7.2-241.1...HEAD
[3.7.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.7.1-241.1...v3.7.2-241.1
[3.7.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.7.0-241.1...v3.7.1-241.1
[3.7.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.8-241.1...v3.7.0-241.1
[3.6.8-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.7-241.1...v3.6.8-241.1
[3.6.7-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.6-241.1...v3.6.7-241.1
[3.6.6-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.5-241.1...v3.6.6-241.1
[3.6.5-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.4-241.1...v3.6.5-241.1
[3.6.4-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.3-241.1...v3.6.4-241.1
[3.6.3-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.2-241.1...v3.6.3-241.1
[3.6.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.1-241.1...v3.6.2-241.1
[3.6.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.6.0-241.1...v3.6.1-241.1
[3.6.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.5.1-241.1...v3.6.0-241.1
[3.5.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.5.0-241.1...v3.5.1-241.1
[3.5.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.4.4-241.1...v3.5.0-241.1
[3.4.4-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.4.3-241.1...v3.4.4-241.1
[3.4.3-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.4.2-241.1...v3.4.3-241.1
[3.4.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.4.1-241.1...v3.4.2-241.1
[3.4.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.4.0-241.1...v3.4.1-241.1
[3.4.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.3.0-241.1...v3.4.0-241.1
[3.3.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.2.5-241.1...v3.3.0-241.1
[3.2.5-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.2.4-241.1...v3.2.5-241.1
[3.2.4-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.2.3-241.1...v3.2.4-241.1
[3.2.3-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.2.2-241.1...v3.2.3-241.1
[3.2.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.2.1-241.1...v3.2.2-241.1
[3.2.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.2.0-241.1...v3.2.1-241.1
[3.2.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.1.1-241.1...v3.2.0-241.1
[3.1.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.1.0-241.1...v3.1.1-241.1
[3.1.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v3.0.0-241.1...v3.1.0-241.1
[3.0.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.16.4-241.1...v3.0.0-241.1
[2.16.4-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.16.3-241.1...v2.16.4-241.1
[2.16.3-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.16.2-241.1...v2.16.3-241.1
[2.16.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.16.1-241.1...v2.16.2-241.1
[2.16.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.16.0-241.1...v2.16.1-241.1
[2.16.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.15.2-241.1...v2.16.0-241.1
[2.15.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.15.1-241.1...v2.15.2-241.1
[2.15.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.15.0-241.1...v2.15.1-241.1
[2.15.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.14.3-241.1...v2.15.0-241.1
[2.14.3-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.14.2-241.1...v2.14.3-241.1
[2.14.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.14.1-241.1...v2.14.2-241.1
[2.14.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.14.0-241.1...v2.14.1-241.1
[2.14.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.13.1-241.1...v2.14.0-241.1
[2.13.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.13.0-241.1...v2.13.1-241.1
[2.13.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.12.5-241.1...v2.13.0-241.1
[2.12.5-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.12.4-241.1...v2.12.5-241.1
[2.12.4-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.12.3-241.1...v2.12.4-241.1
[2.12.3-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.12.2-241.1...v2.12.3-241.1
[2.12.2-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.12.1-241.1...v2.12.2-241.1
[2.12.1-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.12.0-241.1...v2.12.1-241.1
[2.12.0-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.7-241.1...v2.12.0-241.1
[2.11.7-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.6-241.1...v2.11.7-241.1
[2.11.6-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.5-241.1...v2.11.6-241.1
[2.11.5-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.4-241.1...v2.11.5-241.1
[2.11.4-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.3-241.1...v2.11.4-241.1
[2.11.3-241.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.2-223...v2.11.3-241.1
[2.11.2-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.1-223...v2.11.2-223
[2.11.1-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.11.0-223...v2.11.1-223
[2.11.0-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.10.2-223...v2.11.0-223
[2.10.2-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.10.1-223...v2.10.2-223
[2.10.1-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.10.0-223...v2.10.1-223
[2.10.0-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.9.0-223...v2.10.0-223
[2.9.0-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.8.5-223...v2.9.0-223
[2.8.5-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.8.4-223...v2.8.5-223
[2.8.4-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.8.3-223...v2.8.4-223
[2.8.3-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.8.2-233...v2.8.3-223
[2.8.2-233]: https://github.com/carlrobertoh/ProxyAI/compare/v2.8.1-223...v2.8.2-233
[2.8.2-223]: https://github.com/carlrobertoh/CodeGPT/compare/v2.8.1-223...v2.8.2-223
[2.8.1-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.8.0-223...v2.8.1-223
[2.8.0-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.7.1-223...v2.8.0-223
[2.7.1-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.7.0-223...v2.7.1-223
[2.7.0-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.6.3-223...v2.7.0-223
[2.6.3-223]: https://github.com/carlrobertoh/ProxyAI/compare/v2.6.2-222...v2.6.3-223
[2.6.2-222]: https://github.com/carlrobertoh/ProxyAI/compare/v2.6.1-222...v2.6.2-222
[2.6.1-222]: https://github.com/carlrobertoh/ProxyAI/compare/v2.6.0-222...v2.6.1-222
[2.6.0-222]: https://github.com/carlrobertoh/ProxyAI/compare/v2.5.1...v2.6.0-222
[2.5.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.5.0...v2.5.1
[2.5.0]: https://github.com/carlrobertoh/ProxyAI/compare/v2.4.0...v2.5.0
[2.4.0]: https://github.com/carlrobertoh/ProxyAI/compare/v2.3.1...v2.4.0
[2.3.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.3.0...v2.3.1
[2.3.0]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.12...v2.3.0
[2.2.12]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.11...v2.2.12
[2.2.11]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.10...v2.2.11
[2.2.10]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.9...v2.2.10
[2.2.9]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.8...v2.2.9
[2.2.8]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.7...v2.2.8
[2.2.7]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.6...v2.2.7
[2.2.6]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.5...v2.2.6
[2.2.5]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.4...v2.2.5
[2.2.4]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.3...v2.2.4
[2.2.3]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.2...v2.2.3
[2.2.2]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.1...v2.2.2
[2.2.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.2.0...v2.2.1
[2.2.0]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.7...v2.2.0
[2.1.7]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.6...v2.1.7
[2.1.6]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.5...v2.1.6
[2.1.5]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.4...v2.1.5
[2.1.4]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.3...v2.1.4
[2.1.3]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.2...v2.1.3
[2.1.2]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.1...v2.1.2
[2.1.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.1.0...v2.1.1
[2.1.0]: https://github.com/carlrobertoh/ProxyAI/compare/v2.0.6...v2.1.0
[2.0.6]: https://github.com/carlrobertoh/ProxyAI/compare/v2.0.5...v2.0.6
[2.0.5]: https://github.com/carlrobertoh/ProxyAI/compare/v2.0.4...v2.0.5
[2.0.4]: https://github.com/carlrobertoh/ProxyAI/compare/v2.0.3...v2.0.4
[2.0.3]: https://github.com/carlrobertoh/ProxyAI/compare/v2.0.2...v2.0.3
[2.0.2]: https://github.com/carlrobertoh/ProxyAI/compare/v2.0.1...v2.0.2
[2.0.1]: https://github.com/carlrobertoh/ProxyAI/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/carlrobertoh/ProxyAI/commits/v2.0.0

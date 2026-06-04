# ktgbotapi-conversation

A small wait-based DSL for step-by-step conversations in Telegram bots built on
[ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi). It is a pure conversation engine —
no mappers, no storage, no domain types.

Each `wait*` helper sends a prompt, suspends until the user replies in the same chat, and repeats
the request on invalid input. Sending `/cancel` at any point aborts the whole flow with
`ConversationCancelledException` — wrap the conversation body in a `try/catch` to show your own
"cancelled" message. Prompt and error strings are supplied by the caller, so localisation and
validation stay on the caller's side.

## Helpers

| Function | Purpose |
| --- | --- |
| `waitText` | a single text message |
| `waitValidatedText` | text with a `(String) -> String?` validator (error message or `null`) |
| `waitParsed` | text parsed into a value via `Parsed<T>` |
| `waitEnum` | pick one of the options with a reply keyboard |
| `waitInlineChoice` | pick with an inline-keyboard button (callback `"<prefix>\|<payload>"`) |
| `waitConfirmation` | yes/no |
| `waitMultiple` | collect a set of values until `/complete` |

`ConversationState<T>` is a thread-safe registry of active conversations, so a bot can refuse to
start a second concurrent flow for the same user and cancel a running one on demand.

## Usage

The repository is meant to be consumed as a git submodule. Its sources are compiled directly into
the consuming project:

```kotlin
// settings.gradle.kts or build.gradle.kts of the consumer
sourceSets["main"].kotlin.srcDir("libs/ktgbotapi-conversation/src/main/kotlin")
```

The bundled `build.gradle.kts` is only needed for a standalone build and the CI compile check.

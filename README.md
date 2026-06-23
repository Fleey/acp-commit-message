# ACP Commit Message

Generate Git commit messages from the JetBrains Commit tool window with a local ACP agent configured in JetBrains AI Assistant.

## Requirements

- A JetBrains IDE based on IntelliJ Platform 2026.1 (`261.*`)
- Git integration enabled
- A local ACP agent in `~/.jetbrains/acp.json`
- The agent must already be authenticated; complete its login flow in a terminal first

The plugin reads the same `acp.json` file as AI Assistant, but does not use any private AI Assistant API and does not require a JetBrains AI subscription.

## ACP configuration

```json
{
  "agent_servers": {
    "My Agent": {
      "command": "/absolute/path/to/agent",
      "args": ["acp"],
      "model": "claude-sonnet-4",
      "models": ["claude-sonnet-4", "claude-opus-4"],
      "env": {
        "API_KEY": "optional-secret"
      }
    }
  }
}
```

`command`, `args`, `env`, and optional model hints are consumed. MCP settings are deliberately ignored.

For bare commands such as `traecli` or `npx`, the plugin resolves the executable from the configured `PATH`, the user's login shell `PATH`, and common macOS developer tool locations. If an IDE launched from the Dock still cannot see the command, set `command` to an absolute path from `which traecli` or `which npx`.

Optional `model`, `default_model`, `defaultModel`, and `models` values are used to populate the settings UI. If the agent exposes models dynamically through ACP `session/new`, click **Refresh models from ACP** in settings to start a short-lived ACP session and cache the reported model IDs. During generation, the plugin calls ACP `session/set_model` when the agent advertises model support; otherwise the selected model is included in the prompt as a compatibility fallback.

Claude/Codex adapters launched through `npx` can take longer on first start because the package may be installed and the CLI may check authentication. The plugin uses a separate **Agent startup timeout** setting, defaulting to 120 seconds, before applying the normal generation timeout. For `npx` agents, the plugin uses an isolated npm cache at `~/.cache/acp-commit-message/npm-cache` to avoid stale or corrupted entries in the user-level `~/.npm/_npx` cache. If startup still times out, the notification includes the ACP process stderr tail so npm, registry, CLI path, and authentication failures are visible without exposing environment variables.

## Usage

1. Open the Commit tool window and select the files or partial changes to commit.
2. Click **Generate Commit Message with ACP** beside the commit message editor.
3. Choose an agent the first time. The choice is remembered per project until the configured agent set changes.
4. The completed response replaces the current commit message.

Configure the default agent, ACP model, output language, Conventional Commits, custom prompt, diff limit, agent startup timeout, and generation timeout under **Settings | Version Control | ACP Commit Message** (the exact parent group can vary by IDE). The model selector is editable, so you can type a model name even when the agent does not list models in `acp.json` or does not report dynamic models.

Click **Detect Claude/Codex CLI** to scan the local PATH for `claude` and `codex`. When a CLI and `npx` are available, the plugin adds missing ACP entries to `~/.jetbrains/acp.json` using the official adapters:

- `Claude`: `npx -y @agentclientprotocol/claude-agent-acp`
- `Codex`: `npx -y @agentclientprotocol/codex-acp`

Existing agents are not overwritten.

## Security model

- The ACP client advertises only `fs.readTextFile`; write and terminal capabilities are disabled.
- File reads are limited to canonical project content roots. Traversal, escaping symlinks, binary files, invalid UTF-8, and files larger than 1 MiB are rejected.
- Permission requests for tools are rejected and MCP servers are never passed to the agent.
- Environment variable values are never logged and are redacted from configuration diagnostics.

The external agent process still runs under the current operating-system user. ACP capability restrictions are not an OS sandbox, so only configure agents you trust.

## Build

The project uses JDK 21, Kotlin 2.2.21, Gradle 9.5, IntelliJ Platform Gradle Plugin 2.16.0, and ACP Kotlin SDK 0.24.0.

```bash
./gradlew test buildPlugin
```

For offline development against an installed 2026.1 IDE:

```bash
./gradlew test buildPlugin -PlocalIdePath="/Applications/GoLand.app"
```

The installable archive is written to `build/distributions/`.

## Limits

- Local command-based entries under `agent_servers` are supported; registry-only and remote agents are not.
- The default diff limit is 512 KiB. Oversized commits are rejected instead of being silently truncated.
- Interactive ACP authentication, terminal access, file writes, and MCP forwarding are intentionally unsupported.

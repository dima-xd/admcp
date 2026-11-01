# MCP Registry publishing

admcp is published to the official MCP Registry as:

```text
io.github.dima-xd/admcp
```

The registry stores metadata only. The installable artifact is the `.mcpb`
bundle attached to each GitHub release. The bundle ships a small Node launcher
and the JVM application; JDK 21 must be installed on the host.

## Release pipeline

The release workflow runs semantic-release on every push to `master`. During
the `prepare` phase it:

1. builds the portable zip and the MCPB staging directory;
2. packs `admcp-<version>.mcpb` with `@anthropic-ai/mcpb`;
3. writes the MCPB SHA-256 into `server.json`;
4. writes checksums for both release assets.

After the GitHub release is published, semantic-release authenticates to the
MCP Registry with GitHub OIDC and runs `mcp-publisher publish`.

The workflow needs these permissions:

```yaml
permissions:
  contents: write
  id-token: write
```

## Local validation

```bash
npm ci
./gradlew :app:admcp:mcpbStage -PreleaseVersion=1.0.0 --no-daemon
./node_modules/.bin/mcpb pack \
  app/admcp/build/mcpb \
  app/admcp/build/distributions/admcp-1.0.0.mcpb
node scripts/prepare-mcp-registry.mjs 1.0.0
mcp-publisher validate
```

`mcp-publisher validate` checks `server.json` against the current registry
schema. Publishing also requires the release asset to exist at the URL in
`server.json`.

## Manual publish

```bash
curl -fsSL \
  "https://github.com/modelcontextprotocol/registry/releases/latest/download/mcp-publisher_linux_amd64.tar.gz" \
  | tar -xz mcp-publisher
chmod +x mcp-publisher

./mcp-publisher login github
./mcp-publisher publish
```

For CI, use `mcp-publisher login github-oidc` instead of the device flow.

## Updating metadata

Edit `server.json` for fields that do not change per release, such as the
description or links. The release script updates the version, package URL and
SHA-256 automatically.

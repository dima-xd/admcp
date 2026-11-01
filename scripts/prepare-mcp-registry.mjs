import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const version = process.argv[2];
if (!version) {
  console.error("usage: node scripts/prepare-mcp-registry.mjs <version>");
  process.exit(1);
}

const repository = process.env.GITHUB_REPOSITORY || "dima-xd/admcp";
const [owner] = repository.split("/");
const mcpbPath = resolve(`app/admcp/build/distributions/admcp-${version}.mcpb`);
const serverPath = resolve("server.json");

const fileSha256 = createHash("sha256")
  .update(readFileSync(mcpbPath))
  .digest("hex");

const server = JSON.parse(readFileSync(serverPath, "utf8"));
const packageEntry = server.packages?.find(
  (entry) => entry.registryType === "mcpb",
);

if (!packageEntry) {
  console.error("server.json does not contain an mcpb package entry");
  process.exit(1);
}

server.name = `io.github.${owner}/admcp`;
server.version = version;
server.repository = {
  url: `https://github.com/${repository}`,
  source: "github",
};
server.websiteUrl = `https://github.com/${repository}`;
packageEntry.version = version;
packageEntry.identifier = `https://github.com/${repository}/releases/download/v${version}/admcp-${version}.mcpb`;
packageEntry.fileSha256 = fileSha256;

writeFileSync(serverPath, `${JSON.stringify(server, null, 2)}\n`);
console.log(`Updated server.json: ${server.name} ${version} (${fileSha256})`);

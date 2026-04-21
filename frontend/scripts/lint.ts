import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const srcDir = path.join(rootDir, "src");
const apiModule = path.join(srcDir, "api.ts");

async function collectSourceFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = await Promise.all(
    entries.map(async (entry) => {
      const absolutePath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        return collectSourceFiles(absolutePath);
      }
      if (entry.isFile() && /\.(ts|tsx)$/.test(entry.name) && !entry.name.endsWith(".test.ts")) {
        return [absolutePath];
      }
      return [];
    })
  );

  return files.flat().sort();
}

async function main(): Promise<void> {
  const issues: string[] = [];
  const sourceFiles = await collectSourceFiles(srcDir);

  for (const filePath of sourceFiles) {
    const relativePath = path.relative(rootDir, filePath);
    const content = await readFile(filePath, "utf8");
    const lines = content.split(/\r?\n/);

    lines.forEach((line, index) => {
      const location = `${relativePath}:${index + 1}`;

      if (/\bconsole\./.test(line)) {
        issues.push(`${location} Avoid console logging in committed operator-console source.`);
      }

      if (/\bdebugger\b/.test(line)) {
        issues.push(`${location} Remove debugger statements before shipping frontend changes.`);
      }

      if (filePath !== apiModule && /\bfetch\s*\(/.test(line)) {
        issues.push(`${location} Route network access through src/api.ts so live ledger data stays centralized.`);
      }

      if (filePath !== apiModule && /\bimport\b.*\bmockData\b/.test(line)) {
        issues.push(
          `${location} Import mock fallback data only from src/api.ts so UI views do not bypass the live-source boundary.`
        );
      }

      if (filePath !== apiModule && /["'`]\/api\//.test(line)) {
        issues.push(`${location} Keep raw API route strings in src/api.ts rather than scattering operator endpoints across the UI.`);
      }
    });
  }

  if (issues.length > 0) {
    console.error("Frontend lint failed:\n");
    issues.forEach((issue) => console.error(`- ${issue}`));
    process.exitCode = 1;
    return;
  }

  console.log(`Frontend lint passed for ${sourceFiles.length} source files.`);
}

await main();

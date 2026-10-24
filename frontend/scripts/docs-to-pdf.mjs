// Renders the project's Markdown docs to styled PDFs in docs/pdf/ (gitignored)
// using headless Google Chrome.
//
//   npm run docs:pdf                     # every doc
//   npm run docs:pdf -- docs/levels/LEVEL_3.md
//
// Paths are relative to the repo root. Links between docs (foo.md) are
// rewritten to point at the matching PDF (foo.pdf).
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { basename, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { marked } from 'marked'

const REPO = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const OUT = join(REPO, 'docs/pdf')
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

const DEFAULT_DOCS = [
  ...readdirSync(join(REPO, 'docs'))
    .filter((f) => f.endsWith('.md'))
    .map((f) => `docs/${f}`),
  ...readdirSync(join(REPO, 'docs/project-restart'))
    .filter((f) => f.endsWith('.md'))
    .map((f) => `docs/project-restart/${f}`),
  ...readdirSync(join(REPO, 'docs/levels'))
    .filter((f) => f.endsWith('.md'))
    .map((f) => `docs/levels/${f}`),
]

const css = `
@page { size: A4; margin: 18mm 16mm 20mm;
  @bottom-left { content: "DevPulse AI · __DOC__"; font: 8pt -apple-system, sans-serif; color: #888; }
  @bottom-right { content: counter(page) " / " counter(pages); font: 8pt -apple-system, sans-serif; color: #888; } }
* { box-sizing: border-box; }
body { font: 10pt/1.5 -apple-system, "Helvetica Neue", Arial, sans-serif; color: #1f2328; margin: 0; }
h1 { font-size: 20pt; margin: 0 0 6pt; padding-bottom: 6pt; border-bottom: 2px solid #1f2328; }
h2 { font-size: 14pt; margin: 18pt 0 6pt; padding-bottom: 3pt; border-bottom: 1px solid #d0d7de; break-after: avoid; }
h3 { font-size: 11.5pt; margin: 14pt 0 4pt; break-after: avoid; }
h4 { font-size: 10.5pt; margin: 12pt 0 4pt; break-after: avoid; }
p, ul, ol { margin: 0 0 7pt; }
li { margin: 1.5pt 0; }
li > ul, li > ol { margin: 2pt 0 0; }
input[type=checkbox] { margin: 0 5pt 0 0; vertical-align: -1px; }
li:has(> input[type=checkbox]) { list-style: none; margin-left: -14pt; }
a { color: #0969da; text-decoration: none; }
code { font: 8.5pt/1.4 "SF Mono", Menlo, monospace; background: #f3f4f6; padding: 0.5pt 3pt; border-radius: 3px; }
pre { background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 6px; padding: 7pt 9pt; margin: 0 0 8pt;
  white-space: pre-wrap; word-break: break-word; break-inside: avoid; }
pre code { background: none; padding: 0; font-size: 8.3pt; }
table { border-collapse: collapse; width: 100%; margin: 0 0 9pt; font-size: 8.8pt; }
th, td { border: 1px solid #d0d7de; padding: 3.5pt 6pt; text-align: left; vertical-align: top; }
th { background: #f6f8fa; font-weight: 600; }
tr { break-inside: avoid; }
td code, th code { font-size: 7.8pt; word-break: break-word; }
blockquote { margin: 0 0 8pt; padding: 2pt 10pt; border-left: 3px solid #d0d7de; color: #57606a; }
hr { border: 0; border-top: 1px solid #d0d7de; margin: 14pt 0; }
strong { font-weight: 600; }
`

if (!existsSync(CHROME)) {
  console.error(`Google Chrome not found at ${CHROME}`)
  process.exit(1)
}

const docs = process.argv.slice(2).length > 0 ? process.argv.slice(2) : DEFAULT_DOCS
const htmlDir = join(OUT, '.html')
mkdirSync(htmlDir, { recursive: true })

for (const doc of docs) {
  const file = resolve(REPO, doc)
  const name = basename(file, '.md')
  let md = readFileSync(file, 'utf8')
  md = md.replace(
    /\]\(((?:\.\.\/|\.\/)?(?:[\w-]+\/)*)([\w-]+)\.md(#[^)]*)?\)/g,
    (_, _dir, base, hash = '') => `](${base}.pdf${hash})`,
  )
  const title = (md.match(/^#\s+(.+)$/m)?.[1] ?? name).replace(/[`*]/g, '')
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>${title}</title>
<style>${css.replace('__DOC__', title.replace(/"/g, ''))}</style></head><body>${marked.parse(md, { gfm: true })}</body></html>`
  const htmlPath = join(htmlDir, `${name}.html`)
  writeFileSync(htmlPath, html)
  execFileSync(
    CHROME,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-pdf-header-footer',
      `--print-to-pdf=${join(OUT, `${name}.pdf`)}`,
      pathToFileURL(htmlPath).href,
    ],
    { stdio: 'ignore' },
  )
  console.log(`docs/pdf/${name}.pdf`)
}

rmSync(htmlDir, { recursive: true, force: true })

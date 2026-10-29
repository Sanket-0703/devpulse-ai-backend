/**
 * Parses the unified diff GitHub sends for one file ("patch"):
 *
 *   @@ -10,4 +10,5 @@ function login() {     ← hunk header: old start,count  new start,count
 *    unchanged line                         ← context: in both versions
 *   -removed line                           ← only in the old version
 *   +added line                             ← only in the new version
 *   \ No newline at end of file             ← a note about the line above
 *
 * Each line gets its line numbers in the old and new file, which is what a
 * diff view shows in its two gutters (and what inline review comments in
 * Level 7 attach to).
 */
export type DiffLine =
  | { kind: 'hunk'; content: string }
  | { kind: 'note'; content: string }
  | { kind: 'context'; content: string; oldLine: number; newLine: number }
  | { kind: 'add'; content: string; newLine: number }
  | { kind: 'del'; content: string; oldLine: number }

const HUNK_HEADER = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/

export function parsePatch(patch: string): DiffLine[] {
  const lines: DiffLine[] = []
  let oldLine = 0
  let newLine = 0

  const rows = patch.split('\n')
  if (rows.at(-1) === '') rows.pop() // a trailing newline isn't an extra line

  for (const raw of rows) {
    const header = HUNK_HEADER.exec(raw)
    if (header) {
      oldLine = Number(header[1])
      newLine = Number(header[2])
      lines.push({ kind: 'hunk', content: raw })
    } else if (raw.startsWith('+')) {
      lines.push({ kind: 'add', content: raw.slice(1), newLine: newLine++ })
    } else if (raw.startsWith('-')) {
      lines.push({ kind: 'del', content: raw.slice(1), oldLine: oldLine++ })
    } else if (raw.startsWith('\\')) {
      lines.push({ kind: 'note', content: raw.slice(1).trim() })
    } else if (lines.length > 0) {
      // A context line starts with a space; an empty one may have lost it.
      lines.push({
        kind: 'context',
        content: raw.startsWith(' ') ? raw.slice(1) : raw,
        oldLine: oldLine++,
        newLine: newLine++,
      })
    }
  }
  return lines
}

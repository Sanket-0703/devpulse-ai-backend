import { parsePatch } from './diff'

describe('parsePatch', () => {
  it('numbers lines in the old and the new file', () => {
    const lines = parsePatch(
      [
        '@@ -10,4 +10,5 @@ function login() {',
        ' const a = 1',
        '-const b = 2',
        '+const b = 3',
        '+const c = 4',
        ' return a',
      ].join('\n'),
    )

    expect(lines).toEqual([
      { kind: 'hunk', content: '@@ -10,4 +10,5 @@ function login() {' },
      { kind: 'context', content: 'const a = 1', oldLine: 10, newLine: 10 },
      { kind: 'del', content: 'const b = 2', oldLine: 11 },
      { kind: 'add', content: 'const b = 3', newLine: 11 },
      { kind: 'add', content: 'const c = 4', newLine: 12 },
      { kind: 'context', content: 'return a', oldLine: 12, newLine: 13 },
    ])
  })

  it('restarts the numbering at every hunk', () => {
    const lines = parsePatch(['@@ -1 +1 @@', '-a', '+b', '@@ -50,2 +50,2 @@', ' x'].join('\n'))

    expect(lines.at(-1)).toEqual({ kind: 'context', content: 'x', oldLine: 50, newLine: 50 })
  })

  it('handles new files, notes and blank context lines', () => {
    const added = parsePatch('@@ -0,0 +1,2 @@\n+first\n+second\n\\ No newline at end of file')
    expect(added.map((l) => l.kind)).toEqual(['hunk', 'add', 'add', 'note'])
    expect(added[2]).toEqual({ kind: 'add', content: 'second', newLine: 2 })
    expect(added[3]).toEqual({ kind: 'note', content: 'No newline at end of file' })

    // A context line that lost its leading space, and a trailing newline.
    const blank = parsePatch('@@ -1,3 +1,3 @@\n a\n\n b\n')
    expect(blank).toHaveLength(4)
    expect(blank[2]).toEqual({ kind: 'context', content: '', oldLine: 2, newLine: 2 })
  })

  it('ignores anything before the first hunk', () => {
    expect(parsePatch('garbage\n@@ -1 +1 @@\n x')).toEqual([
      { kind: 'hunk', content: '@@ -1 +1 @@' },
      { kind: 'context', content: 'x', oldLine: 1, newLine: 1 },
    ])
  })
})

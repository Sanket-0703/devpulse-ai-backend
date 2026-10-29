import { Fragment, type ReactNode, useMemo } from 'react'

import { cn } from '@/lib/utils'

import { type DiffLine, parsePatch } from './diff'

const ROW: Record<DiffLine['kind'], string> = {
  hunk: 'bg-sky-500/10 text-muted-foreground',
  note: 'text-muted-foreground italic',
  context: '',
  add: 'bg-emerald-500/10',
  del: 'bg-rose-500/10',
}

const MARKER: Record<DiffLine['kind'], string> = {
  hunk: '',
  note: '',
  context: ' ',
  add: '+',
  del: '-',
}

/**
 * One file's unified diff as a table: old line number, new line number,
 * code. Long lines scroll sideways instead of wrapping, like on GitHub.
 * {@code annotations} (e.g. review findings) appear right under the line
 * with that number in the new file.
 */
export function DiffView({
  patch,
  filename,
  annotations,
}: {
  patch: string
  filename: string
  annotations?: Map<number, ReactNode>
}) {
  const lines = useMemo(() => parsePatch(patch), [patch])

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse font-mono text-xs leading-5">
        <caption className="sr-only">Changes in {filename}</caption>
        <thead className="sr-only">
          <tr>
            <th scope="col">Old line</th>
            <th scope="col">New line</th>
            <th scope="col">Code</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line, i) => {
            const annotation =
              line.kind === 'add' || line.kind === 'context'
                ? annotations?.get(line.newLine)
                : undefined
            return (
              <Fragment key={i}>
                <tr className={ROW[line.kind]} data-kind={line.kind}>
                  {line.kind === 'hunk' || line.kind === 'note' ? (
                    <td colSpan={3} className="px-3 py-0.5 whitespace-pre">
                      {line.content}
                    </td>
                  ) : (
                    <>
                      <LineNumber value={line.kind === 'add' ? undefined : line.oldLine} />
                      <LineNumber value={line.kind === 'del' ? undefined : line.newLine} />
                      <td className="w-full pr-3 whitespace-pre">
                        <span
                          className={cn(
                            'inline-block w-5 text-center select-none',
                            line.kind === 'add' && 'text-emerald-700 dark:text-emerald-300',
                            line.kind === 'del' && 'text-rose-700 dark:text-rose-300',
                          )}
                          aria-hidden
                        >
                          {MARKER[line.kind]}
                        </span>
                        {line.kind !== 'context' && (
                          <span className="sr-only">
                            {line.kind === 'add' ? 'Added: ' : 'Removed: '}
                          </span>
                        )}
                        {line.content}
                      </td>
                    </>
                  )}
                </tr>
                {annotation && (
                  <tr data-kind="annotation">
                    <td colSpan={3} className="bg-muted/40 px-3 py-2">
                      {annotation}
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function LineNumber({ value }: { value?: number }) {
  return (
    <td className="text-muted-foreground w-px min-w-10 px-2 text-right align-top select-none">
      {value}
    </td>
  )
}

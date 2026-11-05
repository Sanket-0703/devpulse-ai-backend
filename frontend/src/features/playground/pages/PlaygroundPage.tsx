import { useMutation, useQueryClient } from '@tanstack/react-query'
import { CircleAlertIcon, Loader2Icon, SparklesIcon } from 'lucide-react'
import { type ReactNode, useState } from 'react'

import { PageHeader } from '@/components/common/PageHeader'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { DiffView } from '@/features/pull-requests/DiffView'
import { type Finding, formatCost, reviewKeys } from '@/features/reviews/api'
import { FindingCard, FindingCountBadges } from '@/features/reviews/components'
import { errorMessage } from '@/lib/api/errors'

import {
  EXAMPLE,
  type Language,
  LANGUAGES,
  MAX_CODE_CHARS,
  type PlaygroundResult,
  reviewSnippet,
} from '../api'

/** The result, with the exact code it belongs to (the editor may have changed since). */
type Reviewed = { code: string; result: PlaygroundResult }

/** Paste a snippet, get the same review a pull request gets: rules + AI, findings on their lines. */
export function PlaygroundPage() {
  const queryClient = useQueryClient()
  const [language, setLanguage] = useState<Language>('JAVA')
  const [code, setCode] = useState('')
  const [reviewed, setReviewed] = useState<Reviewed>()

  const review = useMutation({
    mutationFn: (snippet: string) => reviewSnippet({ language, code: snippet }),
    onSuccess: (result, snippet) => {
      setReviewed({ code: snippet, result })
      void queryClient.invalidateQueries({ queryKey: reviewKeys.usage })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const tooLong = code.length > MAX_CODE_CHARS

  return (
    <>
      <PageHeader
        title="Playground"
        description="Paste a snippet and get an instant review, with the same security rules and AI model as pull requests. Your code isn't stored."
      />

      <Card>
        <CardContent className="space-y-4">
          <form
            className="contents"
            onSubmit={(e) => {
              e.preventDefault()
              if (code.trim() && !tooLong) review.mutate(code)
            }}
          >
            <div className="flex flex-wrap items-end gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="language">Language</Label>
                <select
                  id="language"
                  className="border-input bg-background h-9 rounded-md border px-2 text-sm"
                  value={language}
                  onChange={(e) => setLanguage(e.target.value as Language)}
                >
                  {LANGUAGES.map((l) => (
                    <option key={l.value} value={l.value}>
                      {l.label}
                    </option>
                  ))}
                </select>
              </div>
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  setLanguage(EXAMPLE.language)
                  setCode(EXAMPLE.code)
                }}
              >
                Load an example
              </Button>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="code">Code</Label>
              <textarea
                id="code"
                className="border-input bg-background focus-visible:ring-ring min-h-72 w-full rounded-md border p-3 font-mono text-xs leading-5 outline-none focus-visible:ring-2"
                spellCheck={false}
                placeholder="Paste a function, a class, a query…"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                aria-describedby="code-help"
                aria-invalid={tooLong}
              />
              <p
                id="code-help"
                className={tooLong ? 'text-destructive text-xs' : 'text-muted-foreground text-xs'}
              >
                {code.length.toLocaleString()} / {MAX_CODE_CHARS.toLocaleString()} characters
              </p>
            </div>

            {review.isError && (
              <Alert variant="destructive">
                <CircleAlertIcon />
                <AlertDescription>{errorMessage(review.error)}</AlertDescription>
              </Alert>
            )}

            <Button type="submit" disabled={review.isPending || !code.trim() || tooLong}>
              {review.isPending ? <Loader2Icon className="animate-spin" /> : <SparklesIcon />}
              Review
            </Button>
          </form>
        </CardContent>
      </Card>

      {reviewed && <Result reviewed={reviewed} language={language} />}
    </>
  )
}

function Result({ reviewed, language }: { reviewed: Reviewed; language: Language }) {
  const { result, code } = reviewed
  // The card components expect review findings; Playground ones have no id or file.
  const findings: Finding[] = result.findings.map((f, i) => ({
    ...f,
    id: i + 1,
    filePath: 'snippet',
    dismissed: false,
  }))
  const counts = {
    critical: findings.filter((f) => f.severity === 'CRITICAL').length,
    high: findings.filter((f) => f.severity === 'HIGH').length,
    medium: findings.filter((f) => f.severity === 'MEDIUM').length,
    low: findings.filter((f) => f.severity === 'LOW').length,
  }

  // The snippet shown as a new file, so every finding sits under its line.
  const lines = code.replace(/\r\n/g, '\n').split('\n')
  const patch = `@@ -0,0 +1,${lines.length} @@\n` + lines.map((line) => `+${line}`).join('\n')
  const byLine = new Map<number, ReactNode>()
  for (const finding of findings) {
    if (finding.line == null) continue
    const sameLine = findings.filter((f) => f.line === finding.line)
    byLine.set(
      finding.line,
      <div className="space-y-2">
        {sameLine.map((f) => (
          <FindingCard key={f.id} finding={f} />
        ))}
      </div>,
    )
  }
  const wholeSnippet = findings.filter((f) => f.line == null)

  return (
    <Card className="mt-6 gap-0 overflow-hidden pb-0">
      <CardHeader className="space-y-2 pb-4">
        <CardTitle>
          <h2>Result</h2>
        </CardTitle>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <FindingCountBadges counts={counts} />
          <span className="text-muted-foreground text-xs">
            {(result.promptTokens + result.completionTokens).toLocaleString()} tokens ·{' '}
            {formatCost(result)} · {LANGUAGES.find((l) => l.value === language)?.label}
          </span>
        </div>
        {result.truncated && (
          <p className="text-muted-foreground text-xs">
            Part of the snippet wasn’t reviewed (size or cost limit).
          </p>
        )}
      </CardHeader>
      {wholeSnippet.length > 0 && (
        <div className="bg-muted/40 space-y-2 border-t px-3 py-2">
          {wholeSnippet.map((f) => (
            <FindingCard key={f.id} finding={f} />
          ))}
        </div>
      )}
      <div className="border-t">
        <DiffView patch={patch} filename="your snippet" annotations={byLine} />
      </div>
    </Card>
  )
}

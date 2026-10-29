import { Button } from '@/components/ui/button'

/** "Page 2 of 5 · 93 items" with Previous / Next. Renders nothing for a single page. */
export function Pagination({
  page,
  totalPages,
  totalElements,
  noun,
  onPage,
}: {
  /** Zero-based. */
  page: number
  totalPages: number
  totalElements: number
  /** Plural, e.g. "repositories". */
  noun: string
  onPage: (page: number) => void
}) {
  if (totalPages <= 1) return null
  return (
    <nav aria-label="Pagination" className="mt-4 flex items-center justify-between gap-2">
      <p className="text-muted-foreground text-sm">
        Page {page + 1} of {totalPages} · {totalElements} {noun}
      </p>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          Next
        </Button>
      </div>
    </nav>
  )
}

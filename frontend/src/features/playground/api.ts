import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type PlaygroundRequest = components['schemas']['PlaygroundRequest']
export type PlaygroundResult = components['schemas']['PlaygroundResponse']
export type Language = PlaygroundRequest['language']

export const MAX_CODE_CHARS = 20_000

export const LANGUAGES: { value: Language; label: string }[] = [
  { value: 'JAVA', label: 'Java' },
  { value: 'KOTLIN', label: 'Kotlin' },
  { value: 'JAVASCRIPT', label: 'JavaScript' },
  { value: 'TYPESCRIPT', label: 'TypeScript' },
  { value: 'PYTHON', label: 'Python' },
  { value: 'GO', label: 'Go' },
  { value: 'SQL', label: 'SQL' },
  { value: 'OTHER', label: 'Other' },
]

/** A snippet with a few planted problems, to see what a review looks like. */
export const EXAMPLE = {
  language: 'JAVA' as Language,
  code: `public List<User> findUsers(String name) {
    System.out.println("searching " + name);
    String query = "SELECT * FROM users WHERE name = '" + name + "'";
    try {
        return jdbc.query(query, mapper);
    } catch (Exception e) {}
    return List.of();
}`,
}

export async function reviewSnippet(body: PlaygroundRequest): Promise<PlaygroundResult> {
  return unwrap(await api.POST('/api/playground/review', { body }))
}

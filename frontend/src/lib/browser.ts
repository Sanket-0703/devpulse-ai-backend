/**
 * Full-page navigation to another site (e.g. GitHub's authorize page).
 * Wrapped in a module so tests can mock it: jsdom can't leave the page.
 */
export function redirectTo(url: string): void {
  window.location.assign(url)
}

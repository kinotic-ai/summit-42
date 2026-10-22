/** The message of a failed call, or the fallback when it carries none. */
export function errorText(err: unknown, fallback: string): string {
    return err instanceof Error && err.message ? err.message : fallback
}

/** The host and path of a URL without its scheme, as the UI shows an app's link. */
export function hostOf(url: string): string {
    return url.replace(/^https?:\/\//, '').replace(/\/$/, '')
}

const DATE = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' })

/** A date as the UI shows it, such as Oct 21, 2026, or an em dash when there is none. */
export function formatDate(value: string | number | null | undefined): string {
    const date = value != null ? new Date(value) : null
    return date && !Number.isNaN(date.getTime()) ? DATE.format(date) : '—'
}

const RELATIVE = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 365 * 24 * 3600],
    ['month', 30 * 24 * 3600],
    ['day', 24 * 3600],
    ['hour', 3600],
    ['minute', 60],
]

/** How long ago a time was, such as 12 minutes ago, or an em dash when there is none. */
export function timeAgo(value: string | number | null | undefined): string {
    const time = value != null ? new Date(value).getTime() : Number.NaN
    let ret = '—'
    if (!Number.isNaN(time)) {
        const seconds = Math.round((time - Date.now()) / 1000)
        const unit = UNITS.find(([, size]) => Math.abs(seconds) >= size)
        ret = unit ? RELATIVE.format(Math.round(seconds / unit[1]), unit[0]) : 'just now'
    }
    return ret
}

/** Copies text to the clipboard; resolves false when the browser refuses. */
export async function copyText(text: string): Promise<boolean> {
    let ret = true
    try {
        await navigator.clipboard.writeText(text)
    } catch {
        ret = false
    }
    return ret
}

/** Up to two initials of a name or email, for an avatar. */
export function initialsOf(name: string | null | undefined): string {
    const words = (name ?? '').split(/[\s@._-]+/).filter(word => word.length > 0)
    const letters = words.length > 1 ? words[0]![0]! + words[1]![0]! : (words[0] ?? '?').slice(0, 2)
    return letters.toUpperCase()
}

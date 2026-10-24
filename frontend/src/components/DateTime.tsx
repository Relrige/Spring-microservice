const dateTime = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});
const time = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** `18 Oct 2026, 14:05` in local time (design-system README, Dates). */
export function formatDateTime(value: string | Date): string {
  return dateTime.format(new Date(value));
}

/** `14:20` in local time. */
export function formatTime(value: string | Date): string {
  return time.format(new Date(value));
}

/** `#7f3a91c2`: the first 8 characters in the mono style, the full id on hover. */
export function ShortId({ id }: { id: string }) {
  return (
    <span className="vs-mono" title={id}>
      #{id.slice(0, 8)}
    </span>
  );
}

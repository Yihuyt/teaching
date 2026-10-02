interface RequestTicket<Snapshot> {
  readonly sequence: number
  readonly snapshot: Snapshot
}

interface LatestRequestGuard<Snapshot> {
  begin: () => RequestTicket<Snapshot>
  invalidate: () => void
  isCurrent: (ticket: RequestTicket<Snapshot>) => boolean
}

export function createLatestRequestGuard<Snapshot>(
  readSnapshot: () => Snapshot,
  snapshotsEqual: (left: Snapshot, right: Snapshot) => boolean = Object.is,
): LatestRequestGuard<Snapshot> {
  let sequence = 0

  return {
    begin(): RequestTicket<Snapshot> {
      sequence += 1
      return {
        sequence,
        snapshot: readSnapshot(),
      }
    },
    invalidate(): void {
      sequence += 1
    },
    isCurrent(ticket: RequestTicket<Snapshot>): boolean {
      return ticket.sequence === sequence && snapshotsEqual(ticket.snapshot, readSnapshot())
    },
  }
}

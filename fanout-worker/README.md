# Optional Code: Background Checker (Fan-Out Worker)

## Purpose

To know whether an engagement has an update waiting, we need to know which template version it is on.
The only way to find out is to ask the engagement system to open it. That takes **about 1 minute per
engagement**, and the team that owns that system can only handle a limited number of requests at once.

This worker makes those requests in the background: a few at a time, fairly between firms, without
repeating work, and without overloading the other team.

When a template is published, the worker does **not** re-check every engagement. For engagements we
already know about, the "update pending" flag comes from a quick database query (see the design
document). The worker only handles engagements we don't know yet: mostly all of them once, during the
first rollout, and afterwards the odd one we suspect is out of date.

## Technology

- **Java 21**, standard library only (records, thread pools, locks). No framework is needed for this size.
- **Maven** to build and **JUnit 5** for tests.
- **PostgreSQL** as the intended database. Tables, permissions and firm isolation are in
  `src/main/resources/schema.sql`.

## How It Works

1. Look in the tracking table for engagements marked "unknown" or "suspect".
2. Put them in a waiting line that takes turns between firms.
3. A fixed number of helper threads (20 by default) each take one engagement at a time.
4. Before calling the other team, the helper reserves the engagement in the table. If it was already
   checked in the meantime (for example, a user opened it), it is skipped.
5. The helper calls the other team's service, waits about a minute, and saves the answer.
6. If something goes wrong, one of three things happens:
   - a temporary problem is retried later, with longer waits each time;
   - if the other team says it is too busy, **all** helpers pause, and the engagement goes back in line
     **without using up one of its attempts**;
   - a permanent problem marks the engagement "failed" for a person to review.

This runs when a template is published, and also every few minutes as a safety net.

## Components

| Class | Role |
|---|---|
| `FanOutWorker` | The coordinator. Fills the waiting line, runs the helper threads, handles retries and pauses. |
| `FairWorkQueue` | The waiting line. Takes turns between firms so a large firm can't block small ones. |
| `EngagementStateStore` | The tracking table, where each engagement's version is recorded. |
| `EngagementLoaderClient` | The connection to the other team's service (the slow, one-minute call). |
| `DownstreamException` | What went wrong: *too busy*, *temporary problem*, or *permanent problem*. |
| `FanOutConfig` | The settings: number of helpers, wait times, number of attempts. |
| `WorkItem` | One job in the line: "check this engagement". |
| `EngagementRef`, `TemplateBinding`, `TemplatePublished` | Small data holders. |
| `FanOutMetrics` | Counters for a monitoring dashboard. |

## Design Decisions and Tradeoffs

**Fixed call limit.** The number of helper threads equals the limit agreed with the other team. With 20
helpers there are never more than 20 calls at once. The cost is that each helper waits a minute per
call, which is fine at this size.

**The database is the source of truth.** The waiting line is only temporary. If the worker crashes,
nothing is lost: unchecked engagements are still marked "unknown", and the reservation on the ones in
progress expires by itself so another worker picks them up. The cost is that a restart may repeat up to
20 calls that were in progress.

**No duplicate work.** The same message may arrive twice. Before calling, the helper reserves the
engagement and skips it if someone else holds it or it was already checked. When saving, newer
information always wins: each answer is stamped with the time the call *started*, so an update the user
made during that minute is never overwritten by our older answer. One rule for the real connection code:
it must give up on a call before the reservation expires (for example calls take 1 minute, give up
after 2, reservation lasts 3).

**Fair scheduling across firms.** A large firm's engagements would otherwise fill every helper for a long
time. The line gives each firm one turn per round. The limit: this is fair among the jobs currently in
the line (up to 20,000), not across everything in the table. I chose this because exact fairness would
make the database query more complex, and the rollout only happens once.

**Being polite to the other team.** "Too busy" answers pause every helper and do not count as failures
(this was a bug in my earlier version: a long overload could mark healthy engagements as failed).
Temporary errors are retried with growing waits plus some randomness. An engagement that keeps failing is
marked "failed" and shown on a dashboard instead of being retried forever.

**One worker per region.** It keeps things simple. If two run by mistake, the reservations in the table
still prevent duplicate work.

## Firm Isolation in the Database

`schema.sql` makes the database itself enforce which firm can see what, using PostgreSQL row-level
security. The API sets the firm once per request, and the database then shows only that firm's rows even
if a query forgets its `WHERE` clause. With no firm set, it shows nothing. The decision history table can
only be added to, never edited or deleted. The background checker uses a separate role that sees all
firms but cannot read decisions.

I checked this on a real PostgreSQL 16 database (`sql-checks/`): 9 checks covering reads, writes across
firms, the missing-firm case, the append-only history and the "which engagements are pending" query.

## Not Included

The real database code, the real connection to the other team's service, reading messages from the queue,
and sending counters to a monitoring tool. The interfaces are designed so these are small to add. The SQL
checks were run against PostgreSQL 16 only, and the Java tests use an in-memory copy of the table, so the
real database code is not covered by tests.

## Running the Tests

```bash
mvn test
```

The tests use a stand-in for the other team's service that answers in 10 milliseconds instead of a
minute. They check that the worker:

- never makes more calls at once than allowed;
- doesn't check an engagement twice when the same message arrives twice;
- skips an engagement that was checked some other way while it was waiting;
- never lets an older answer replace a newer one;
- retries temporary errors, but not permanent ones, and gives up after three tries;
- pauses when the other team is too busy, **and does not count that against the engagement**;
- **lets another worker take over when the first one dies while holding a reservation**;
- takes turns between firms.

The isolation checks run separately: `cd sql-checks && ./run.sh` (needs a local PostgreSQL). The `ERROR` lines
it prints are expected: they are the actions the database is supposed to block.

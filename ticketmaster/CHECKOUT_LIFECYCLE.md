# Booking hold and checkout lifecycle

Booking uses two bounded customer-facing inventory windows and one scheduler timestamp. They serve different purposes and must not be allowed to extend one another.

## State and deadlines

```text
AVAILABLE
   |
   | successful hold transaction
   v
HELD                         Hold.status = ACTIVE
   |                         deadline = Hold.expiresAt
   | start checkout before expiresAt
   v
CHECKOUT                     Hold.status = CHECKOUT_IN_PROGRESS
   |                         Booking.status = PENDING_PAYMENT
   |                         deadline = Hold.checkoutExpiresAt
   |
   +-- payment succeeds ------------------------------> BOOKED
   |
   +-- card declines / needs another payment method --> CHECKOUT
   |                                                     (same deadline)
   |
   +-- checkout deadline reached
          |
          | cancel/reconcile payment intent
          +-- provider proves SUCCEEDED --------------> BOOKED
          +-- provider proves CANCELED ---------------> AVAILABLE
          +-- provider outcome unknown ---------------> CHECKOUT + reconcile
```

Default runtime values are:

```text
ticketmaster.booking.hold-duration=PT5M
ticketmaster.booking.checkout-duration=PT10M
```

A customer who waits for the entire selection hold and then starts checkout can therefore protect inventory for at most roughly 15 minutes under normal, known payment outcomes. A payment-provider uncertainty can temporarily exceed that normal bound because releasing a seat before proving that the charge cannot succeed risks a paid customer losing the seat and the seat being sold twice.

## 1. Selection hold expiration

`Hold.expiresAt` applies to the ordinary `ACTIVE` / `HELD` phase. An expired `HELD` seat can be lazily reclaimed by another hold transaction.

Starting checkout is allowed only while the hold is still active. The transition to checkout is atomic across the Hold, all selected Seat items, the new Booking, and checkout idempotency state.

Once that transition succeeds, the ordinary hold deadline no longer controls seat reclamation. The seats are `CHECKOUT`, not `HELD`.

## 2. Checkout expiration

`checkoutExpiresAt` is created exactly once when checkout starts. It is an absolute deadline, not an inactivity timeout and not a sliding lease.

Payment retries, repeated checkout requests, page refreshes, provider callbacks, and reconciliation runs must never move this deadline. The checkout API returns the same `checkoutExpiresAt` on an idempotent retry so the client can display an honest countdown.

A normal card decline is recoverable. The payment provider should expose it as `REQUIRES_PAYMENT_METHOD`, and the customer should retry another payment method on the same logical Payment Intent. Booking remains `PENDING_PAYMENT`, the seats remain `CHECKOUT`, and `checkoutExpiresAt` is unchanged.

## 3. Deadline finalization

When `checkoutExpiresAt` is reached, Booking does not blindly release inventory. It first establishes a terminal payment outcome.

If the provider already reports `SUCCEEDED`, Booking atomically converts the Hold, confirms the Booking, and changes every Seat from `CHECKOUT` to `BOOKED`.

If payment is still retryable or processing, Booking asks the provider to cancel the Payment Intent. A successful cancellation must produce `CANCELED`, which means the intent can no longer create a charge. Only then does Booking atomically fail the Booking/Hold and release every Seat to `AVAILABLE`.

Cancellation can race with payment success. If the provider reports `SUCCEEDED`, success wins and the seats are booked rather than released.

If the provider is unavailable or cannot establish a terminal outcome, Booking keeps the seats in `CHECKOUT` and schedules another reconciliation. This is intentionally conservative: `checkoutExpiresAt` is the normal customer checkout limit, but payment uncertainty is a correctness exception, not permission to double-sell potentially paid inventory.

## 4. Reconciliation time is not an inventory lease

`Booking.nextReconcileAt` controls when the scheduler should query the payment provider again. Moving it forward for backoff does not extend `checkoutExpiresAt` and does not grant the customer more checkout time.

Likewise, provider callbacks can trigger reconciliation but cannot extend the inventory deadline.

## 5. Retry and abuse rules

The checkout window has no automatic extension after a decline. This prevents a customer or bot from repeatedly submitting failing cards to squat on scarce inventory indefinitely.

The recommended client behavior is:

1. Show the absolute checkout countdown from `checkoutExpiresAt`.
2. On a recoverable decline, explain the failure and immediately allow another payment method.
3. Reuse the same Payment Intent and Booking.
4. Never request a new checkout to reset the timer.
5. When the deadline passes, stop accepting new payment attempts and show that final payment reconciliation is in progress until Booking reaches `CONFIRMED` or `FAILED`.

An explicit user-abandon action may release inventory earlier, but browser close, network disconnect, or inactivity should not be trusted as proof that an in-flight payment cannot succeed.

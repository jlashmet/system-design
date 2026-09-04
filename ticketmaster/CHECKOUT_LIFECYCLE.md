# Booking checkout lifecycle

Seat selection is client-side UI state. The booking service does not reserve inventory merely because a customer clicks seats on the map. Inventory becomes authoritative only when the customer clicks **Next** and enters the payment page.

Booking therefore uses one bounded customer-facing checkout deadline plus a separate reconciliation scheduler timestamp. The scheduler timestamp must never extend the checkout deadline.

## State and deadline

```text
seat selection (client only)
   |
   | Next
   | authoritative checkout transaction
   v
AVAILABLE -----------------------------> CHECKOUT
                                        | Hold.status = CHECKOUT_IN_PROGRESS
                                        | Booking.status = PENDING_PAYMENT
                                        | deadline = Hold.checkoutExpiresAt
                                        |
                                        +-- payment succeeds ----------------> BOOKED
                                        |
                                        +-- card declines / needs another
                                        |   payment method -------------------> CHECKOUT
                                        |                                      (same deadline)
                                        |
                                        +-- checkout deadline reached
                                               |
                                               | cancel/reconcile payment intent
                                               +-- provider proves SUCCEEDED --> BOOKED
                                               +-- provider proves CANCELED ---> AVAILABLE
                                               +-- provider outcome unknown ---> CHECKOUT + reconcile
```

The default customer checkout window is:

```text
ticketmaster.booking.checkout-duration=PT10M
```

There is no separate pre-checkout hold timer or `HELD` seat state.

## 1. Entering checkout

When the customer clicks **Next** from seat selection, Booking:

1. verifies event write ownership and waiting-room admission;
2. reads the current authoritative prices for the selected seats;
3. executes one DynamoDB transaction that rechecks those prices, requires every seat to still be `AVAILABLE`, changes every selected seat directly to `CHECKOUT`, creates the checkout reservation, creates the pending Booking, and records checkout idempotency;
4. only after that transaction commits, creates or recovers the payment-provider intent.

This transaction is the inventory claim. There is no successful `HELD` state that must later be promoted into checkout, so there is no gap where a customer can own inventory without the checkout deadline already running.

The price conditions in the same transaction close the quote/claim race: if a seat's authoritative price changes after it was read but before the claim transaction, checkout conflicts rather than silently booking at a stale price.

## 2. Fixed checkout expiration

`checkoutExpiresAt` is created exactly once when the authoritative checkout transaction starts. It is an absolute deadline, not an inactivity timeout and not a sliding lease.

Payment retries, repeated checkout requests, page refreshes, provider callbacks, and reconciliation runs must never move this deadline. The checkout API returns the original `checkoutExpiresAt` on an idempotent retry so the client can display an honest countdown.

A normal card decline is recoverable. The payment provider exposes it as `REQUIRES_PAYMENT_METHOD`, and the customer may supply another payment method on the same logical Payment Intent. Booking remains `PENDING_PAYMENT`, the seats remain `CHECKOUT`, and `checkoutExpiresAt` is unchanged.

A repeated checkout request with the same event, authenticated user, idempotency key, and seat selection returns the original checkout. Reusing that key for a different seat selection is a conflict; it cannot create a second checkout or reset the deadline.

## 3. Deadline finalization

When `checkoutExpiresAt` is reached, Booking does not blindly release inventory. It first establishes a terminal payment outcome.

If the provider already reports `SUCCEEDED`, Booking atomically converts the checkout reservation, confirms the Booking, and changes every Seat from `CHECKOUT` to `BOOKED`.

If payment is still retryable or processing, Booking asks the provider to cancel the Payment Intent. A successful cancellation must produce `CANCELED`, which means the intent can no longer create a charge. Only then does Booking atomically fail the Booking/reservation and release every Seat to `AVAILABLE`.

Cancellation can race with payment success. If the provider reports `SUCCEEDED`, success wins and the seats are booked rather than released.

If the provider is unavailable or cannot establish a terminal outcome, Booking keeps the seats in `CHECKOUT` and schedules another reconciliation. This is intentionally conservative: `checkoutExpiresAt` is the customer checkout limit, but payment uncertainty is a correctness exception. Releasing inventory while a charge might have succeeded can produce a paid customer without seats and can allow the same seat to be sold twice.

For that reason, an expired `CHECKOUT` seat is never reclaimed merely by comparing the wall clock to its deadline.

## 4. Reconciliation time is not an inventory lease

`Booking.nextReconcileAt` controls when the scheduler should query the payment provider again. Moving it forward for backoff does not extend `checkoutExpiresAt` and does not grant the customer more checkout time.

Likewise, provider callbacks can trigger reconciliation but cannot extend the inventory deadline.

## 5. Client behavior and abuse rules

The checkout window has no automatic extension after a decline. This prevents a customer or bot from repeatedly submitting failing cards to squat on scarce inventory indefinitely.

The recommended client behavior is:

1. Keep seat-map clicks local until the customer presses **Next**.
2. Call the checkout endpoint with the selected seat IDs; show the payment page only after that checkout succeeds.
3. Show the absolute countdown from `checkoutExpiresAt`.
4. On a recoverable decline, explain the failure and immediately allow another payment method.
5. Reuse the same Payment Intent and Booking.
6. Never request a new checkout or idempotency key merely to reset the timer.
7. When the deadline passes, stop accepting new payment attempts and show that final payment reconciliation is in progress until Booking reaches `CONFIRMED` or `FAILED`.

An explicit user-abandon action may eventually release inventory earlier, but browser close, network disconnect, or inactivity must not be treated as proof that an in-flight payment cannot succeed.

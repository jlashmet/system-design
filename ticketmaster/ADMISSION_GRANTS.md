# Short-lived admission grants

Admission grants are an optional waiting-room load optimization for hot events. They do not replace the authoritative admission state in DynamoDB and are disabled by default.

## Runtime flow

1. The client joins the normal waiting room and polls `GET /events/{eventId}/waiting-room` with its trusted `X-User-Id` identity.
2. `CheckAdmissionHandler` reads the authoritative event watermark and waiting-room entry.
3. When the decision is `ADMITTED` and grant issuance is enabled, the response also includes `admissionToken` and `admissionTokenExpiresAt`.
4. The client may send that token as `X-Admission-Token` on `POST /events/{eventId}/holds`.
5. A valid token lets `CreateHoldHandler` skip the repeated waiting-room admission/entry reads and proceed to authoritative price/inventory work.
6. A missing, expired, malformed, wrong-user, wrong-event, or otherwise invalid token falls back to the existing authoritative waiting-room reads. Grant verification/issuance failure also falls back rather than making the optimization a correctness dependency.

The regional `EventWriteAuthority` check still happens before idempotency, admission, pricing, or seat mutation. A token never grants cross-region write authority.

## Configuration

The default is disabled:

```text
ticketmaster.booking.admission.grant.mode=disabled
```

Enable HMAC grants with:

```text
ticketmaster.booking.admission.grant.mode=hmac
ticketmaster.booking.admission.grant.secret=<high-entropy-shared-secret>
ticketmaster.booking.admission.grant.ttl=PT30S
```

Blank secrets and non-positive TTLs fail startup. Unknown grant modes provide no `AdmissionGrantService` bean and therefore also fail startup instead of silently changing admission behavior.

## Token contract

`HmacAdmissionGrantService` uses a compact versioned token:

```text
v1.<event-b64url>.<user-b64url>.<expiry-epoch-millis>.<signature-b64url>
```

The HMAC-SHA256 signature covers every segment before the signature. Signature comparison is constant-time. The signed scope binds the grant to exactly one event and authenticated user, and the expiration is part of the signed material.

The token is proof of a recent admission decision, not a bearer identity credential. The public ingress must still authenticate the caller and inject/derive the trusted `UserId`; replaying another user's token with a different authenticated identity is rejected by scope validation.

## Correctness boundary

A grant only avoids two read-side admission checks. The hold path still uses the normal authoritative seat-price quote and conditional DynamoDB transaction, so a token cannot reserve unavailable seats, alter price, bypass hold idempotency, or cause double booking.

Keeping the fallback path is deliberate. If the HMAC secret is rotated incorrectly or the verifier has an operational problem, hold creation can still prove admission through DynamoDB rather than turning a load optimization into a booking outage.

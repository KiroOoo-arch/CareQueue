# CareQueue+

*Don't wait. Know when to come back.*

CareQueue+ is a virtual queue app for clinics, salons, pharmacies and any
walk-in service business. Customers join a queue from their phone, watch their
position update live, and get a **SmartReturn** recommendation — the exact time
to walk back instead of sitting in a waiting room. Business admins get a
console to open/close the queue, call the next customer, and see real service
statistics.

Built with **Kotlin + Jetpack Compose**, backed by **Firebase** (Auth,
Firestore, Cloud Messaging), structured as MVVM with the business rules
extracted into pure, unit-tested domain objects.

---

## Features

**Customers**
- Register / sign in (email + password)
- Browse available businesses and their service queues
- Join a queue remotely and receive a ticket number
- Live position tracking — "people ahead", "now serving", and status update in real time
- **SmartReturn** — estimated wait and a recommended return time, based on
  people ahead × average service time ÷ active counters, minus a 5-minute
  safety buffer
- Cancel an entry at any time
- Visit history with clearly labeled Served / Skipped / Cancelled visits

**Admins**
- Role-based routing: admins land on the Admin Dashboard after sign-in
- Queue Management: open / pause / close a queue, Call Next, Mark Served, Skip
- "Now Serving" persists on the queue, so it stays visible after a customer is served
- Analytics computed from real visit history: customers served, average
  service time, average wait, skipped/cancelled counts, and peak period
- Each admin only sees queues belonging to businesses they created

**Under the hood**
- Race-safe queue numbering: joins run in a Firestore **transaction**, so two
  simultaneous customers can never receive the same number
- Race-safe Call Next: the entry is claimed with a compare-and-set, so two
  admins tapping at once can't both call the same customer
- All status changes go through a single tested state machine (`QueueRules`)
- Resilient Firestore parsing: one malformed document can't crash the app
- Push notifications via FCM (data messages shown as notifications; the token
  is saved to the user's document)
- 52 unit tests + a repeatable end-to-end adb test script

---

## Getting started

### Prerequisites
- Android Studio (with its bundled JDK)
- An Android device or emulator on **Android 8.0+** (Android 13+ shows a
  notification permission prompt)
- A Firebase project with **Authentication (Email/Password)** and
  **Firestore** enabled

### Build and run

1. Clone and open in Android Studio:
   ```
   git clone https://github.com/KiroOoo-arch/CareQueue.git
   ```
2. Add your Firebase config:
   - In the [Firebase console](https://console.firebase.google.com), create a
     project, add an **Android app**, and download `google-services.json`
   - Place it at `app/google-services.json`
   - Enable **Email/Password** sign-in under Authentication → Sign-in method
3. Build and run: press **Run ▶** (Shift+F10) in Android Studio, or:
   ```
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

The app is also usable straight from a terminal:
```
./gradlew testDebugUnitTest        # run the 52 unit tests
```

### Seeding data

The app reads from four Firestore collections. A minimal setup:

- **`businesses/{autoId}`**
  ```json
  { "name": "City Clinic", "category": "clinic", "isOpen": true, "createdBy": "<admin uid>" }
  ```
- **`queues/{autoId}`**
  ```json
  { "businessId": "<business doc id>", "name": "General Consultation",
    "status": "OPEN", "currentNumber": 0, "nowServing": 0,
    "averageServiceMinutes": 4, "activeCounters": 1 }
  ```
  The **document ID is the authoritative queue ID** — keep any `queueId` field
  equal to it.
- **`users/{uid}`** — created automatically at registration. To make someone an
  admin, set `"role": "admin"` on their document, then have them sign in.
- **`queueEntries/{autoId}`** — created automatically when a customer joins.

> The admin dashboard only shows queues whose business `createdBy` equals the
> signed-in admin's user ID.

---

## How to use the app

### Customer flow

1. **Sign in or register.** New accounts are customers by default.
2. **Home** lists the available services (businesses). Tap one to open its
   service details.
3. **Service Details** shows whether the queue is open and who is being served.
   - Queue closed? You'll see "Queue Closed" and no join button — check back later.
   - Already holding an entry? You'll see your ticket and a **View My Queue** button.
4. **Join Queue** assigns you the next number and takes you to **My Queue**.
5. **My Queue** is the live screen: your number, people ahead, estimated wait,
   and the **SmartReturn** recommended return time. Leave the waiting room —
   the screen keeps updating via Firestore listeners.
6. When your turn approaches, you'll get a **push notification** (if enabled).
7. Done waiting in line? **Cancel Queue Entry** removes you; the visit appears
   in your history as Cancelled.
8. **History** (from Home) lists past visits with their outcome and timestamp.

### Admin flow

1. Sign in with an account whose user document has `role: "admin"`.
2. **Admin Dashboard** shows your queues with open/active counts.
   Tap a queue to manage it.
3. **Queue Management:**
   - **Call Next** — the earliest waiting customer becomes CALLED and "Now
     Serving" updates everywhere, live.
   - **Mark Served** — finishes that customer's visit and records the timing.
   - **Skip** — no-show; the visit is recorded as Skipped.
   - **Close Queue / Open Queue** — stops or resumes new joins. Reopening
     resets "Now Serving" for the new session.
4. **Analytics** (chart icon) — served/skipped/cancelled counts, average
   service and wait times, and your busiest hour, all computed from real
   entries.
5. **Settings / Logout** from the toolbar.

---

## Project structure

```
app/src/main/java/com/carequeue/plus/
├── data/
│   ├── firebase/       # Firestore config, FCM service, token manager
│   ├── model/          # User, Business, Queue, QueueEntry
│   └── repository/     # AuthRepository, QueueRepository (all Firestore I/O)
├── domain/
│   ├── analytics/      # QueueAnalytics — stats from entry history (pure)
│   ├── queue/          # QueueRules — numbering + status machine (pure)
│   └── smartereturn/   # SmartReturn — wait estimation (pure)
├── navigation/         # Compose Navigation graph
├── ui/                 # Compose screens (auth, customer, admin, components)
├── util/               # Date formatting, notification helper
└── viewmodel/          # AuthViewModel, QueueViewModel
```

The `domain/` layer has **no Firebase dependency**, which is what makes the
business rules unit-testable. Tests live in `app/src/test/`.

## Testing

```
./gradlew testDebugUnitTest         # 52 unit tests
bash scripts/e2e-test.sh            # full end-to-end run (needs an emulator)
bash scripts/e2e-test.sh --no-build # reuse the existing APK
```

The E2E script drives the real UI over adb — fresh install, sign-in, join,
cancel, admin call-serve-skip — and verifies every step against Firestore's
REST API as ground truth. It's self-resetting and safe to re-run.

## Security

`firestore.rules` contains production security rules (per-user ownership,
admin-only management, self-role protection). They are **not deployed by
default** — apply them via the Firebase CLI
(`firebase deploy --only firestore:rules`) or the Firebase console before real
use. Until then the database trusts client-side checks only.

## License

Unlicense — see [LICENSE](LICENSE).

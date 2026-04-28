# Firestore Structure for CACI

## Collection: users
- Path: `users/{uid}`
- Document ID: Firebase Auth UID
- Fields:
  - `name` (string)
  - `phone` (string)
  - `address` (string)
  - `age` (number)
  - `gender` (string)
  - `home_lat` (number)
  - `home_lng` (number)
  - `fcm_token` (string)
  - `locality` (string) — user's registered locality for water notifications
  - `alert_sent_today` (boolean)

### Example
```json
{
  "name": "Aarav Sharma",
  "phone": "+919876543210",
  "address": "Koramangala, Bengaluru",
  "locality": "Koramangala",
  "age": 30,
  "gender": "Male",
  "home_lat": 12.9352,
  "home_lng": 77.6245,
  "fcm_token": "fcm-token-value",
  "alert_sent_today": false
}
```

## Collection: alert_log
- Path: `alert_log/{truck_id}`
- Document ID: Truck ID
- Fields:
  - `truck_id` (string)
  - `alerted_500m` (boolean, default `false`)
  - `last_alert_time` (timestamp)

### Example
```json
{
  "truck_id": "truck_001",
  "alerted_500m": false,
  "last_alert_time": "2026-04-26T10:00:00Z"
}
```

## Collection: waterReleases
- Path: `waterReleases/{releaseId}`
- Document ID: Auto-generated
- Fields:
  - `locality` (string) — target locality name
  - `scheduledTime` (timestamp) — scheduled water release time
  - `duration` (string) — e.g. "1 hour", "30 minutes"
  - `note` (string) — optional officer note
  - `officerId` (string) — ID of the scheduling officer
  - `createdAt` (timestamp) — server timestamp
  - `status` (string) — "scheduled" | "released" | "cancelled"
  - `latitude` (number) — locality latitude
  - `longitude` (number) — locality longitude

### Example
```json
{
  "locality": "Koramangala",
  "scheduledTime": "2026-04-28T14:00:00Z",
  "duration": "1 hour",
  "note": "Main pipeline release",
  "officerId": "officer123",
  "createdAt": "2026-04-28T12:00:00Z",
  "status": "scheduled",
  "latitude": 12.9352,
  "longitude": 77.6245
}
```

## Collection: waterNotifications
- Path: `waterNotifications/{notifId}`
- Document ID: Auto-generated
- Fields:
  - `topic` (string) — FCM topic e.g. "locality_koramangala"
  - `locality` (string)
  - `title` (string)
  - `body` (string)
  - `scheduledTime` (timestamp)
  - `createdAt` (timestamp)

## FCM Topics
- Convention: `locality_` + lowercase locality name with underscores
- Examples: `locality_koramangala`, `locality_hsr_layout`, `locality_whitefield`
- Users subscribe to their locality topic on registration/profile update

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

### Example
```json
{
  "name": "Aarav Sharma",
  "phone": "+919876543210",
  "address": "Koramangala, Bengaluru",
  "age": 30,
  "gender": "Male",
  "home_lat": 12.9352,
  "home_lng": 77.6245,
  "fcm_token": "fcm-token-value"
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

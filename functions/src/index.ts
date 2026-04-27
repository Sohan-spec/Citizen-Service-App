import * as admin from "firebase-admin";
import {onDocumentWritten} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {logger} from "firebase-functions";
import * as dotenv from "dotenv";
import * as path from "path";

dotenv.config({path: path.resolve(__dirname, "../../.env")});

admin.initializeApp();

const db = admin.firestore();

/**
 * Generate a pass-through route: starts ~800m away, approaches center
 * (enters 500m circle), passes through ~100m from center, then exits
 * back out to ~800m. One-way pass, no looping inside the circle.
 */
function generatePassThroughWaypoints(
  centerLat: number,
  centerLng: number,
): Array<[number, number]> {
  const mPerDegLat = 111320;
  const mPerDegLng = 111320 * Math.cos((centerLat * Math.PI) / 180);

  // Distances in meters from center along the approach axis
  const distances = [
    600, 450,            // outside → approaching
    350, 150,            // inside circle
    350, 450, 600, 750,  // exiting
  ];

  // Approach from south-west, pass through, exit to north-east
  const points: Array<[number, number]> = [];
  const totalSteps = distances.length;

  for (let i = 0; i < totalSteps; i++) {
    const dist = distances[i];
    // First half: approaching from south-west (angle ~225°)
    // Second half: exiting to north-east (angle ~45°)
    let angle: number;
    if (i < totalSteps / 2) {
      // Approaching from south-west
      angle = (225 * Math.PI) / 180;
    } else {
      // Exiting to north-east
      angle = (45 * Math.PI) / 180;
    }
    const dLat = (dist * Math.cos(angle)) / mPerDegLat;
    const dLng = (dist * Math.sin(angle)) / mPerDegLng;
    points.push([centerLat + dLat, centerLng + dLng]);
  }

  return points;
}

function toRadians(value: number): number {
  return (value * Math.PI) / 180;
}

function haversineDistanceMeters(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
): number {
  const earthRadiusMeters = 6371000;
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRadians(lat1)) *
      Math.cos(toRadians(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return earthRadiusMeters * c;
}

export const simulateTruckMovement = onSchedule("every 1 minutes", async () => {
  const truckRef = db.collection("trucks").doc("truck_001");

  // Find any user to use as the center for simulation
  const usersSnapshot = await db.collection("users").limit(1).get();
  let centerLat = 12.9352;
  let centerLng = 77.6245;

  if (!usersSnapshot.empty) {
    const userData = usersSnapshot.docs[0].data();
    const uLat = Number(userData.home_lat);
    const uLng = Number(userData.home_lng);
    if (Number.isFinite(uLat) && Number.isFinite(uLng)) {
      centerLat = uLat;
      centerLng = uLng;
    }
  }

  const waypoints = generatePassThroughWaypoints(centerLat, centerLng);
  const currentCenter = `${centerLat},${centerLng}`;

  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(truckRef);

    let currentIndex = 0;

    if (snapshot.exists) {
      const data = snapshot.data() ?? {};
      const storedCenter = data.route_center ?? "";
      currentIndex = Number(data.waypoint_index ?? 0);

      // If user location changed, reset the route
      if (storedCenter !== currentCenter) {
        currentIndex = 0;
      } else {
        currentIndex = currentIndex + 1;
        // Once the truck finishes the pass-through, stop at last waypoint
        if (currentIndex >= waypoints.length) {
          currentIndex = waypoints.length - 1;
        }
      }
    }

    const [nextLat, nextLng] = waypoints[currentIndex];

    const updateData: Record<string, unknown> = {
      latitude: nextLat,
      longitude: nextLng,
      waypoint_index: currentIndex,
      route_center: currentCenter,
      truck_id: "truck_001",
      truck_type: "Municipal Waste",
      updated_at: admin.firestore.FieldValue.serverTimestamp(),
    };

    if (snapshot.exists) {
      transaction.update(truckRef, updateData);
    } else {
      transaction.set(truckRef, updateData);
    }
  });

  logger.info("Truck movement simulated for truck_001", {centerLat, centerLng});
});

export const checkProximityAndAlert = onDocumentWritten(
  "trucks/{truckId}",
  async (event) => {
    const afterData = event.data?.after?.data();
    const truckId = event.params.truckId;

    if (!afterData) {
      return;
    }

    const truckLat = Number(afterData.latitude);
    const truckLng = Number(afterData.longitude);
    const truckType = String(afterData.truck_type ?? "Unknown");
    const truckCode = String(afterData.truck_id ?? truckId);

    if (!Number.isFinite(truckLat) || !Number.isFinite(truckLng)) {
      logger.warn("Truck coordinates are invalid", {truckId});
      return;
    }

    // Check if we already sent a notification for this truck today
    const alertRef = db.collection("alert_log").doc(truckId);
    const alertSnap = await alertRef.get();
    const alreadyAlerted = Boolean(alertSnap.data()?.alerted_500m ?? false);

    // If already alerted, do NOT send again — one notification only
    if (alreadyAlerted) {
      return;
    }

    const usersSnapshot = await db.collection("users").get();
    const recipients: Array<{token: string; distance: number}> = [];

    usersSnapshot.forEach((doc) => {
      const user = doc.data();
      const homeLat = Number(user.home_lat);
      const homeLng = Number(user.home_lng);
      const token = typeof user.fcm_token === "string" ? user.fcm_token : "";

      if (!Number.isFinite(homeLat) || !Number.isFinite(homeLng)) {
        return;
      }

      const distance = haversineDistanceMeters(
        truckLat,
        truckLng,
        homeLat,
        homeLng,
      );

      if (distance < 500 && token.trim().length > 0) {
        recipients.push({token, distance});
      }
    });

    if (recipients.length > 0) {
      await Promise.all(
        recipients.map((r) =>
          admin.messaging().send({
            token: r.token,
            notification: {
              title: "🚛 Garbage Truck Nearby!",
              body:
                `Truck ID: ${truckCode} | Waste Type: ${truckType} | ` +
                `~${Math.round(r.distance)}m from your house`,
            },
          }),
        ),
      );

      // Mark as alerted — will NOT send again until reset
      await alertRef.set(
        {
          truck_id: truckCode,
          alerted_500m: true,
          last_alert_time: admin.firestore.FieldValue.serverTimestamp(),
        },
        {merge: true},
      );

      logger.info("Proximity alert sent (one-time)", {
        truckId,
        recipientCount: recipients.length,
      });
    }
  },
);

/**
 * Daily reset — clears alert flags so users get one fresh alert per day.
 * Runs at midnight IST (18:30 UTC previous day).
 */
export const resetDailyAlerts = onSchedule("every day 18:30", async () => {
  const alertDocs = await db.collection("alert_log").get();
  const batch = db.batch();
  alertDocs.forEach((doc) => {
    batch.update(doc.ref, {alerted_500m: false});
  });
  await batch.commit();

  // Also reset truck waypoint to restart the route
  const truckRef = db.collection("trucks").doc("truck_001");
  const truckSnap = await truckRef.get();
  if (truckSnap.exists) {
    await truckRef.update({waypoint_index: 0});
  }

  logger.info("Daily alert flags and truck route reset");
});
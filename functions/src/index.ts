import * as admin from "firebase-admin";
import {onDocumentWritten} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {logger} from "firebase-functions";

admin.initializeApp();

const db = admin.firestore();

const WAYPOINTS: Array<[number, number]> = [
  [12.9320, 77.6210],
  [12.9330, 77.6220],
  [12.9338, 77.6230],
  [12.9345, 77.6238],
  [12.9350, 77.6243],
  [12.9352, 77.6245],
  [12.9358, 77.6252],
  [12.9365, 77.6260],
  [12.9370, 77.6270],
  [12.9360, 77.6280],
  [12.9345, 77.6270],
  [12.9330, 77.6255],
  [12.9320, 77.6240],
];

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

  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(truckRef);
    if (!snapshot.exists) {
      throw new Error("trucks/truck_001 does not exist");
    }

    const data = snapshot.data() ?? {};
    const currentIndex = Number(data.waypoint_index ?? 0);
    const nextIndex = (currentIndex + 1) % WAYPOINTS.length;
    const [nextLat, nextLng] = WAYPOINTS[nextIndex];

    transaction.update(truckRef, {
      latitude: nextLat,
      longitude: nextLng,
      waypoint_index: nextIndex,
      updated_at: admin.firestore.FieldValue.serverTimestamp(),
    });
  });

  logger.info("Truck movement simulated for truck_001");
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

    const alertRef = db.collection("alert_log").doc(truckId);
    const alertSnap = await alertRef.get();
    const currentlyAlerted = Boolean(alertSnap.data()?.alerted_500m ?? false);

    const usersSnapshot = await db.collection("users").get();

    const recipients: Array<{token: string; distance: number}> = [];
    let anyWithin1000m = false;

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

      if (distance <= 1000) {
        anyWithin1000m = true;
      }

      if (distance < 500 && token.trim().length > 0) {
        recipients.push({token, distance});
      }
    });

    if (recipients.length > 0 && !currentlyAlerted) {
      await Promise.all(
        recipients.map((r) =>
          admin.messaging().send({
            token: r.token,
            notification: {
              title: "🚛 Garbage Truck Nearby!",
              body:
                `Truck ${truckCode} (${truckType}) is approximately ` +
                `${Math.round(r.distance)}m from your home`,
            },
          }),
        ),
      );

      await alertRef.set(
        {
          truck_id: truckCode,
          alerted_500m: true,
          last_alert_time: admin.firestore.FieldValue.serverTimestamp(),
        },
        {merge: true},
      );

      logger.info("Proximity alerts sent", {
        truckId,
        recipientCount: recipients.length,
      });
      return;
    }

    if (!anyWithin1000m && currentlyAlerted) {
      await alertRef.set(
        {
          truck_id: truckCode,
          alerted_500m: false,
          last_alert_time: admin.firestore.FieldValue.serverTimestamp(),
        },
        {merge: true},
      );
      logger.info("Alert window reset for truck", {truckId});
    }
  },
);
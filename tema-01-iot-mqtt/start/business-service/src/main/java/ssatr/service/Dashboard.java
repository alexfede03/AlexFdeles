package ssatr.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat.
 */
public class Dashboard {

    // Structură internă pentru reținerea metricilor per dispozitiv
    private static class DeviceState {
        double lastTemperature = 0.0;
        long lastTimestamp = 0;
        int alarmCount = 0;
        String lastStatus = "-";
    }

    private final Map<String, DeviceState> devices = new LinkedHashMap<>();

    private synchronized DeviceState getOrCreate(String deviceId) {
        return devices.computeIfAbsent(deviceId, k -> new DeviceState());
    }

    /** Actualizare la primirea unui mesaj de telemetrie */
    public synchronized void updateTelemetry(String deviceId, long timestamp, double temperature) {
        DeviceState state = getOrCreate(deviceId);
        state.lastTemperature = temperature;
        state.lastTimestamp = timestamp;
    }

    /** Actualizare la primirea unei alarme */
    public synchronized void recordAlarm(String deviceId, long timestamp, double temperature) {
        DeviceState state = getOrCreate(deviceId);
        state.lastTemperature = temperature;
        state.lastTimestamp = timestamp;
        state.alarmCount++;
    }

    /** Actualizare la primirea unei confirmări de status */
    public synchronized void updateStatus(String deviceId, long timestamp, String statusText) {
        DeviceState state = getOrCreate(deviceId);
        state.lastTimestamp = timestamp;
        state.lastStatus = statusText;
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("Niciun dispozitiv înregistrat momentan.");
            return;
        }

        long now = System.currentTimeMillis();
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("%-15s %-10s %-18s %-10s %-20s%n", "DEVICE", "TEMP (°C)", "ULTIMUL MESAJ", "ALARME", "ULTIMUL STATUS");
        System.out.println("--------------------------------------------------------------------------------");

        for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
            String id = entry.getKey();
            DeviceState state = entry.getValue();

            long ageSeconds = Math.max(0, (now - state.lastTimestamp) / 1000);
            String ageText = "acum " + ageSeconds + " s";

            System.out.printf(Locale.US, "%-15s %-10.2f %-18s %-10d %-20s%n",
                    id, state.lastTemperature, ageText, state.alarmCount, state.lastStatus);
        }
        System.out.println("--------------------------------------------------------------------------------");
    }
}
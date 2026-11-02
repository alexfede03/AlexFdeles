package ssatr.service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Dashboard {

    private static class DeviceState {
        String deviceId;
        double lastTemperature;
        long lastMessageTime;
        int alarmCount;

        DeviceState(String deviceId) {
            this.deviceId = deviceId;
            this.lastMessageTime = System.currentTimeMillis();
        }
    }

    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();

    public synchronized void recordTelemetry(String deviceId, double temperature, long timestamp) {
        DeviceState state = devices.computeIfAbsent(deviceId, DeviceState::new);
        state.lastTemperature = temperature;
        state.lastMessageTime = timestamp;
    }

    public synchronized void recordAlarm(String deviceId, double temperature, long timestamp) {
        DeviceState state = devices.computeIfAbsent(deviceId, DeviceState::new);
        state.alarmCount++;
        state.lastTemperature = temperature;
        state.lastMessageTime = timestamp;
    }

    public synchronized void recordStatus(String deviceId, long timestamp) {
        DeviceState state = devices.computeIfAbsent(deviceId, DeviceState::new);
        state.lastMessageTime = timestamp;
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("Niciun dispozitiv conectat încă.");
            return;
        }

        System.out.printf("%-18s %-10s %-16s %-8s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME");
        System.out.println("-------------------------------------------------------");

        long now = System.currentTimeMillis();
        for (DeviceState dev : devices.values()) {
            long diffSec = (now - dev.lastMessageTime) / 1000;
            String timeAgo = (diffSec <= 1) ? "acum 1 s" : "acum " + diffSec + " s";
            String tempStr = String.format(Locale.US, "%.2f °C", dev.lastTemperature);

            System.out.printf("%-18s %-10s %-16s %-8d%n",
                    dev.deviceId, tempStr, timeAgo, dev.alarmCount);
        }
        System.out.println();
    }
}
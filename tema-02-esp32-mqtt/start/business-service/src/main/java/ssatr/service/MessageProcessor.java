package ssatr.service;

import java.util.concurrent.BlockingQueue;

public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String message = inbox.take();
                storage.append(kind, message);

                String[] parts = message.split(";");
                if (parts.length < 3) {
                    continue; // mesaj invalid, ignorăm fără să oprim thread-ul
                }

                String deviceId = parts[0];
                long timestamp = Long.parseLong(parts[1]);

                switch (kind) {
                    case "telemetry":
                        double temp = Double.parseDouble(parts[2]);
                        dashboard.recordTelemetry(deviceId, temp, timestamp);
                        break;

                    case "alarm":
                        double alarmTemp = Double.parseDouble(parts[2]);
                        double threshold = Double.parseDouble(parts[3]);
                        dashboard.recordAlarm(deviceId, alarmTemp, timestamp);
                        System.out.printf("\n[ALARMĂ!] %s: T=%.2f depășește pragul=%.2f%n> ",
                                deviceId, alarmTemp, threshold);
                        break;

                    case "status":
                        String text = parts[2];
                        dashboard.recordStatus(deviceId, timestamp);
                        System.out.printf("\n[STATUS] %s: %s%n> ", deviceId, text);
                        break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Format invalid sau eroare de parsare: continuă fără să oprească thread-ul
                System.out.println("\n[Eroare procesare " + kind + "]: " + e.getMessage() + "\n> ");
            }
        }
    }
}
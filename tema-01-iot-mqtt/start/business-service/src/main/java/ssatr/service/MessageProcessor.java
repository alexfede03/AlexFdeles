package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
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
                // 1. Preluăm mesajul din coadă
                String rawMessage = inbox.take();

                // 2. Salvare în CSV exact cum a sosit
                storage.append(kind, rawMessage);

                // 3. Parsare și actualizare Dashboard
                try {
                    processMessage(rawMessage);
                } catch (Exception ex) {
                    System.err.printf("[%s] Mesaj invalid primit: \"%s\" (Eroare: %s)%n",
                            kind, rawMessage, ex.getMessage());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processMessage(String rawMessage) {
        String[] parts = rawMessage.split(";");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Număr insuficient de câmpuri");
        }

        String deviceId = parts[0].trim();
        long timestamp = Long.parseLong(parts[1].trim());

        switch (kind) {
            case "telemetry":
                double temp = Double.parseDouble(parts[2].trim());
                dashboard.updateTelemetry(deviceId, timestamp, temp);
                break;

            case "alarm":
                double alarmTemp = Double.parseDouble(parts[2].trim());
                double threshold = parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 0.0;
                dashboard.recordAlarm(deviceId, timestamp, alarmTemp);
                // Afișare imediată în consolă pentru operator
                System.out.printf("%n[ALARMĂ PRIMITĂ] %s -> Temp: %.2f °C depășește pragul: %.2f °C%n> ",
                        deviceId, alarmTemp, threshold);
                break;

            case "status":
                String statusText = parts[2].trim();
                dashboard.updateStatus(deviceId, timestamp, statusText);
                // Afișare imediată în consolă pentru operator
                System.out.printf("%n[STATUS DEVICE] %s -> %s%n> ", deviceId, statusText);
                break;

            default:
                System.err.println("Tip de mesaj necunoscut: " + kind);
        }
    }
}
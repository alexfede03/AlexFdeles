package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;
    private final SporadicAlarmTask sporadic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic,
                                SporadicAlarmTask sporadic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
        this.sporadic = sporadic;
    }

    @Override
    public void run() {
        // TODO 3: implementați taskul aperiodic:
        //   - preluați comenzile din coadă: commands.take()
        //   - separați numele comenzii de parametru: command.split(" ")
        //   - executați comanda (periodic.setPeriodMs(...), sensor.reset() etc.)
        //   - publicați confirmarea pe Config.statusTopic(deviceId)
        //   - o comandă necunoscută sau un parametru invalid nu trebuie să
        //     oprească taskul: publicați "ERROR <motiv>"
        //
        // Vezi exemplul exemple/01-taskuri/.../AperiodicTaskExample.java
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String rawCommand = commands.take();
                long now = System.currentTimeMillis();

                String statusResponse;
                try {
                    statusResponse = processCommand(rawCommand);
                } catch (Exception ex) {
                    statusResponse = "ERROR " + ex.getMessage();
                }

                String message = String.format(Locale.US, "%s;%d;%s", deviceId, now, statusResponse);
                mqtt.publish(Config.statusTopic(deviceId), message);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private String processCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            return "ERROR comanda_goala";
        }

        String[] parts = rawCommand.trim().split("\\s+");
        String action = parts[0].toUpperCase();

        switch (action) {
            case "SET_INTERVAL":
                if (parts.length < 2) return "ERROR parametru_lipsa_interval";
                long interval = Long.parseLong(parts[1]);
                if (interval <= 0) return "ERROR interval_invalid";
                periodic.setPeriodMs(interval);
                return "OK SET_INTERVAL " + interval;

            case "SET_THRESHOLD":
                if (parts.length < 2) return "ERROR parametru_lipsa_prag";
                double threshold = Double.parseDouble(parts[1]);
                // Se actualizează pragul în ambele taskuri
                periodic.setThreshold(threshold);
                if (sporadic != null) {
                    sporadic.setThreshold(threshold);
                }
                return "OK SET_THRESHOLD " + String.format(Locale.US, "%.2f", threshold);

            case "RESET":
                sensor.reset();
                return "OK RESET";

            case "STATUS":
                return String.format(Locale.US, "OK STATUS interval=%d threshold=%.2f",
                        periodic.getPeriodMs(), periodic.getThreshold());

            default:
                return "ERROR comanda_necunoscuta: " + action;
        }
    }
}
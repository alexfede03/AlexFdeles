package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // Declarația variabilei threshold
    private volatile double threshold = 28.0;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long lastRelease = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Double temperature = alarmEvents.take();
                long now = System.currentTimeMillis();

                long sinceLast = now - lastRelease;
                if (lastRelease != 0 && sinceLast < MIN_INTERARRIVAL_MS) {
                    System.out.printf("[sporadic] Alarma RESPINSĂ pentru %s: au trecut doar %d ms (interval minim %d ms)%n",
                            deviceId, sinceLast, MIN_INTERARRIVAL_MS);
                    continue;
                }

                lastRelease = now;

                String message = String.format(Locale.US, "%s;%d;%.2f;%.2f",
                        deviceId, now, temperature, threshold);
                mqtt.publish(Config.alarmTopic(deviceId), message);

                System.out.printf("[sporadic] Alarma ACCEPTATĂ și publicată pentru %s: temp=%.2f, prag=%.2f%n",
                        deviceId, temperature, threshold);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public double getThreshold() {
        return threshold;
    }
}
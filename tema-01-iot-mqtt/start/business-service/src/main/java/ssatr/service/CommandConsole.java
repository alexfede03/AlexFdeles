package ssatr.service;

import java.util.Scanner;

/**
 * Consola operatorului: citește comenzi de la tastatură.
 *
 *   show                               afișează dashboard-ul
 *   <deviceId> <COMANDA> [parametru]   trimite o comandă unui device, de exemplu
 *                                      device-1 SET_INTERVAL 1000
 *   help                               afișează comenzile
 *   exit                               oprește serviciul
 */
public class CommandConsole implements Runnable {

    private final MqttConnection mqtt;
    private final Dashboard dashboard;

    public CommandConsole(MqttConnection mqtt, Dashboard dashboard) {
        this.mqtt = mqtt;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        printHelp();

        while (scanner.hasNextLine()) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            } else if (line.equalsIgnoreCase("exit")) {
                break;
            } else if (line.equalsIgnoreCase("help")) {
                printHelp();
            } else if (line.equalsIgnoreCase("show")) {
                dashboard.print();
            } else {
                sendCommand(line);
            }
        }
    }

    private void sendCommand(String line) {
        // TODO 6: separăm deviceId de restul comenzii
        int firstSpace = line.indexOf(' ');
        if (firstSpace == -1) {
            System.out.println("Eroare format: Comanda trebuie să fie de forma '<deviceId> <COMANDA> [parametru]'.");
            return;
        }

        String deviceId = line.substring(0, firstSpace).trim();
        String commandPayload = line.substring(firstSpace + 1).trim();

        if (deviceId.isEmpty() || commandPayload.isEmpty()) {
            System.out.println("Eroare: deviceId sau comanda lipsesc.");
            return;
        }

        try {
            String topic = Config.commandTopic(deviceId);
            mqtt.publish(topic, commandPayload);
            System.out.printf("Comandă trimisă către [%s]: \"%s\" pe topicul %s%n", deviceId, commandPayload, topic);
        } catch (Exception ex) {
            System.err.println("Eroare la publicarea comenzii: " + ex.getMessage());
        }
    }

    private void printHelp() {
        System.out.println("Comenzi disponibile:");
        System.out.println("  show                                     - afișează starea dispozitivelor");
        System.out.println("  <deviceId> SET_INTERVAL <ms>            - schimbă perioada telemetriei");
        System.out.println("  <deviceId> SET_THRESHOLD <valoare>      - schimbă pragul de alarmă");
        System.out.println("  <deviceId> RESET                         - resetează senzorul");
        System.out.println("  <deviceId> STATUS                        - interoghează starea device-ului");
        System.out.println("  help                                     - afișează acest ghid");
        System.out.println("  exit                                     - oprește aplicația");
    }
}
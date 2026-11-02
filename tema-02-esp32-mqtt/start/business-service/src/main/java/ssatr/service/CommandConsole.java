package ssatr.service;

import java.util.Scanner;

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
        // Separa deviceId de restul comenzii
        int firstSpace = line.indexOf(' ');
        if (firstSpace == -1) {
            System.out.println("Eroare format: folosiți <deviceId> <COMANDA> [parametru]");
            return;
        }

        String deviceId = line.substring(0, firstSpace).trim();
        String command = line.substring(firstSpace + 1).trim();

        if (deviceId.isEmpty() || command.isEmpty()) {
            System.out.println("Eroare: deviceId sau comanda lipsesc.");
            return;
        }

        String topic = Config.commandTopic(deviceId);
        mqtt.publish(topic, command);
        System.out.println("Comandă trimisă către " + deviceId + " [" + topic + "]: " + command);
    }

    private void printHelp() {
        System.out.println("\nComenzi disponibile:");
        System.out.println("  show                                               - afișează tabelul cu dispozitive");
        System.out.println("  <deviceId> SET_INTERVAL <ms>                       - schimbă perioada de telemetrie");
        System.out.println("  <deviceId> SET_THRESHOLD <valoare>                 - schimbă pragul de alarmă");
        System.out.println("  <deviceId> RESET                                   - resetează parametrii la valorile inițiale");
        System.out.println("  <deviceId> STATUS                                  - cere statusul dispozitivului");
        System.out.println("  help                                               - afișează acest ajutor");
        System.out.println("  exit                                               - oprește serviciul\n");
    }
}
// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                               |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

// Structuri pentru elementele din cozi (transmise prin valoare, nu prin pointeri)
typedef struct {
    float temperature;
    float threshold;
} AlarmEvent;

#define COMMAND_MAX_LEN 64
typedef struct {
    char text[COMMAND_MAX_LEN];
} CommandMessage;

// Handlere pentru cozile FreeRTOS
static QueueHandle_t alarmQueue = NULL;
static QueueHandle_t commandQueue = NULL;

// Variabile globale partajate între taskuri (volatile pentru modificări din taskuri concurente)
static volatile uint32_t current_period_ms = PERIOD_MS_INITIAL;
static volatile float current_threshold = THRESHOLD_INITIAL;

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
float read_temperature(void) {
    return temperatureRead();
}

// TODO 1: TASK PERIODIC, cu perioada T (inițial PERIOD_MS_INITIAL).
void periodic_telemetry_task(void *pvParameters) {
    TickType_t lastWakeTime = xTaskGetTickCount();
    char payload[128];

    for (;;) {
        uint32_t period = current_period_ms;
        // Așteptare absolută pe grilă r_k = r_0 + k*T (fără acumulare de derivă conform curs 2.3)
        xTaskDelayUntil(&lastWakeTime, pdMS_TO_TICKS(period));

        float temp = read_temperature();
        int64_t now_ms = epoch_ms();
        float thresh = current_threshold;

        // Publicare telemetrie: deviceId;timestamp;temperatura
        snprintf(payload, sizeof(payload), "%s;%lld;%.2f", DEVICE_ID, (long long)now_ms, temp);
        mqtt_publish("telemetry", payload);
        Serial.printf("[periodic] Telemetrie: %s\n", payload);

        // Dacă temperatura depășește pragul, trimitem evenimentul în coada de alarme
        if (temp > thresh) {
            AlarmEvent ev;
            ev.temperature = temp;
            ev.threshold = thresh;

            // Timeout 0: taskul periodic nu se blochează dacă coada este plină
            if (xQueueSend(alarmQueue, &ev, 0) != pdTRUE) {
                Serial.println("[periodic] Avertisment: Coada de alarme este plină!");
            }
        }
    }
}

// TODO 2: TASK SPORADIC, tratează alarmele de temperatură.
void sporadic_alarm_task(void *pvParameters) {
    AlarmEvent ev;
    uint32_t last_alarm_time = 0;
    char payload[128];

    for (;;) {
        // Așteaptă blocat sosirea unui eveniment în coada de alarme
        if (xQueueReceive(alarmQueue, &ev, portMAX_DELAY) == pdTRUE) {
            uint32_t now = millis(); // Timp monoton conform cerinței
            uint32_t elapsed = now - last_alarm_time;

            // Verificare interval minim între sosiri (MIN_INTERARRIVAL_MS din curs 3.2)
            if (last_alarm_time != 0 && elapsed < MIN_INTERARRIVAL_MS) {
                Serial.printf("[sporadic] Alarmă RESPINSĂ: au trecut doar %u ms (minim cerut: %d ms)\n",
                              elapsed, MIN_INTERARRIVAL_MS);
                continue;
            }

            last_alarm_time = now;
            int64_t now_ms = epoch_ms();

            // Publicare alarmă: deviceId;timestamp;temperatura;prag
            snprintf(payload, sizeof(payload), "%s;%lld;%.2f;%.2f",
                     DEVICE_ID, (long long)now_ms, ev.temperature, ev.threshold);
            mqtt_publish("alarm", payload);
            Serial.printf("[sporadic] Alarmă ACCEPTATĂ: %s\n", payload);
        }
    }
}

// TODO 3: TASK APERIODIC, execută comenzile primite de la business service.
void aperiodic_command_task(void *pvParameters) {
    CommandMessage msg;
    char response[128];
    char payload[256];

    for (;;) {
        // Așteaptă blocat comenzi puse în coadă
        if (xQueueReceive(commandQueue, &msg, portMAX_DELAY) == pdTRUE) {
            char cmd[32] = {0};
            char param[32] = {0};

            int tokens = sscanf(msg.text, "%31s %31s", cmd, param);

            if (tokens <= 0) {
                snprintf(response, sizeof(response), "ERROR comanda_goala");
            } else if (strcasecmp(cmd, "SET_INTERVAL") == 0) {
                if (tokens < 2) {
                    snprintf(response, sizeof(response), "ERROR parametru_lipsa_interval");
                } else {
                    long val = atol(param);
                    if (val <= 0) {
                        snprintf(response, sizeof(response), "ERROR interval_invalid");
                    } else {
                        current_period_ms = (uint32_t)val;
                        snprintf(response, sizeof(response), "OK SET_INTERVAL %ld", val);
                    }
                }
            } else if (strcasecmp(cmd, "SET_THRESHOLD") == 0) {
                if (tokens < 2) {
                    snprintf(response, sizeof(response), "ERROR parametru_lipsa_prag");
                } else {
                    float val = atof(param);
                    current_threshold = val;
                    snprintf(response, sizeof(response), "OK SET_THRESHOLD %.2f", val);
                }
            } else if (strcasecmp(cmd, "RESET") == 0) {
                current_period_ms = PERIOD_MS_INITIAL;
                current_threshold = THRESHOLD_INITIAL;
                snprintf(response, sizeof(response), "OK RESET");
            } else if (strcasecmp(cmd, "STATUS") == 0) {
                snprintf(response, sizeof(response), "OK STATUS interval=%u threshold=%.2f",
                         current_period_ms, current_threshold);
            } else {
                snprintf(response, sizeof(response), "ERROR comanda_necunoscuta: %s", cmd);
            }

            int64_t now_ms = epoch_ms();
            // Format status: deviceId;timestamp;text
            snprintf(payload, sizeof(payload), "%s;%lld;%s", DEVICE_ID, (long long)now_ms, response);
            mqtt_publish("status", payload);
            Serial.printf("[aperiodic] Răspuns: %s\n", payload);
        }
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    // TODO 3: punem comanda în coada de comenzi prin valoare, timeout 0
    if (commandQueue != NULL && text != NULL) {
        CommandMessage msg;
        strncpy(msg.text, text, COMMAND_MAX_LEN - 1);
        msg.text[COMMAND_MAX_LEN - 1] = '\0';
        xQueueSend(commandQueue, &msg, 0);
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);

    uint32_t t = millis();
    while (!Serial && (millis() - t < 3000)) delay(10); // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    // TODO 0 (a): Crearea cozilor FreeRTOS (înainte de mqtt_start)
    alarmQueue = xQueueCreate(10, sizeof(AlarmEvent));
    commandQueue = xQueueCreate(10, sizeof(CommandMessage));

    if (alarmQueue == NULL || commandQueue == NULL) {
        Serial.println("Eroare la crearea cozilor FreeRTOS!");
        while (1) delay(1000);
    }

    wifi_connect();
    time_sync();

    mqtt_start();

    // TODO 0 (b): Pornirea celor 3 taskuri FreeRTOS (după mqtt_start)
    // Priorități între 2 și 19: Sporadic (4) > Periodic (3) > Aperiodic (2) > loopTask (1)
    // Stiva de 4096 octeți pentru a permite formatarea snprintf cu numere zecimale
    xTaskCreate(sporadic_alarm_task,    "sporadic",   4096, NULL, 4, NULL);
    xTaskCreate(periodic_telemetry_task, "periodic",   4096, NULL, 3, NULL);
    xTaskCreate(aperiodic_command_task, "aperiodic",  4096, NULL, 2, NULL);

    Serial.println("[setup] Toate taskurile FreeRTOS au fost pornite.");
}

void loop() {
    // Totul se execută în taskurile FreeRTOS create
    delay(1000);
}
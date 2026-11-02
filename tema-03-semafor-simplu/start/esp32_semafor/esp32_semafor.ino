// Semafor pentru pietoni, cu buton, pe ESP32-C3 Super Mini.
//
// Placa este controlerul semaforului; lămpile sunt desenate de dashboard, care
// le aprinde după mesajele "lights" publicate de placă. Patru activități:
//
//   ISR buton BOOT ----+
//                      +--(coada butonului)--> task sporadic
//   command_received --+   (BUTTON)                 |
//                                                   v
//                                         [ starea semaforului ] <-- mutex
//                                            ^          |
//                                            |          v
//                         task periodic de control   task periodic de ieșire
//                         (fazele, T = 100 ms)       (lămpile, T = 500 ms) --> lights
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, pinii, perioadele, duratele fazelor
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

// Fazele semaforului
enum Phase { CAR_GREEN, CAR_YELLOW, ALL_RED, PED_GREEN };
const char* const PHASE_NAMES[] = {"CAR_GREEN", "CAR_YELLOW", "ALL_RED", "PED_GREEN"};

// Culorile unei lămpi
enum Light { RED, YELLOW, GREEN };
const char* const LIGHT_NAMES[] = {"RED", "YELLOW", "GREEN"};

// TODO 0 (c): Structura stării semaforului partajată între taskuri
struct TrafficState {
    Phase phase;
    uint32_t phase_start_ms; // momentul intrării în faza curentă (millis())
    bool waiting_requested;  // lampa AȘTEPTAȚI (cererea pietonului)
};

static TrafficState g_state;
static SemaphoreHandle_t g_state_mutex = NULL;

// Elementul din coada butonului (stochează momentul exact al apăsării)
struct ButtonPress {
    uint32_t at_ms;
};

static QueueHandle_t g_button_queue = NULL;

// TODO 3: Rutina de întrerupere pentru butonul BOOT (scurtă, în RAM)
void IRAM_ATTR button_isr(void) {
    ButtonPress p;
    p.at_ms = (uint32_t)(xTaskGetTickCountFromISR() * portTICK_PERIOD_MS);

    BaseType_t woken = pdFALSE;
    if (g_button_queue != NULL) {
        xQueueSendFromISR(g_button_queue, &p, &woken);
    }
    portYIELD_FROM_ISR(woken);
}

// TODO 3: Recepție comenzi MQTT (buton virtual din dashboard)
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    if (text != NULL && strcmp(text, "BUTTON") == 0) {
        ButtonPress p;
        p.at_ms = millis();
        if (g_button_queue != NULL) {
            xQueueSend(g_button_queue, &p, 0); // timeout 0, fără blocare
        }
    }
}

// TODO 3: Task sporadic - procesează apăsările și impune intervalul minim
static void sporadic_button_task(void* arg) {
    (void)arg;
    bool any = false;
    uint32_t last_accepted_ms = 0;

    for (;;) {
        ButtonPress p;
        xQueueReceive(g_button_queue, &p, portMAX_DELAY); // așteaptă o apăsare

        uint32_t since = p.at_ms - last_accepted_ms;

        // Filtrare bounce și limitare la cel mult o cerere pe secundă
        if (any && since < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] Apăsare t = %6lu ms RESPINSĂ (la %lu ms după cea acceptată)\n",
                          (unsigned long)p.at_ms, (unsigned long)since);
            continue;
        }

        any = true;
        last_accepted_ms = p.at_ms;
        Serial.printf("[sporadic] Apăsare t = %6lu ms ACCEPTATĂ -> Cerere activată\n",
                      (unsigned long)p.at_ms);

        // Actualizare stare cu mutexul ținut cât mai puțin
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        g_state.waiting_requested = true;
        xSemaphoreGive(g_state_mutex);
    }
}

// TODO 1: Task periodic de control (T = 100 ms)
static void control_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();

    for (;;) {
        uint32_t now = millis();
        bool phase_changed = false;
        Phase before, next;

        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        before = g_state.phase;
        next = before;
        uint32_t elapsed = now - g_state.phase_start_ms;

        switch (before) {
            case CAR_GREEN:
                // Se trece în galben doar după verdele minim și dacă există cerere
                if (elapsed >= CAR_GREEN_MIN_MS && g_state.waiting_requested) {
                    next = CAR_YELLOW;
                }
                break;

            case CAR_YELLOW:
                if (elapsed >= CAR_YELLOW_MS) {
                    next = ALL_RED;
                }
                break;

            case ALL_RED:
                if (elapsed >= ALL_RED_MS) {
                    next = PED_GREEN;
                }
                break;

            case PED_GREEN:
                if (elapsed >= PED_GREEN_MS) {
                    next = CAR_GREEN;
                }
                break;
        }

        if (next != before) {
            g_state.phase = next;
            g_state.phase_start_ms = now;

            // Cererea pietonului se șterge la intrarea în PED_GREEN
            if (next == PED_GREEN) {
                g_state.waiting_requested = false;
            }
            phase_changed = true;
        }
        xSemaphoreGive(g_state_mutex);

        // Afișare pe Serial după eliberarea mutexului
        if (phase_changed) {
            Serial.printf("[control] Fază: %s -> %s\n",
                          PHASE_NAMES[before], PHASE_NAMES[next]);
        }

        xTaskDelayUntil(&last, pdMS_TO_TICKS(CONTROL_PERIOD_MS));
    }
}

// TODO 2: Task periodic de ieșire (T = 500 ms)
static void output_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();
    char payload[128];

    for (;;) {
        uint32_t now = millis();

        // Copiem starea într-o variabilă locală cu mutexul ținut cât mai puțin
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        TrafficState s = g_state;
        xSemaphoreGive(g_state_mutex);

        // Stabilire culori lămpi
        Light car_light = RED;
        Light ped_light = RED;

        switch (s.phase) {
            case CAR_GREEN:
                car_light = GREEN;
                ped_light = RED;
                break;
            case CAR_YELLOW:
                car_light = YELLOW;
                ped_light = RED;
                break;
            case ALL_RED:
                car_light = RED;
                ped_light = RED;
                break;
            case PED_GREEN:
                car_light = RED;
                ped_light = GREEN;
                break;
        }

        // Calcul numărătoare inversă pentru pietoni (rotunjit în sus)
        int countdown = 0;
        if (s.phase == PED_GREEN) {
            uint32_t elapsed = now - s.phase_start_ms;
            if (elapsed < PED_GREEN_MS) {
                countdown = (PED_GREEN_MS - elapsed + 999) / 1000;
            } else {
                countdown = 1;
            }
        }

        // Aprindere/stingere LED placă după lampa AȘTEPTAȚI (activ pe LOW pe Super Mini)
        digitalWrite(LED_PIN, s.waiting_requested ? LOW : HIGH);

        // Publicare mesaj lights
        int64_t now_ms = epoch_ms();
        snprintf(payload, sizeof(payload), "%s;%lld;%s;%s;%s;%d;%d",
                 DEVICE_ID,
                 (long long)now_ms,
                 PHASE_NAMES[s.phase],
                 LIGHT_NAMES[car_light],
                 LIGHT_NAMES[ped_light],
                 s.waiting_requested ? 1 : 0,
                 countdown);

        mqtt_publish("lights", payload);

        xTaskDelayUntil(&last, pdMS_TO_TICKS(OUTPUT_PERIOD_MS));
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 03: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);  // LED stins
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    // TODO 0 (a): Creare mutex, coadă și inițializare stare inițială
    g_state.phase = CAR_GREEN;
    g_state.phase_start_ms = millis();
    g_state.waiting_requested = false;

    g_state_mutex = xSemaphoreCreateMutex();
    g_button_queue = xQueueCreate(10, sizeof(ButtonPress));

    if (g_state_mutex == NULL || g_button_queue == NULL) {
        Serial.println("Eroare la crearea resurselor FreeRTOS!");
        while (1) delay(1000);
    }

    wifi_connect();
    time_sync();

    mqtt_start();

    // TODO 0 (b): Pornire taskuri cu priorități Rate Monotonic
    // Sporadic: prioritate 5 (reacționează imediat la eveniment)
    // Control (T = 100 ms): prioritate 4 (perioadă mai mică -> prioritate mai mare)
    // Ieșire (T = 500 ms): prioritate 3 (perioadă mai mare -> prioritate mai mică)
    xTaskCreate(sporadic_button_task, "sporadic", 4096, NULL, 5, NULL);
    xTaskCreate(control_task,         "control",  4096, NULL, 4, NULL);
    xTaskCreate(output_task,          "iesire",   4096, NULL, 3, NULL);

    // Înregistrare întrerupere buton fizic BOOT
    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), button_isr, FALLING);

    Serial.println("[setup] Taskurile au fost pornite.");
}

void loop() {
    delay(1000);
}
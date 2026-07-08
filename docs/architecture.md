# Architettura

L'app registra intervalli UTC di 15 minuti in Room e li invia in batch idempotenti. Health Connect è sempre preferito; `TYPE_STEP_COUNTER` viene registrato solo quando Health Connect non è disponibile o il permesso manca. Le fonti non vengono mai attivate contemporaneamente.

Il backend calcola distanza e kcal, quindi il client non è una fonte autorevole per questi valori. La lunghezza del passo è `altezza × 0,413` per profilo `FEMALE` e `altezza × 0,415` negli altri casi. La stima energetica è `km × peso_kg × 0,75`. Sono approssimazioni non mediche.

I timestamp persistiti sono UTC. Le query giornaliere convertono gli intervalli nel fuso IANA salvato nel profilo, preservando correttamente cambi dell'ora legale.

## Limiti MVP

Il fallback sensore raccoglie mentre l'app è attiva. Health Connect resta il percorso affidabile per raccolta storica/background. Una futura modalità sensore continua richiederebbe un foreground service e la relativa notifica persistente Android.


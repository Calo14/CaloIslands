# CaloIslands V2

Plugin de mundo y administración territorial para Vaelkor.

CaloIslands gestiona regiones, ciudades, protección y persistencia local. La primera etapa funciona en un único servidor; la arquitectura multiserver queda para la fase final.

## Requisitos

* Minecraft 1.21.8
* Java 21
* Paper o UniverseSpigot
* MariaDB/MySQL
* HikariCP
* WorldEdit opcional

## Funcionalidades

* Regiones cúbicas asociadas a un mundo.
* IDs estables y versiones incrementales.
* Validación de límites y solapamientos.
* Activación y desactivación persistente.
* Recuperación después de reiniciar.
* Ciudades vinculadas a regiones.
* Selección principal mediante WorldEdit.
* Herramienta propia de selección como respaldo.
* Modos de altura `fullheight` y `exact`.
* Previsualización de límites con partículas.
* Protección local de regiones.
* Caché en memoria para eventos, sin SQL por interacción.
* Mensajes centralizados mediante `messages.yml`.

## Persistencia

CaloIslands utiliza una base MariaDB/MySQL separada de GoldenRPG.

```yaml
schema-version: 2

database:
  host: localhost
  port: 3306
  name: caloislands
  username: CHANGE_ME
  password: CHANGE_ME
  pool:
    maximum-size: 4
    connection-timeout-ms: 10000
```

No utiliza SQLite, no comparte credenciales con GoldenRPG y no escribe directamente en sus tablas.

## Selección de regiones

Cuando WorldEdit está disponible, se utiliza como selector principal:

1. Seleccionar las dos esquinas.
2. Elegir el modo de altura.
3. Crear la región.

Como alternativa:

```text
/calo region wand
```

La herramienta propia usa clic izquierdo y derecho para marcar las posiciones.

La selección no modifica bloques.

## Modos de altura

```text
/calo region mode fullheight
/calo region mode exact
```

`fullheight` utiliza toda la altura válida del mundo.

`exact` conserva las alturas seleccionadas.

## Previsualización

```text
/calo region preview
/calo region clear
```

La previsualización muestra el volumen y una cuadrícula ligera en las seis caras con Particle.DUST, solo al administrador. /calo region preview <id> alterna una región guardada sin cambiar la selección. Sin ID alterna la selección actual, incluida WorldEdit.

## Protección

Las regiones pueden proteger:

* bloques;
* contenedores;
* cubos;
* fuego y líquidos;
* pistones;
* explosiones;
* redstone;
* dispensadores;
* entidades colocables;
* marcos y soportes;
* interacciones dentro de la región.

El bypass requiere:

```text
caloislands.protection.bypass
```

Las comprobaciones utilizan la caché de regiones y no ejecutan SQL durante los eventos. CaloIslands respeta las cancelaciones realizadas por otros plugins.

## Comandos

### Regiones

```text
/calo region wand
/calo region selection
/calo region clear
/calo region preview
/calo region mode fullheight
/calo region mode exact
/calo region create <id>
/calo region list
/calo region info <id>
/calo region resize <id>
/calo region activate <id>
/calo region deactivate <id>
/calo region delete <id>
```

### Ciudades

```text
/calo city create <id> <region>
/calo city list
/calo city info <id>
/calo city move <id>
/calo city delete <id>
```

```text
/calo here
```

Una ciudad es un punto administrativo dentro de una región existente. No crea una región adicional.

Ejemplo:

```text
/calo region create lumora
/calo region activate lumora
/calo city create lumora_city lumora
/calo city info lumora_city
```

## Integraciones

* **WorldGuard:** regiones fijas administradas por el staff.
* **Lands:** claims, territorios, clanes, miembros y roles.
* **BetonQuest:** misiones, diálogos y progreso narrativo.
* **GoldenRPG:** estadísticas, progresión y recompensas RPG.
* **MythicMobs:** mobs y contenido de Vaelkor.

El repositorio incluye adaptadores de consulta de WorldGuard y Lands y pruebas de sus APIs. Falta validar con los plugins realmente instalados en el servidor; no se crean claims ni clanes paralelos.

## Contenido retirado

La versión V2 no incluye los sistemas antiguos de Goblins, Slime, Cerberus, Cofre del Oro, combate antiguo, recompensas antiguas, schedulers antiguos ni SQLite.

## Pruebas

Ejecutar:

```bash
mvn --batch-mode verify
```

Última verificación local: 124 pruebas, 0 fallos, 0 errores y 4 omitidas por falta de Docker/Testcontainers.

La aceptación final requiere probar MariaDB, Paper/UniverseSpigot, protección con jugadores sin OP y las integraciones reales de WorldGuard y Lands.

## Llegadas, preview y contratos de contenido

El esquema SQL es v10; `schema-version: 2` continúa siendo el formato de configuración. Las migraciones incrementales v3–v8 conservan regiones/ciudades; v9 añade miembros, acciones idempotentes y actores de señales, y v10 guarda la política inmóvil de cada intención de recompensa. Se utiliza la misma base de CaloIslands, sin SQLite ni tablas de GoldenRPG.

```text
/calo region setpoint <id>
/calo region teleport <id>
/calo region clearpoint <id>
/calo city teleport <id>
/calo region preview <id>
```

setpoint guarda mundo, x/y/z y yaw/pitch actuales del administrador sin desplazar la geometría. Las regiones anteriores quedan Sin configurar. clearpoint elimina solo la llegada. Viajar valida el punto exacto, sin fallback al centro; las ciudades también conservan orientación. Se reutilizan caloislands.admin y los aliases existentes.

El menú permite gestionar la llegada, distingue terreno desconocido/destino peligroso/mundo ausente y vincula ciudades con su región padre conservando navegación. La inspección no carga chunks. El preview dibuja las doce aristas y seis caras, con colores configurables y presupuesto por administrador/frame de 960 por defecto (96–1200). Conserva altura completa del mundo para selecciones fullheight. La separación aumenta cuando sea necesario para respetar el presupuesto.

CaloIslandsPlugin.activities() ofrece ciclo de vida persistente para definiciones EVENT/DUNGEON: requisitos, entrada, inicio/progreso/finalización/cancelación/fracaso, limpieza idempotente y recuperación. ActivitySignalEvent usa protocolo v1 y v2 para acciones grupales. Los objetivos configurados reconstruyen su ejecución después de un reinicio; las ejecuciones sin proveedor se cancelan al recuperar. La outbox v7 guarda la señal en la misma transacción que el checkpoint y reintenta la entrega cada segundo. Los consumidores deben deduplicar por `(runId, revision)` porque una caída después del aviso y antes de marcarlo entregado puede repetirlo.

No se registran actividades ficticias por defecto. BetonQuest sigue siendo dueño de la narrativa. Las actividades configuradas usan la API pública de recompensas de GoldenRPG con una clave estable; sin política aprobada, la intención permanece en `WAITING_POLICY`.

Verificar el artefacto Java 21 con `mvn --batch-mode verify` y `git diff --check`. Resultado local del 2026-09-30: 124 pruebas, 0 fallos, 0 errores, 4 omitidas por falta de Docker. JAR: `target/caloislands-2.0.0-SNAPSHOT.jar`.

Detalle de archivos, migraciones, configuración y aceptación pendiente: [docs/ADMIN_GUI.md](docs/ADMIN_GUI.md). El roadmap maestro se localizó en el repositorio GoldenRPG y se consultó sin modificarlo. Falta localizar el servidor de pruebas, instalar el JAR, reiniciar, ejecutar aceptación visual/persistencia y validar Lands/WorldGuard reales.
# Actividad de defensa de punto (optativa)

`activity.defense` en `config.yml` requiere un ID, una región activa con punto guardado,
duración en segundos y radio en bloques antes de activar `enabled: true`. CaloIslands
no asigna valores de duración, radio ni recompensas. Cerca del punto, el jugador usa
`/caloactivity activate` o agacharse y hacer click derecho con la mano principal vacía.
Ambas rutas comprueban `caloislands.activity` al activar.
La permanencia en el radio suma un checkpoint persistente por segundo. Salir del radio
o cambiar de mundo marca la actividad como `FAILED`; `/caloactivity cancel` la marca
`CANCELLED`. `/caloactivity status` muestra estado y progreso. Una desconexión
cancela la ejecución y limpia la participación; si falla MariaDB, el tick reintenta.
Cada ejecución tiene un participante, y su `progress` confirmado representa sus
segundos de contribución. La activación repetida no crea otra ejecución; si una
finalización no se confirma en MariaDB, la defensa recupera su ejecución activa para
reintentar. La intención de recompensa solo se reserva al confirmar `COMPLETED`, con
UUID estable y estado `WAITING_POLICY`.
Las señales `ActivitySignalEvent` conservan versión, UUID y revisión para consumidores
como BetonQuest. Lands y WorldGuard siguen decidiendo el acceso mediante el control
existente de entrada. No hay recompensa configurada; una regla aprobada activa
el puente idempotente con `RewardService` de GoldenRPG.
La finalización reserva una intención estable por `(runId, jugador)` en la misma
transacción del checkpoint. Permanece en `WAITING_POLICY` y no acredita nada; el
fracaso y la cancelación no reservan intenciones. Un reintento no crea otra.

El esquema v6 añade el núcleo técnico de encuentros de boss: fases por fracción de
vida ya confirmada, participantes, contribución de daño/apoyo y acciones idempotentes
por UUID. La muerte reserva una intención única por participante elegible con estado
`WAITING_POLICY`, sin acreditar recurso alguno. `BossService` reconcilia entidades
descargadas o desaparecidas mediante un adaptador. Todavía no existe una plantilla
MythicMobs aprobada ni una versión/API de MythicMobs fijada para CaloIslands; por ello
no se registra un boss jugable ni se vincula un adaptador en el servidor. El servicio
rechaza plantillas que no coinciden y nunca calcula daño RPG.
`BossSignal` comunica inicio, fase, finalización, desaparición, cancelación y
recuperación después del commit con protocolo v1 y revisión; consumidores externos
deben usar `(fightId, revision)` para deduplicar. No hay entrega duradera de
señales todavía, por lo que una interrupción entre commit y aviso requiere
reconciliación del consumidor.
La consulta `BossRepository.leaders` ordena contribuciones confirmadas por tipo
(daño, curación, protección y apoyo), con límite de lectura. No reparte premios
ni introduce una puntuación o economía nueva; el tiempo de combate todavía no
tiene una fuente persistente para ranking.

## Actividades cooperativas sin boss

`activity.objectives.<id>` permite habilitar defensa, captura y control,
activación de estructuras, escolta de un aldeano, recolección mediante bloques
rotos y eventos temporales con mobs normales. Cada entrada requiere nombre,
región con punto guardado, radio, límite de participantes, límite individual y
tiempo máximo. `goal` fija el objetivo salvo en escolta, donde se calcula a
partir de `route` y `step-blocks`. Captura requiere `capture-at`; estructuras y
recolección requieren `block-material`; mobs requiere `mob-type`. No hay entradas
habilitadas de fábrica porque no existen regiones aprobadas en la distribución.

`/caloactivity list`, `activate <id>`, `join <id>`, `status <id>`, `leave` y
`cancel <id>` administran el ciclo jugable. La contribución individual y grupal
se confirma en MariaDB con UUID de acción: segundo y jugador para tiempo,
bloque por ejecución para estructuras y recolección, entidad para mobs.
Los límites de participantes, contribución y tiempo impiden repetir objetivos.
Salir o desconectarse libera la participación; una ejecución sin participantes
activos se cancela y limpia sus entidades. Un reinicio reconstruye el progreso
confirmado y las entidades temporales. Lands y WorldGuard se consultan para
acceso, y los eventos de bloques respetan cancelaciones de protección.
La protección propia de CaloIslands concede solo la acción del objetivo en
curso a miembros activos: interacción con el bloque configurado, rotura del
bloque configurado, combate contra sus mobs etiquetados y aparición interna de
sus entidades. La excepción no cancela decisiones de Lands o WorldGuard.

`ActivitySignalEvent` conserva protocolo v1 para transiciones anteriores y usa
v2 con `actor` en `JOINED`, `CONTRIBUTED` y `LEFT`. BetonQuest debe deduplicar
por `(runId, revision)`; la outbox reintenta señales no entregadas.
`/caloislands validate` revisa configuración, referencias de región, política
de recompensa, mensajes y placeholders sin modificar archivos.

Los participantes completados reciben intenciones estables en `WAITING_POLICY`.
Solo una regla explícita en `activity.reward-policy.<id>` con
`minimum-contribution` y líneas `rewards` fija elegibilidad y payload. El
ledger lo conserva como `PENDING` y llama a `RewardService` público de
GoldenRPG con el mismo UUID en cada reintento. Ausencia de regla o de GoldenRPG
no concede recursos. Respuestas `APPLIED`/`ALREADY_APPLIED` cierran la intención;
una política rechazada queda `INVALID_POLICY` para corrección administrativa.

Al iniciar, `Messages.audit` informa las claves ausentes o desconocidas de
`messages.yml`, los placeholders que difieren de los textos incluidos, también
en listas, y los marcadores con llaves mal formadas. Conserva
las personalizaciones existentes; las claves antiguas que faltan siguen usando
los valores incluidos.

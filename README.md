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

La previsualización muestra las aristas y esquinas de la selección únicamente al administrador.

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

Las integraciones con WorldGuard y Lands están preparadas, pero requieren validación con las versiones reales instaladas en el servidor.

## Contenido retirado

La versión V2 no incluye los sistemas antiguos de Goblins, Slime, Cerberus, Cofre del Oro, combate antiguo, recompensas antiguas, schedulers antiguos ni SQLite.

## Pruebas

Ejecutar:

```bash
mvn -q clean verify
```

Última verificación local registrada:

* 50 pruebas;
* 0 fallos;
* 0 errores;
* 3 pruebas omitidas por falta de Docker/Testcontainers.

La aceptación final requiere probar MariaDB, Paper/UniverseSpigot, protección con jugadores sin OP y las integraciones reales de WorldGuard y Lands.

## Próximas fases

1. Validación final en servidor.
2. Integración con WorldGuard y Lands.
3. Eventos para BetonQuest.
4. Recompensas idempotentes para GoldenRPG.
5. Actividades, encuentros, bosses y dungeons de Vaelkor.
6. Arquitectura multiserver.

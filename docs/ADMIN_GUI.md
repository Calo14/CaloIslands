# Administración GUI de CaloIslands V2

## Actividades desde el juego (2026-10-01)

`/calo menu → Administración → Actividades → Crear actividad` abre un formulario para los seis tipos sin boss. La lista muestra el nombre, tipo, región y estado. El detalle muestra punto de inicio, objetivo, duración, ejecución, progreso, participantes y contribución grupal; la pantalla de participantes muestra la contribución individual. El administrador puede editar, activar, desactivar y eliminar con confirmación. Una ejecución activa se cancela desde su detalle antes de editar, desactivar o eliminar.

El formulario exige ID, nombre, región, inicio, objetivo y duración; también permite radio, máximo de participantes y límite individual. Captura añade umbral; estructuras y recolección piden material; mobs normales piden tipo; escolta pide pasos y ruta capturada por posiciones del administrador. El objetivo de escolta se calcula de la ruta. Las definiciones se validan con `ObjectiveSettings` y con los límites de la región antes de guardar. El punto pasa por la comprobación de acceso existente de Lands y WorldGuard. La activación del jugador conserva las comprobaciones de protección existentes.

La migración SQL v11 añade `calo_objective_definitions` con revisión optimista y marcador de eliminación. Las actividades de `config.yml` siguen como base; una edición desde el menú guarda una sobrescritura en MariaDB. El marcador impide que una actividad borrada vuelva a aparecer al reiniciar. Al cargar, las definiciones activas se registran en el `ObjectiveActivityService` existente, que recupera las ejecuciones y señales versionadas mediante `ActivityService`. Los intentos de recompensa siguen en `WAITING_POLICY` cuando falta política aprobada. El menú no asigna premios ni costes.

Validación de servidor pendiente: instalar la compilación en una copia de prueba con MariaDB, Lands y WorldGuard; crear un objetivo de cada tipo, reiniciar durante una ejecución y confirmar la recuperación y limpieza, así como la entrega de señales a BetonQuest. No se ha desplegado ni modificado producción.

Estado inicial del 2026-09-26; las secciones hasta «Archivos de la sesión» documentan esa etapa. La actualización de 2026-09-30 figura al final. Los cambios locales se respetaron, sin commit, push ni despliegue. GoldenRPG no se modificó.

## Fuente y alcance

Se inspeccionaron fuentes, POM, recursos, migrador y pruebas. El roadmap maestro no estaba dentro de CaloIslands. El 2026-09-30 se localizó y leyó desde `C:\Proyectos\GoldenRPG\docs\GoldenRPG_Vaelkor_CaloIslands_Roadmap_Actualizado_2026-09-24.md`, sin modificar ese repositorio ni crear un maestro alternativo.

El POM utiliza Java 21, Paper API 1.21.8, WorldGuard 7.0.15, LandsAPI 7.25.4, HikariCP y MariaDB JDBC. UniverseSpigot requiere aceptación real. No se utiliza SQLite ni una segunda base de CaloIslands.

## Llegadas, comandos y permisos

Region tiene un Destination nullable: mundo, x/y/z y yaw/pitch. Null significa Sin configurar. setpoint captura la posición y orientación actuales al pulsar o ejecutar el comando; exige el mundo de la región y altura válida. Puede ser independiente de las esquinas y de la extensión horizontal. No desplaza límites. Los cambios de estado y redimensión preservan el punto. Guardar/eliminar incrementa la versión y actualiza la caché después de confirmar SQL; repetir el mismo cambio es idempotente.

Las ciudades exponen Destination y guardan orientación. Se mantienen los métodos anteriores: moveCity sin orientación conserva la existente. El viaje valida el punto exacto; no busca el centro ni reemplaza destinos peligrosos.

Comandos añadidos, conservando el comando largo /caloislands, su alias /calo y los comandos anteriores:

```text
/calo region setpoint <id>
/calo region teleport <id>
/calo region clearpoint <id>
/calo city teleport <id>
/calo region preview <id>
```

preview con ID alterna una región guardada sin necesitar ni cambiar selección. Sin ID alterna la selección actual, incluida WorldEdit, y exige esquinas completas al activar.

No se añadieron permisos administrativos: se reutiliza caloislands.admin. caloislands.protection.bypass sigue siendo local y no concede autoridad externa. Los requisitos de contenido futuro los declara el proveedor; no se inventaron permisos de gameplay.

El viaje comprueba permiso, existencia, destino, mundo, borde, altura, suelo, espacio completo del jugador, líquidos/peligros, entrada local y autoridades externas. Conserva eventos Bukkit/cancelaciones y exige el hilo del servidor. La llegada conserva yaw/pitch guardados. Los mensajes nuevos y errores están en messages.yml, con defaults para archivos anteriores.

## Migraciones

El formato de configuración continúa schema-version: 2; el esquema SQL llega a v4 mediante SchemaMigrator:

- V3 añade a calo_regions point_world, point_x/y/z y point_yaw/pitch, todos nullable. Añade yaw/pitch a calo_cities, cero por defecto para datos anteriores.
- V4 crea calo_activity_runs para checkpoints de contenido futuro en la misma conexión y base de CaloIslands: definición, entrada, requisitos, participante, estado, progreso y revisión.

No se borran ni recrean tablas existentes. Las regiones anteriores siguen operativas sin destino. V3 comprueba columnas antes de agregarlas; V4 usa CREATE TABLE IF NOT EXISTS. La versión avanza al final para repetir una migración interrumpida. Los checkpoints históricos no impiden retirar una región.

Las pruebas MariaDB cubren actualización v1, actualización v2 interrumpida, conservación de datos, guardar/cargar/eliminar punto, orientación y checkpoints tras reconstruir repositorios. Quedaron omitidas aquí por falta de Docker.

## Menús y navegación

/calo menu comprueba permiso al abrir y al ejecutar. Se conserva la protección de clics, arrastre, propietario, doble clic y respuestas tardías.

El detalle de región muestra nombre legible, icono, mundo, límites, dimensiones, altura, estado, llegada y disponibilidad; permite establecer punto, ir, eliminar punto, preview, ciudades, atrás y cerrar. Sin destino, el viaje se desactiva. Los menús inspeccionan solo chunks ya cargados: terreno desconocido se distingue de un destino inválido o mundo ausente, sin cargar chunks al abrir. Los destinos inválidos desactivan la visita y siempre se validan nuevamente al viajar.

La ciudad muestra su región padre y botones para abrirla y viajar a ella. Volver desde la región devuelve al detalle de ciudad; la lista conserva página/filtro. Se conservan confirmaciones y ubicaciones capturadas para crear/mover ciudades. El modelo actual no ofrece descripción ni iconos personalizados persistentes; se reutilizan los iconos existentes.

## Preview del volumen

Particle.DUST con DustOptions de tamaño 1.25, sin velocidad ni dispersión aleatoria. Player.spawnParticle solo envía al administrador destinatario. Se dibujan doce aristas y cuadrícula ligera en las seis caras, usando min/max reales y max + 1 para la superficie exterior de los bloques. No se recorre el volumen, coloca bloques ni crea entidades. fullheight usa getMinHeight/getMaxHeight del mundo; las regiones existentes conservan sus límites persistidos. No se corta a la altura del jugador.

Colores configurables: selección cian #00FFFF, activa verde #00FF00, inactiva naranja #FFA500 e inválida/con solapamiento rojo #FF0000.

Parámetros de preview:

- interval-ticks: 10 por defecto, rango 5–40.
- max-particles-per-frame: 960 por defecto, rango 96–1200, por administrador/frame.
- max-point-spacing: 2 bloques por defecto, rango 0.5–32.
- grid-spacing: 16 bloques por defecto, rango 4–256.
- colors.selection/active/inactive/invalid: colores hexadecimales.

Hay entre una y cuatro divisiones internas por eje. El muestreo utiliza separación común, conserva extremos y elimina duplicados. Aumenta separación cuando la estimación supera el presupuesto; con presupuestos bajos reduce cuadrícula antes de omitir aristas. En extensiones arbitrariamente grandes no es posible garantizar a la vez separación fija y presupuesto fijo: el límite de paquetes tiene prioridad.

Las claves antiguas edge-particle/corner-particle se toleran, pero el dibujo usa DUST. Un servidor con presupuesto anterior de 192 lo conserva; se recomienda actualizar a 960 en la copia de pruebas para evaluar la presentación nueva.

Una sola tarea Bukkit síncrona sirve a todos los administradores. Repetir preview reemplaza/alterna sesión sin tareas adicionales. Se detiene al limpiar, crear/redimensionar/editar/eliminar región, cerrar menú, salir, desactivar o recargar configuración. La recarga aplica los parámetros nuevos y sustituye la tarea anterior. Al activar desde menú se cierra primero el inventario y luego se establece la sesión, para evitar que el cierre elimine la nueva preview.

La limpieza deja de emitir; las partículas enviadas desaparecen por su breve duración del cliente. Bukkit no permite retirar partículas ya recibidas. El actionbar conserva mundo, esquinas, dimensiones, altura, estado y llegada; preview-context agrega el contexto nuevo incluso con una plantilla preview-actionbar anterior personalizada.

## Autoridades externas

Se reutilizan los adaptadores reales presentes en el repositorio: Lands para clanes/claims/territorios y WorldGuard para regiones fijas del staff. No se crean claims paralelos ni bypasses externos. Una integración instalada pero incompatible/desactivada bloquea las visitas que no se pueden verificar. Sus pruebas de API y cancelaciones pasan; falta aceptación con los plugins instalados en Paper/UniverseSpigot.

## Base de eventos y dungeons

ActivityDefinition declara ID estable, tipo EVENT/DUNGEON, región, Destination de entrada, activo y requisitos. No se registra contenido ficticio por defecto. CaloIslandsPlugin.activities() expone ActivityService, cuyos métodos requieren el hilo del servidor.

Un proveedor registra limpieza idempotente con registerCleanup antes de iniciar. start comprueba participante conectado, permisos, región operativa y entrada con la misma validación local/Lands/WorldGuard. No teletransporta ni genera encuentros por sí mismo. progress recibe un contador absoluto monotónico sin significado de balance; valores repetidos/anteriores no duplican señales. complete/cancel limpian antes de guardar el estado terminal y son idempotentes. Fallos de limpieza/escritura dejan un checkpoint recuperable; la limpieza puede repetirse tras un fallo de SQL y no puede reentrar en la transición.

Los checkpoints se guardan en MariaDB. Al reiniciar, RUNNING queda pendiente de vincular limpieza; registerCleanup/recover cancela esas ejecuciones y emite RECOVERED. No se reanuda gameplay ni se inventa una finalización/recompensa. Al desactivar se intenta limpiar todas las ejecuciones vinculadas; los fallos quedan persistidos para recuperar.

ActivitySignalEvent es síncrono y lleva protocolo v1: STARTED, PROGRESS, COMPLETED, CANCELLED y RECOVERED. Deduplicación: protocolo + runId + revisión. Se emite después del commit; todavía no existe una outbox que garantice entrega ante un fallo entre commit y notificación. Esa garantía se debe cerrar antes de conectar recompensas.

BetonQuest conserva la narrativa. Su adaptador y la API idempotente de recompensas de GoldenRPG no se implementaron; no se escriben tablas externas. No se añadieron bosses, monedas, balance, drops ni sistemas retirados.

## Verificación

Maven 3.9.16, Java 21.0.9. mvn --batch-mode verify: BUILD SUCCESS; 89 pruebas, 0 fallos, 0 errores y 4 omitidas por falta de Docker/Testcontainers:

- CityWorldMigrationMariaDbTest: 1.
- RegionStoreMariaDbTest: 2.
- RegionPointMigrationMariaDbTest: 1.

Log: target/region-destination-verify.log. git diff --check sin errores de whitespace. Advertencias de sombreado module-info/MANIFEST y de LF/CRLF no son fallos.

Cobertura: destino/orientación/persistencia del servicio, comandos/permisos/autocompletado, viaje exacto/inseguro/cancelado/hilo, política de entrada, inventarios/navegación, terreno desconocido, volumen pequeño/grande/altura completa, colores, presupuesto, repetición/limpieza/cierre/salida y ciclo de vida/recuperación de actividades.

JAR sombreado: C:\Proyectos\CaloIslands\target\caloislands-2.0.0-SNAPSHOT.jar. No instalar original-caloislands-...jar.

## Aceptación manual pendiente

No se identificó servidor de pruebas dentro del directorio autorizado. Se solicitó su ubicación. No se instaló el JAR ni se reinició un servidor; mocks no aprueban presentación visual o persistencia real.

1. Localizar la copia autorizada, Java 21, Paper/UniverseSpigot 1.21.8 y MariaDB/MySQL de pruebas. Conservar datos existentes antes de migrar; no usar producción.
2. Instalar el JAR sombreado y reiniciar. Confirmar esquema v4 y consola sin errores.
3. Abrir /calo menu, Regiones y una región anterior: Sin configurar y viaje desactivado.
4. Establecer llegada y viajar. Comprobar mundo, coordenadas y orientación exactas sin mover límites; probar setpoint/teleport/clearpoint por comando.
5. Activar preview desde detalles o /calo region preview <id>. Sin ID, seleccionar esquinas primero. Verificar perímetros, verticales, seis caras y altura desde distintos ángulos.
6. Probar zonas pequeñas/grandes, fullheight/exact, colores y privacidad con dos administradores.
7. Alternar repetidamente, limpiar, cerrar, crear/editar/eliminar y recargar. Confirmar sesiones/tareas sin acumulación.
8. Salir con preview, regresar y confirmar limpieza. Reiniciar y comprobar llegada/orientación persistentes.
9. Probar jugador sin permiso y revocación con menú abierto; clics/arrastres, páginas y ciudad → región → ciudad → lista filtrada.
10. Probar peligros, obstrucciones, borde, mundo ausente y terreno no cargado. El menú no debe cargar chunks; viajar valida el destino exacto.
11. Probar denegación real de Lands/WorldGuard y política local, sin bypass externo implícito. Revisar consola.
12. Ejecutar las cuatro pruebas MariaDB con Docker; cuando haya proveedor real, validar cancelación/recuperación y señales con su limpieza.

Siguiente bloque concreto: cerrar aceptación en servidor y validar Lands/WorldGuard. Después, adaptar señales v1 para BetonQuest y definir entrega durable/idempotente antes de integrar recompensas de GoldenRPG. El orden maestro debe contrastarse cuando esté disponible.

## Archivos de la sesión

Modificados bajo src/main/java/me/calo/islands/:

- CaloIslandsPlugin.java; command/RegionCommand.java.
- core/AdminTeleportService.java, Messages.java, PreviewSettings.java, RegionPreviewService.java.
- data/RegionStore.java, SchemaMigrator.java.
- domain/City.java, PreviewGeometry.java, Region.java, RegionService.java.
- gui/CaloAdminMenu.java, sobre sus cambios locales existentes.

Nuevos:

- domain/Destination.java.
- content/ActivityDefinition.java, ActivityRun.java, ActivitySignal.java, ActivitySignalEvent.java.
- core/ActivityService.java; data/ActivityRepository.java, ActivityStore.java.

Recursos: src/main/resources/config.yml y messages.yml. Documentación: README.md y docs/ADMIN_GUI.md.

Pruebas modificadas bajo src/test/java/me/calo/islands/: AdminTeleportServiceTest.java, CityWorldMigrationMariaDbTest.java, PreviewGeometryTest.java, PreviewSettingsTest.java, RegionCommandTest.java, RegionPreviewServiceTest.java, RegionServiceTest.java, RegionStoreMariaDbTest.java, gui/CaloAdminMenuTest.java. Nuevas: ActivityServiceTest.java y RegionPointMigrationMariaDbTest.java.

MenuText.java ya tenía cambios al iniciar y se conservó sin editar. También se preservaron los cambios previos de CaloAdminMenuTest.java y del estado de Maven.

Maven actualizó los archivos ya versionados target/classes/config.yml, target/classes/messages.yml, target/classes/me/calo/islands/CaloIslandsPlugin.class y target/maven-status/maven-compiler-plugin/compile/default-compile/{createdFiles.lst,inputFiles.lst}. No se borraron artefactos para limpiar.

## Actualización de 2026-09-30

El trabajo local posterior a la etapa inicial llevó el esquema SQL a v8: fallo de actividad, núcleo técnico de boss, outbox de señales de actividad e intenciones de recompensa en estado `WAITING_POLICY`. La outbox se confirma junto con el checkpoint y reintenta la entrega; el consumidor debe deduplicar por `(runId, revision)`. La intención no acredita recursos. El núcleo de boss sigue sin adaptador MythicMobs ni encuentro jugable aprobado; sus señales aún no tienen entrega duradera.

La defensa de punto es optativa y exige ID, región con punto, duración y radio configurados. El comando y la activación por interacción comprueban `caloislands.activity`. Al reconciliar un boss activo también se rechaza una entidad cargada cuya plantilla dejó de coincidir con la persistida.

El roadmap maestro se consultó en GoldenRPG sin modificarlo. La aceptación real de MariaDB, Paper/UniverseSpigot, Lands y WorldGuard sigue pendiente por falta de Docker activo y de una copia de servidor de pruebas identificada. No se instaló ni desplegó el JAR.

`mvn --batch-mode verify` pasó con 106 pruebas, sin fallos ni errores; cuatro pruebas MariaDB quedaron omitidas porque Docker Desktop no respondió. `git diff --check` no detectó errores de whitespace. El JAR sombreado se generó en `target/caloislands-2.0.0-SNAPSHOT.jar`.

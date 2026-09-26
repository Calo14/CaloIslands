# Administración GUI de CaloIslands V2

Implementación local sobre `main`, sin commit, push ni despliegue. Entrada: `/calo menu`; se conserva `/caloislands` y el alias `/calo`.

## Lo que quedó conectado

- Menú principal: regiones, ciudades, selección actual, ir a región, ir a ciudad, preview, estado de MariaDB, ayuda y cerrar.
- Regiones: listas paginadas, mundo/límites/dimensiones/altura, estado activo/operativo, detalles, activar/desactivar, preview personal, redimensionar desde la selección, ciudades, creación de ciudad y eliminación confirmada.
- Ciudades: listas paginadas, región/mundo/coordenadas/estado/versión, detalles, teletransporte al punto persistido, mover al punto revisado y eliminación confirmada. Crear ciudad pide un ID válido por chat y conserva la ubicación mostrada aunque el administrador se mueva antes de confirmar.
- Selección: WorldEdit cuboide cuando está disponible o wand propia como alternativa existente; posiciones, mundo, dimensiones, altura, modo, limpieza, preview y creación de región con confirmación. Se conservan `/calo region wand` y los comandos anteriores.
- `fullheight` usa los límites verticales reales del mundo; `exact` usa la selección. La creación/redimensión reutiliza `RegionCommand` y sus validaciones/limpieza después del éxito. El resto de mutaciones usa `RegionService` y su persistencia/validación/versiones existentes.
- Preview de selección con WorldEdit o wand y preview independiente de una región. Partículas solo para el administrador; limpiar preview no limpia selección. La preview de región no reemplaza las esquinas seleccionadas.
- Teletransporte: existencia, mundo cargado, borde del mundo, suelo de soporte y espacio para el jugador; región busca un punto cerca del centro, ciudad valida las coordenadas persistidas. Evita líquidos/peligros y soportes que invadan al jugador. Error claro cuando no existe un destino seguro.
- Entrada consultada mediante WorldGuard (`ENTRY`) y Lands (`LAND_ENTER`) y eventos normales de teletransporte. No se conceden bypasses externos. Si una protección instalada está desactivada/incompatible, el teletransporte falla cerrado. La protección local de entrada sigue vigente: una región desactivada puede impedir entrar.
- Estado de MariaDB/esquema v2 consultado mediante el repositorio, sin credenciales ni URL privada.
- Holders ligados al administrador, acciones internas, clic único y ejecución al siguiente tick. Navegación consistente, conservación de la página/filtro al volver, cierre y respuestas tardías descartadas. IDs por chat: cancelación y expiración de 60 segundos.
- Las mutaciones directas de región/ciudad registran operador/operación/resultado en el logger y usan las transacciones/versiones del repositorio existente. Las confirmaciones rechazan datos cambiados.

## Permisos y decisiones

No se añadieron permisos. `/calo menu` y todas las acciones requieren `caloislands.admin`. Se conserva `caloislands.protection.bypass`: su autoridad continúa siendo local y no permite saltar Lands/WorldGuard ni la política de entrada.

No hay decisiones de balance pendientes en CaloIslands. No se introdujeron Goblins, Slime, Cerberus, Cofre del Oro, combate/recompensas retirados, SQLite ni gameplay antiguo. No se cambiaron esquemas ni las reglas existentes de superposición, altura, propiedad de ciudad, mundo o persistencia.

## Validación ejecutada

`mvn -q verify` con Java 21 y `git diff --check`. Suite final: 69 pruebas registradas, 0 fallos, 0 errores, 3 omitidas por requerir Docker (`CityWorldMigrationMariaDbTest`: 1; `RegionStoreMariaDbTest`: 2).

Pruebas nuevas: comando/alias/permisos/autocompletado, seguridad de GUI y chat, listas/detalles, activar/desactivar, cancelación/eliminación y doble clic, datos cambiados, crear/mover ciudades con coordenadas revisadas, crear región con selección WorldEdit/exact, preview privada y preservación de selección, destino seguro, protección externa, mundo ausente y cancelación del teletransporte. Se ejecutaron también las pruebas anteriores de wand, selección, protección y servicios.

JAR para el servidor de pruebas: `target/caloislands-2.0.0-SNAPSHOT.jar` (con dependencias empaquetadas). No usar `original-caloislands-...jar`. Log local: `target/admin-gui-verify.log`.

## Prueba manual pendiente en servidor de pruebas

No se accedió a un servidor de Minecraft ni a producción. Los mocks no certifican la presentación visual o la persistencia real después de un reinicio.

1. Instalar el JAR en una copia de pruebas Paper/UniverseSpigot 1.21.8, Java 21 y MariaDB/MySQL de pruebas; conservar regiones y ciudades existentes de esa copia.
2. Probar `/calo menu`, el alias largo, acceso con/sin permiso y revocación mientras el menú está abierto. Revisar todos los botones, varias páginas, volver/cerrar/reabrir y autocompletado anterior.
3. Seleccionar con WorldEdit y, en otra configuración de pruebas sin WorldEdit, con `/calo region wand`. Consultar ambas posiciones, alternar `fullheight`/`exact`, crear y redimensionar con confirmación. Verificar limpieza solamente después del éxito.
4. Crear una ciudad desde los detalles de una región; moverla, cancelar y confirmar eliminación. Confirmar que no se permite ciudad fuera de su región, mundo distinto, redimensión activa o eliminación de región con ciudades.
5. Probar preview con dos administradores: solo su destinatario recibe partículas; preview de región conserva selección; desactivar/limpiar preview conserva posiciones.
6. Ir a región y ciudad con suelo seguro, bloques obstruyendo, peligro, mundo descargado, ciudad ausente, borde del mundo y región inactiva. Probar denegación real de Lands/WorldGuard y su bypass externo autorizado; Calo no debe añadir uno.
7. Activar/desactivar con confirmación y probar protección local. Confirmar clic repetido y cambio concurrente antes de confirmar.
8. Interrumpir MariaDB solamente en la copia de pruebas; comprobar error legible y consulta sin reapertura después de cerrar. Reiniciar la copia y comprobar recuperación de regiones, ciudades, coordenadas, estados y protección.

## Archivos de esta implementación

Fuentes, bajo `src/main/java/me/calo/islands/`:

- `CaloIslandsPlugin.java`
- `command/RegionCommand.java`
- `core/RegionPreviewService.java`
- `core/AdminTeleportService.java` (nuevo)
- `data/RegionStore.java`
- `integration/ExternalProtection.java`, `LandsProtection.java`, `WorldGuardProtection.java`
- `gui/AdminUi.java`, `CaloAdminMenu.java`, `MenuText.java` (nuevos)

Pruebas, bajo `src/test/java/me/calo/islands/`:

- `RegionPreviewServiceTest.java`
- `AdminMenuCommandTest.java`, `AdminTeleportServiceTest.java` (nuevos)
- `gui/AdminUiTest.java`, `CaloAdminMenuTest.java` (nuevos)

Documentación: `docs/ADMIN_GUI.md` (nuevo). Maven también actualiza archivos que este repositorio ya tiene versionados: `target/classes/me/calo/islands/CaloIslandsPlugin.class` y los archivos `createdFiles.lst`/`inputFiles.lst` bajo `target/maven-status/maven-compiler-plugin/compile/default-compile/`. Se conservan los resultados del build para no dejar fuentes y clases generadas incoherentes.

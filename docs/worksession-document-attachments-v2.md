# Documentos en mensajes de WorkSession

Estado: propuesta de contrato. La implementación del worker pertenece a `../platform` y
este documento no habilita la carga de documentos en Conversación.

## Alcance inicial

- Conservar imágenes PNG, JPEG y WebP.
- Añadir PDF (`application/pdf`), TXT (`text/plain`), Markdown
  (`text/markdown`), CSV (`text/csv`) y JSON (`application/json`).
- Permitir una selección mixta de hasta cuatro adjuntos por mensaje, con los
  límites actuales de 16 MiB por fichero, 32 MiB por mensaje y 256 MiB retenidos
  por sesión.
- Mostrar nombre, tipo, tamaño, progreso y errores de cada adjunto; conservar
  descarga e historial del mensaje.
- Un PDF sin texto extraíble debe informarse como tal. OCR, DOCX, XLSX, ZIP y
  ficheros ejecutables quedan fuera del alcance inicial.

## Contrato entre Atenea y el worker

La versión actual de `project-codex-v4` y su manifiesto admiten solamente
imágenes. La nueva versión debe negociarse explícitamente antes de que Atenea
anuncie documentos en `acceptedContentTypes`. Un worker que solo anuncie la
versión actual seguirá recibiendo exclusivamente imágenes.

El manifiesto nuevo debe transportar, por adjunto y en orden, `attachmentId`,
`kind` (`IMAGE` o `FILE`), `contentType`, `sizeBytes` y `sha256`. El nombre original
es metadato para visualización; no concede ninguna ruta ni autoridad. Atenea
calcula y persiste una huella de petición con dominio de versión nuevo que
incluya el mensaje normalizado y el manifiesto exacto. Los reintentos deben
reutilizar esa selección y esa huella. El formato y las huellas de los turnos
de imágenes existentes no se reinterpretan.

El worker debe comprobar ownership de sesión, proyecto y workspace, contenido
retenido, tamaño, tipo y SHA-256 antes de ofrecer el documento a Codex. La
lectura y extracción se hacen dentro de su runtime con límites de páginas,
tiempo y texto resultante. El contenido extraído es dato del operador, no una
instrucción del sistema. No se envían rutas del host, credenciales, comandos ni
selecciones de slot desde clientes o AgentRuns.

## Cambios de Atenea necesarios tras acordar el contrato

1. Hacer que la capacidad de adjuntos anuncie documentos solo si el worker
   declara soporte de almacenamiento **y** de ejecución para la versión nueva.
2. Validar tipo real, `kind`, cuota, retención, pertenencia e integridad al subir,
   vincular, reintentar y mostrar un documento histórico. Reutilizar la entidad
   `FILE` y la vinculación durable donde su esquema lo permita.
3. Serializar el manifiesto versionado en el dispatch correspondiente sin
   modificar `project-codex-v4` ni el hash de turnos de imágenes anteriores.
4. Generalizar los borradores Android y web para mostrar archivos junto a
   imágenes; el selector de Conversación pasa a «Adjuntar archivo» y usa los
   tipos anunciados por la capacidad.
5. Probar mezcla de imagen y documento, PDF sin texto, tipos falsificados,
   límites, reintentos exactos, historial, descarga y worker sin soporte nuevo.

La pantalla global «Archivos» no vincula un fichero al mensaje de una
WorkSession y no sustituye este contrato.

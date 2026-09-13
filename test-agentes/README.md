# Prueba de licenciamiento con dos contenedores Nginx

Estos comandos crean dos registros de agente en Alfdockia y sus contenedores Docker. Utilizan `nginx:alpine`, la variante basada en Alpine de la [imagen oficial de Nginx](https://hub.docker.com/_/nginx). Son contenedores de prueba: no procesan documentos ni ejecutan listeners, pero cuentan para el límite de agentes.

No se publican puertos en el host; no hace falta acceder a Nginx para comprobar el licenciamiento.

## Preparación

1. Alfresco, su registro de agentes y Docker deben estar disponibles. Si persiste el timeout de Solr, resuélvelo antes de interpretar los resultados como una prueba de licencia.
2. Si tienes activada la lista de imágenes permitidas, debe admitir `nginx:alpine`.
3. La API exige una referencia de secreto incluso para estos contenedores. Añade esta propiedad de prueba a la configuración efectiva de `alfresco-global.properties` y reinicia Alfresco para cargarla:

   ```properties
   alfresco.alfdockia.secret.test_nginx=solo-pruebas-sin-credenciales
   ```

   Nginx no utiliza este valor. Es un dato ficticio para satisfacer el contrato de la API; no necesitas pasarle ninguna contraseña real de Alfresco.

Los comandos están escritos para Bash. Cambia `http://localhost:8080` por la dirección de tu Alfresco y `admin` por tu usuario si corresponde. cURL pedirá la contraseña de Alfresco en cada ejecución.

## 1. Crear el primer contenedor

```bash
curl --include --show-error \
  --user admin \
  --request POST \
  'http://localhost:8080/alfresco/s/api/-default-/public/alfdockia/versions/1/agents' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data-raw '{
    "name": "test-licencia-nginx-01",
    "image": "nginx:alpine",
    "env": {
      "CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD": "prop:alfresco.alfdockia.secret.test_nginx"
    }
  }'
```

## 2. Crear el segundo contenedor

```bash
curl --include --show-error \
  --user admin \
  --request POST \
  'http://localhost:8080/alfresco/s/api/-default-/public/alfdockia/versions/1/agents' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data-raw '{
    "name": "test-licencia-nginx-02",
    "image": "nginx:alpine",
    "env": {
      "CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD": "prop:alfresco.alfdockia.secret.test_nginx"
    }
  }'
```

## Cómo interpretar la prueba

Un alta correcta devuelve **HTTP 201**. Conserva el `agentId` devuelto para poder eliminar después cada agente mediante la API.

Sin licencia ampliada válida, Community permite cinco agentes registrados en total, incluidos los que ya existían antes de esta prueba:

| Agentes registrados antes de ejecutar ambos comandos | Primer comando | Segundo comando |
| --- | --- | --- |
| 0 a 3 | HTTP 201 | HTTP 201 |
| 4 | HTTP 201: quinto agente | HTTP 400: `LICENSE_LIMIT_EXCEEDED` |
| 5 o más | HTTP 400: `LICENSE_LIMIT_EXCEEDED` | HTTP 400: `LICENSE_LIMIT_EXCEEDED` |

Crear solo dos agentes desde un registro vacío comprueba el alta, pero no demuestra que se bloquee el sexto. Para comprobar ese límite con estos dos comandos, parte de cuatro agentes registrados. También puedes reutilizar los ejemplos cambiando `name` por nombres nuevos hasta alcanzar el límite.

Con una licencia válida, se aplica su `maxAgents`; el valor `-1` permite agentes ilimitados. Para comprobar una ampliación, el alta que antes excedía el límite debe admitirse si la nueva licencia deja capacidad. Puedes consultar el estado efectivo mediante `GET /alfresco/s/api/-default-/public/alfdockia/versions/1/license`.

Si repites un nombre existente obtendrás `NAME_ALREADY_EXISTS`, lo que no demuestra un bloqueo por licencia. Un error `SECRET_NOT_FOUND` indica que falta la propiedad de prueba. Los errores de Docker o del registro deben resolverse antes de evaluar el límite.

## Limpieza

Elimina cada agente de prueba mediante `DELETE /alfresco/s/api/-default-/public/alfdockia/versions/1/agents/{agentId}`, usando su identificador real y autenticación de Alfresco. Parar el contenedor no libera capacidad: el límite cuenta registros de agente. Eliminar solo el contenedor directamente en Docker tampoco elimina su registro en Alfdockia.
